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

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GCE/App Engine metadata server helper, mirroring lib/cloud-api/metadata.js.
 *
 * <p>On App Engine (and other GCP services) the metadata server is available at
 * {@code http://metadata.google.internal}.  This service probes it to determine whether the app
 * is running on GCP, and reads the project ID and region when available.
 */
public final class MetadataService {

  private static final Logger log = LoggerFactory.getLogger(MetadataService.class);
  private static final String METADATA_BASE =
      "http://metadata.google.internal/computeMetadata/v1/";
  private static final int TIMEOUT_MS = 1_000;

  private MetadataService() {}

  public record GcpInfo(String project, String region) {}

  /**
   * Queries the metadata server and returns a {@link GcpInfo} when running on GCP, or {@code null}
   * when not on GCP.
   */
  public static GcpInfo checkGCP() {
    try {
      String project = fetchMetadata("project/project-id");
      if (project == null) return null;

      String fullZone = fetchMetadata("instance/zone"); // e.g. "projects/123/zones/us-central1-a"
      String region = null;
      if (fullZone != null) {
        // strip trailing zone letter: "us-central1-a" → "us-central1"
        String zone = fullZone.substring(fullZone.lastIndexOf('/') + 1);
        int lastDash = zone.lastIndexOf('-');
        region = lastDash > 0 ? zone.substring(0, lastDash) : zone;
      }
      log.info("Running on GCP: project={}, region={}", project, region);
      return new GcpInfo(project, region);

    } catch (Exception e) {
      log.debug("Metadata server not reachable – assuming local environment: {}", e.getMessage());
      return null;
    }
  }

  // ---------------------------------------------------------------------------

  private static String fetchMetadata(String path) {
    try {
      URL url = new URL(METADATA_BASE + path);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(TIMEOUT_MS);
      conn.setReadTimeout(TIMEOUT_MS);
      conn.setRequestProperty("Metadata-Flavor", "Google");
      if (conn.getResponseCode() != 200) return null;
      byte[] bytes = conn.getInputStream().readAllBytes();
      conn.disconnect();
      return new String(bytes, StandardCharsets.UTF_8).trim();
    } catch (Exception e) {
      return null;
    }
  }
}
