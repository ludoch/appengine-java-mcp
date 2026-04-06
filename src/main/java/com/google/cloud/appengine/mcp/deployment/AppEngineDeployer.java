/*
Copyright 2025 Google LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.google.cloud.appengine.mcp.deployment;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.serviceusage.v1.EnableServiceRequest;
import com.google.api.serviceusage.v1.GetServiceRequest;
import com.google.api.serviceusage.v1.ServiceUsageClient;
import com.google.api.serviceusage.v1.ServiceUsageSettings;
import com.google.appengine.v1.CreateVersionRequest;
import com.google.appengine.v1.Deployment;
import com.google.appengine.v1.Version;
import com.google.appengine.v1.VersionsClient;
import com.google.appengine.v1.VersionsSettings;
import com.google.appengine.v1.ZipInfo;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.appengine.mcp.cloud.AppEngineApiService;
import com.google.cloud.appengine.mcp.cloud.AuthService;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * App Engine deployment orchestrator.
 */
public final class AppEngineDeployer {

  private static final Logger log = LoggerFactory.getLogger(AppEngineDeployer.class);

  private AppEngineDeployer() {}

  public static String deploy(DeployConfig config) throws IOException {
    validateConfig(config);
    progress(config, "Starting App Engine deployment for service '%s' in project '%s'..."
        .formatted(config.serviceName(), config.projectId()));

    ensureApisEnabled(config.projectId(), DeploymentConstants.REQUIRED_APIS_SOURCE,
        config.accessToken(), config);

    String bucketName = config.projectId() + DeploymentConstants.BUCKET_SUFFIX;
    ensureBucket(config.projectId(), bucketName, config.accessToken(), config);

    progress(config, "Zipping source files…");
    byte[] zipBytes = zipFiles(config.files(), config);

    if (zipBytes.length > DeploymentConstants.MAX_SOURCE_SIZE_BYTES) {
      throw new IOException("Source archive (%d MiB) exceeds the %d MiB limit."
          .formatted(zipBytes.length / (1024 * 1024),
              DeploymentConstants.MAX_SOURCE_SIZE_BYTES / (1024 * 1024)));
    }

    progress(config, "Uploading source to gs://%s/%s…".formatted(
        bucketName, DeploymentConstants.ZIP_FILE_NAME));
    String gcsUri = uploadToGcs(
        config.projectId(), bucketName, zipBytes, config.accessToken(), config);

    progress(config, "Creating App Engine version…");
    String versionId = deployVersion(config, gcsUri);

    String url = AppEngineApiService.buildServiceUrl(config.projectId(), config.serviceName());
    progress(config, "Deployment complete. Service URL: " + url + "  (version: " + versionId + ")");

    recordDeployment(config.projectId(), config.serviceName(), versionId, url);

    return url;
  }

  private static void ensureApisEnabled(
      String projectId, List<String> apis, String accessToken, DeployConfig config) {
    progress(config, "Ensuring required APIs are enabled: " + String.join(", ", apis));
    try {
      GoogleCredentials creds = AuthService.getCredentials(accessToken);
      var settings = ServiceUsageSettings.newBuilder()
          .setCredentialsProvider(FixedCredentialsProvider.create(creds))
          .build();
      try (var client = ServiceUsageClient.create(settings)) {
        for (String api : apis) {
          String resourceName = "projects/" + projectId + "/services/" + api;
          try {
            var getReq = GetServiceRequest.newBuilder()
                .setName(resourceName)
                .build();
            var svc = client.getService(getReq);
            if (svc.getState() != com.google.api.serviceusage.v1.State.ENABLED) {
              progress(config, "Enabling API: " + api);
              var enableReq = EnableServiceRequest.newBuilder()
                  .setName(resourceName)
                  .build();
              client.enableServiceAsync(enableReq).get();
            }
          } catch (Exception e) {
            log.warn("Could not verify/enable API {}: {}", api, e.getMessage());
          }
        }
      }
    } catch (Exception e) {
      log.warn("API enablement check failed (continuing): {}", e.getMessage());
    }
  }

  private static void ensureBucket(
      String projectId, String bucketName, String accessToken, DeployConfig config)
      throws IOException {
    progress(config, "Ensuring staging bucket gs://%s exists…".formatted(bucketName));
    try {
      GoogleCredentials creds = AuthService.getCredentials(accessToken);
      Storage storage = StorageOptions.newBuilder()
          .setProjectId(projectId)
          .setCredentials(creds)
          .build()
          .getService();

      var bucket = storage.get(bucketName);
      if (bucket == null) {
        storage.create(BucketInfo.newBuilder(bucketName)
            .setLocation("US")
            .build());
        progress(config, "Created staging bucket gs://" + bucketName);
      }
    } catch (Exception e) {
      throw new IOException("Failed to ensure staging bucket: " + e.getMessage(), e);
    }
  }

  private static String uploadToGcs(
      String projectId, String bucketName, byte[] data, String accessToken, DeployConfig config)
      throws IOException {
    try {
      GoogleCredentials creds = AuthService.getCredentials(accessToken);
      Storage storage = StorageOptions.newBuilder()
          .setProjectId(projectId)
          .setCredentials(creds)
          .build()
          .getService();

      BlobId blobId = BlobId.of(bucketName, DeploymentConstants.ZIP_FILE_NAME);
      BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
          .setContentType("application/zip")
          .setMetadata(Map.of("created-by", DeploymentConstants.LABEL_CREATED_BY))
          .build();
      storage.create(blobInfo, data);

      String uri = "https://storage.googleapis.com/" + bucketName
          + "/" + DeploymentConstants.ZIP_FILE_NAME;
      progress(config, "Source uploaded: " + uri);
      return uri;
    } catch (Exception e) {
      throw new IOException("Failed to upload source: " + e.getMessage(), e);
    }
  }

