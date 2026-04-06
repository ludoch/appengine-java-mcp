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
import com.google.appengine.v1.GetServiceRequest;
import com.google.appengine.v1.GetVersionRequest;
import com.google.appengine.v1.ListServicesRequest;
import com.google.appengine.v1.ListVersionsRequest;
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
 * Service for interacting with the Google App Engine Admin API.
 */
public final class AppEngineApiService {

  private static final Logger log = LoggerFactory.getLogger(AppEngineApiService.class);

  private AppEngineApiService() {}

  /**
   * Lists all services in the given project.
   */
  public static List<ServiceInfo> listServices(String projectId, String accessToken)
      throws IOException {
    log.debug("Listing App Engine services for project: {}", projectId);
    GoogleCredentials creds = AuthService.getCredentials(accessToken);
    ServicesSettings settings = ServicesSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(creds))
        .build();

    List<ServiceInfo> results = new ArrayList<>();
    try (ServicesClient client = ServicesClient.create(settings)) {
      String parent = "apps/" + projectId;
      ListServicesRequest request = ListServicesRequest.newBuilder()
          .setParent(parent)
          .build();
      for (Service service : client.listServices(request).iterateAll()) {
        results.add(new ServiceInfo(
            service.getId(),
            buildServiceUrl(projectId, service.getId()),
            extractTrafficSplit(service)
        ));
      }
    }
    return results;
  }

  /**
   * Gets details for a single service.
   */
  public static ServiceInfo getService(String projectId, String serviceId, String accessToken)
      throws IOException {
    log.debug("Getting App Engine service: {}/{}", projectId, serviceId);
    GoogleCredentials creds = AuthService.getCredentials(accessToken);
    ServicesSettings settings = ServicesSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(creds))
        .build();

    try (ServicesClient client = ServicesClient.create(settings)) {
      String name = "apps/" + projectId + "/services/" + serviceId;
      GetServiceRequest request = GetServiceRequest.newBuilder()
          .setName(name)
          .build();
      Service service = client.getService(request);
      return new ServiceInfo(
          service.getId(),
          buildServiceUrl(projectId, service.getId()),
          extractTrafficSplit(service)
      );
    }
  }

  /**
   * Lists all versions for a service.
   */
  public static List<VersionInfo> listVersions(
      String projectId, String serviceId, String accessToken) throws IOException {
    log.debug("Listing versions for {}/{}", projectId, serviceId);
    GoogleCredentials creds = AuthService.getCredentials(accessToken);
    VersionsSettings settings = VersionsSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(creds))
        .build();

    List<VersionInfo> results = new ArrayList<>();
    try (VersionsClient client = VersionsClient.create(settings)) {
      String parent = "apps/" + projectId + "/services/" + serviceId;
      ListVersionsRequest request = ListVersionsRequest.newBuilder()
          .setParent(parent)
          .build();
      for (Version v : client.listVersions(request).iterateAll()) {
        results.add(mapVersion(v));
      }
    }
    return results;
  }

  /**
   * Gets details for a specific version.
   */
  public static VersionInfo getVersion(
      String projectId, String serviceId, String versionId, String accessToken) throws IOException {
    log.debug("Getting version {} for {}/{}", versionId, projectId, serviceId);
    GoogleCredentials creds = AuthService.getCredentials(accessToken);
    VersionsSettings settings = VersionsSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(creds))
        .build();

    try (VersionsClient client = VersionsClient.create(settings)) {
      String name = "apps/" + projectId + "/services/" + serviceId + "/versions/" + versionId;
      GetVersionRequest request = GetVersionRequest.newBuilder()
          .setName(name)
          .build();
      Version v = client.getVersion(request);
      return mapVersion(v);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  public static String buildServiceUrl(String projectId, String serviceId) {
    if ("default".equals(serviceId)) {
      return "https://" + projectId + ".appspot.com";
    }
    return "https://" + serviceId + "-dot-" + projectId + ".appspot.com";
  }

  private static String extractTrafficSplit(Service service) {
    if (service.hasSplit()) {
      return service.getSplit().getAllocationsMap().toString();
    }
    return "100% (default)";
  }

  private static VersionInfo mapVersion(Version v) {
    return new VersionInfo(
        v.getId(),
        v.getRuntime(),
        v.getEnv(),
        v.getServingStatus().name(),
        v.getCreateTime() != null ? v.getCreateTime().toString() : "unknown",
        v.getCreatedBy(),
        v.getDiskUsageBytes()
    );
  }

  public record ServiceInfo(String serviceId, String url, String trafficSplit) {}

  public record VersionInfo(
      String versionId,
      String runtime,
      String env,
      String servingStatus,
      String createTime,
      String createdBy,
      long diskUsageBytes) {}
}
