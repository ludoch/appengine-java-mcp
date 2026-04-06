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
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.logging.v2.LoggingClient;
import com.google.cloud.logging.v2.LoggingSettings;
import com.google.logging.v2.ListLogEntriesRequest;
import com.google.logging.v2.LogEntry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cloud Logging operations for App Engine services, mirroring getServiceLogs() in run.js.
 */
public final class LoggingApiService {

  private static final Logger log = LoggerFactory.getLogger(LoggingApiService.class);
  private static final int PAGE_SIZE = 100;

  private LoggingApiService() {}

  /**
   * Fetches log entries for an App Engine service.
   *
   * @param projectId   GCP project ID
   * @param serviceId   App Engine service name (e.g. "default")
   * @param accessToken OAuth token or {@link com.google.cloud.appengine.mcp.Constants#GCLOUD_AUTH}
   * @return Formatted log lines, most-recent first
   */
  public static String getServiceLogs(
      String projectId, String serviceId, String accessToken) throws IOException {
    log.debug("Fetching logs for App Engine service {}/{}", projectId, serviceId);

    GoogleCredentials creds = AuthService.getCredentials(accessToken);
    LoggingSettings settings = LoggingSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(creds))
        .build();

    String filter = """
        resource.type="gae_app"
        resource.labels.module_id="%s"
        resource.labels.project_id="%s"
        """.formatted(serviceId, projectId);

    ListLogEntriesRequest request = ListLogEntriesRequest.newBuilder()
        .addResourceNames("projects/" + projectId)
        .setFilter(filter)
        .setOrderBy("timestamp desc")
        .setPageSize(PAGE_SIZE)
        .build();

    List<String> lines = new ArrayList<>();
    try (LoggingClient client = LoggingClient.create(settings)) {
      for (LogEntry entry : client.listLogEntries(request).iterateAll()) {
        lines.add(formatEntry(entry));
      }
    }
    return String.join("\n", lines);
  }

  // ---------------------------------------------------------------------------

  private static String formatEntry(LogEntry entry) {
    String timestamp = entry.hasTimestamp() ? entry.getTimestamp().toString() : "N/A";
    String severity = entry.getSeverity().name();

    String data = switch (entry.getPayloadCase()) {
      case TEXT_PAYLOAD -> entry.getTextPayload();
      case JSON_PAYLOAD -> entry.getJsonPayload().toString();
      case PROTO_PAYLOAD -> entry.getProtoPayload().toString();
      default -> "";
    };

    String httpInfo = "";
    if (entry.hasHttpRequest()) {
      var req = entry.getHttpRequest();
      httpInfo = "HTTP %s %d %s".formatted(
          req.getRequestMethod(), req.getStatus(), req.getRequestUrl());
    }

    return "[%s] [%s] %s %s".formatted(timestamp, severity, httpInfo, data).trim();
  }
}
