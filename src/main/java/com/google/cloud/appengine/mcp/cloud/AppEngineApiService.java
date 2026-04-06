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
package com.google.cloud.appengine.mcp.cloud;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.appengine.v1.Service;
import com.google.appengine.v1.ServicesClient;
import com.google.appengine.v1.ServicesSettings;
import com.google.appengine.v1.Version;
import com.google.appengine.v1.VersionsClient;
import com.google.appengine.v1.VersionsSettings;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * App Engine Admin API operations, mirroring lib/cloud-api/run.js for App Engine services.
 *
 * <p>Uses the {@code google-cloud-appengine-admin} client library. Results are cached in App
 * Engine Memcache (via the legacy API) to reduce API quota usage.
 */
public final class AppEngineApiService {

  private static final Logger log = LoggerFactory.getLogger(AppEngineApiService.class);
  private static final int CACHE_TTL_SECONDS;

  static {
    int ttl = 120;
    try {
      ttl = Integer.parseInt(System.getenv("CACHE_TTL_SECONDS"));
    } catch (Exception ignored) {}
    CACHE_TTL_SECONDS = ttl;
  }

  private AppEngineApiService() {}

  // ---------------------------------------------------------------------------
  // Services
  // ---------------------------------------------------------------------------

  /**
   * Lists all App Engine services in the given project.
   * Mirrors listServices() from run.js.
   */
  public static List<ServiceInfo> listServices(String projectId, String accessToken)
      throws IOException {
    String cacheKey = "ae_services_" + projectId;
    List<ServiceInfo> cached = getFromMemcache(cacheKey);
    if (cached != null) {
      log.debug("Returning service list from Memcache for project {}", projectId);
      return cached;
    }

    log.debug("Listing App Engine services for project {}", projectId);
    GoogleCredentials creds = AuthService.getCredentials(accessToken);
    ServicesSettings settings = ServicesSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(creds))
        .build();

    List<ServiceInfo> services = new ArrayList<>();
    try (ServicesClient client = ServicesClient.create(settings)) {
      String parent = "apps/" + projectId;
      for (Service service : client.listServices(parent).iterateAll()) {
        services.add(ServiceInfo.fromProto(service, projectId));
      }
    }

    putToMemcache(cacheKey, services, CACHE_TTL_SECONDS);
    return services;
  }

  /**
   * Gets details for a specific App Engine service.
   * Mirrors getService() from run.js.
   */
  public static ServiceInfo getService(
      String projectId, String serviceId, String accessToken) throws IOException {
    String cacheKey = "ae_service_" + projectId + "_" + serviceId;
    ServiceInfo cached = getFromMemcache(cacheKey);
    if (cached != null) return cached;

    log.debug("Getting App Engine service {}/{}", projectId, serviceId);
    GoogleCredentials creds = AuthService.getCredentials(accessToken);
    ServicesSettings settings = ServicesSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(creds))
        .build();

    try (ServicesClient client = ServicesClient.create(settings)) {
      String name = "apps/" + projectId + "/services/" + serviceId;
      Service service = client.getService(name);
      ServiceInfo info = ServiceInfo.fromProto(service, projectId);
      putToMemcache(cacheKey, info, CACHE_TTL_SECONDS);
      return info;
    }
  }

  // ---------------------------------------------------------------------------
  // Versions
  // ---------------------------------------------------------------------------

  /**
   * Lists versions of an App Engine service.
   */
  public static List<VersionInfo> listVersions(
      String projectId, String serviceId, String accessToken) throws IOException {
    log.debug("Listing versions for {}/{}", projectId, serviceId);
    GoogleCredentials creds = AuthService.getCredentials(accessToken);
    VersionsSettings settings = VersionsSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(creds))
        .build();

    List<VersionInfo> versions = new ArrayList<>();
    try (VersionsClient client = VersionsClient.create(settings)) {
      String parent = "apps/" + projectId + "/services/" + serviceId;
      for (Version v : client.listVersions(parent).iterateAll()) {
        versions.add(VersionInfo.fromProto(v));
      }
    }
    return versions;
  }

  /**
   * Gets a specific version of an App Engine service.
   */
  public static VersionInfo getVersion(
      String projectId, String serviceId, String versionId, String accessToken)
      throws IOException {
    log.debug("Getting version {}/{}/{}", projectId, serviceId, versionId);
    GoogleCredentials creds = AuthService.getCredentials(accessToken);
    VersionsSettings settings = VersionsSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(creds))
        .build();

    try (VersionsClient client = VersionsClient.create(settings)) {
      String name = "apps/" + projectId + "/services/" + serviceId + "/versions/" + versionId;
      Version v = client.getVersion(name);
      return VersionInfo.fromProto(v);
    }
  }

  // ---------------------------------------------------------------------------
  // Utility: service URL
  // ---------------------------------------------------------------------------

  public static String buildServiceUrl(String projectId, String serviceId) {
    if ("default".equals(serviceId)) {
      return "https://" + projectId + ".appspot.com";
    }
    return "https://" + serviceId + "-dot-" + projectId + ".appspot.com";
  }

  // ---------------------------------------------------------------------------
  // Value objects
  // ---------------------------------------------------------------------------

  public record ServiceInfo(
      String name,
      String serviceId,
      String projectId,
      String url,
      String trafficSplit) {

    static ServiceInfo fromProto(Service s, String projectId) {
      String serviceId = s.getName().substring(s.getName().lastIndexOf('/') + 1);
      String url = buildServiceUrl(projectId, serviceId);
      // Traffic split: format as "version->weight" pairs
      var sb = new StringBuilder();
      s.getSplit().getAllocationsMap().forEach((v, w) -> {
        if (!sb.isEmpty()) sb.append(", ");
        sb.append(v).append("->").append(String.format("%.0f%%", w * 100));
      });
      return new ServiceInfo(s.getName(), serviceId, projectId, url, sb.toString());
    }
  }

  public record VersionInfo(
      String versionId,
      String runtime,
      String env,
      String servingStatus,
      String createTime,
      String createdBy,
      long diskUsageBytes) {

    static VersionInfo fromProto(Version v) {
      String versionId = v.getName().substring(v.getName().lastIndexOf('/') + 1);
      String createTime = v.hasCreateTime() ? v.getCreateTime().toString() : "N/A";
      return new VersionInfo(
          versionId,
          v.getRuntime(),
          v.getEnv(),
          v.getServingStatus().name(),
          createTime,
          v.getCreatedBy(),
          v.getDiskUsageBytes());
    }
  }

  // ---------------------------------------------------------------------------
  // App Engine Memcache helpers
  // ---------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static <T> T getFromMemcache(String key) {
    if (CACHE_TTL_SECONDS <= 0) return null;
    try {
      com.google.appengine.api.memcache.MemcacheService memcache =
          com.google.appengine.api.memcache.MemcacheServiceFactory.getMemcacheService();
      return (T) memcache.get(key);
    } catch (Exception e) {
      return null;
    }
  }

  private static void putToMemcache(String key, Object value, int ttlSeconds) {
    if (CACHE_TTL_SECONDS <= 0) return;
    try {
      com.google.appengine.api.memcache.MemcacheService memcache =
          com.google.appengine.api.memcache.MemcacheServiceFactory.getMemcacheService();
      memcache.put(key, value,
          com.google.appengine.api.memcache.Expiration.byDeltaSeconds(ttlSeconds));
    } catch (Exception ignored) {}
  }
}