  private static String deployVersion(DeployConfig config, String gcsUri) throws IOException {
    String versionId = "v" + Instant.now().toEpochMilli();

    try {
      GoogleCredentials creds = AuthService.getCredentials(config.accessToken());
      VersionsSettings settings = VersionsSettings.newBuilder()
          .setCredentialsProvider(FixedCredentialsProvider.create(creds))
          .build();

      try (VersionsClient client = VersionsClient.create(settings)) {
        Version version = Version.newBuilder()
            .setId(versionId)
            .setRuntime(config.runtime() != null ? config.runtime()
                : DeploymentConstants.DEFAULT_RUNTIME)
            .setEnv(DeploymentConstants.DEFAULT_ENV)
            .setServingStatus(com.google.appengine.v1.ServingStatus.SERVING)
            .setDeployment(Deployment.newBuilder()
                .setZip(ZipInfo.newBuilder()
                    .setSourceUrl(gcsUri)
                    .build())
                .build())
            .putAllBetaSettings(Map.of("created-by", DeploymentConstants.LABEL_CREATED_BY))
            .build();

        String parent = "apps/" + config.projectId() + "/services/" + config.serviceName();
        progress(config, "Submitting version %s to %s…".formatted(versionId, parent));

        CreateVersionRequest request = CreateVersionRequest.newBuilder()
            .setParent(parent)
            .setVersion(version)
            .build();

        var operation = client.createVersionAsync(request);
        Version deployed = operation.get();
        return deployed.getId().isEmpty() ? versionId : deployed.getId();
      }
    } catch (Exception e) {
      throw new IOException("Version creation failed: " + e.getMessage(), e);
    }
  }

  private static byte[] zipFiles(List<FileEntry> files, DeployConfig config) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(baos)) {
      for (FileEntry entry : files) {
        if (entry.path() != null) {
          Path p = Path.of(entry.path());
          if (Files.isDirectory(p)) {
            zipDirectory(p, p, zos, config);
          } else {
            addZipEntry(zos, p.getFileName().toString(), Files.readAllBytes(p));
          }
        } else if (entry.filename() != null && entry.content() != null) {
          addZipEntry(zos, entry.filename(), entry.content().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
      }
    }
    progress(config, "Archive size: %.2f MiB".formatted(baos.size() / (1024.0 * 1024.0)));
    return baos.toByteArray();
  }

  private static void zipDirectory(Path root, Path dir, ZipOutputStream zos, DeployConfig config)
      throws IOException {
    try (var stream = Files.walk(dir)) {
      for (Path path : (Iterable<Path>) stream::iterator) {
        if (Files.isDirectory(path)) continue;
        String entryName = root.relativize(path).toString().replace('\\', '/');
        addZipEntry(zos, entryName, Files.readAllBytes(path));
      }
    }
  }

  private static void addZipEntry(ZipOutputStream zos, String name, byte[] data)
      throws IOException {
    ZipEntry entry = new ZipEntry(name);
    zos.putNextEntry(entry);
    zos.write(data);
    zos.closeEntry();
  }

  private static void recordDeployment(
      String projectId, String serviceId, String versionId, String url) {
    try {
      com.google.appengine.api.datastore.DatastoreService ds =
          com.google.appengine.api.datastore.DatastoreServiceFactory.getDatastoreService();
      com.google.appengine.api.datastore.Entity entity =
          new com.google.appengine.api.datastore.Entity("McpDeployment");
      entity.setProperty("projectId", projectId);
      entity.setProperty("serviceId", serviceId);
      entity.setProperty("versionId", versionId);
      entity.setProperty("url", url);
      entity.setProperty("timestamp", new java.util.Date());
      entity.setProperty("createdBy", DeploymentConstants.LABEL_CREATED_BY);
      ds.put(entity);
      log.debug("Deployment recorded in Datastore for {}/{}", projectId, serviceId);
    } catch (Exception e) {
      log.debug("Datastore deployment recording skipped: {}", e.getMessage());
    }
  }

  private static void validateConfig(DeployConfig config) throws IOException {
    if (config.projectId() == null || config.projectId().isBlank()) {
      throw new IOException("projectId is required");
    }
    if (config.serviceName() == null || config.serviceName().isBlank()) {
      throw new IOException("serviceName is required");
    }
    if (config.files() == null || config.files().isEmpty()) {
      throw new IOException("files list is required and must not be empty");
    }
  }

  private static void progress(DeployConfig config, String message) {
    log.info(message);
    if (config.progressCallback() != null) {
      config.progressCallback().accept(message);
    }
  }

  public record FileEntry(String path, String filename, String content) {
    public static FileEntry ofPath(String path) {
      return new FileEntry(path, null, null);
    }
    public static FileEntry ofContent(String filename, String content) {
      return new FileEntry(null, filename, content);
    }
  }

  public record DeployConfig(
      String projectId,
      String serviceName,
      String runtime,
      List<FileEntry> files,
      String accessToken,
      Consumer<String> progressCallback) {}
}
