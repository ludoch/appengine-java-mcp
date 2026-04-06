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

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.OAuth2Credentials;
import com.google.auth.oauth2.UserCredentials;
import com.google.cloud.appengine.mcp.Constants;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authentication helper, mirroring lib/cloud-api/auth.js.
 *
 * <p>Supports two modes:
 * <ol>
 *   <li>Application Default Credentials (ADC) – used when {@code accessToken} equals
 *       {@link Constants#GCLOUD_AUTH}.
 *   <li>OAuth2 access token passed from the MCP client's {@code Authorization} header.
 * </ol>
 */
public final class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  private static final List<String> SCOPES =
      List.of(Constants.SCOPE_CLOUD_PLATFORM, Constants.SCOPE_EMAIL);

  private AuthService() {}

  /**
   * Returns {@link GoogleCredentials} appropriate for the given access-token value.
   *
   * @param accessToken Either {@link Constants#GCLOUD_AUTH} (use ADC) or an OAuth2 Bearer token.
   */
  public static GoogleCredentials getCredentials(String accessToken) throws IOException {
    if (accessToken == null || Constants.GCLOUD_AUTH.equals(accessToken)) {
      return getApplicationDefaultCredentials();
    }
    return buildOAuthCredentials(accessToken);
  }

  /**
   * Returns {@code true} when Application Default Credentials are configured and functional.
   * Mirrors ensureGCPCredentials() in auth.js.
   */
  public static boolean ensureGCPCredentials() {
    log.debug("Checking for Google Cloud Application Default Credentials…");
    try {
      GoogleCredentials creds = getApplicationDefaultCredentials();
      creds.refreshIfExpired();
      log.info("Application Default Credentials found and valid.");
      return true;
    } catch (IOException e) {
      log.error(
          "Google Cloud Application Default Credentials are not configured. "
              + "Run: gcloud auth application-default login\n"
              + "Original error: {}",
          e.getMessage());
      return false;
    }
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private static GoogleCredentials getApplicationDefaultCredentials() throws IOException {
    GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
    if (credentials.createScopedRequired()) {
      credentials = credentials.createScoped(SCOPES);
    }
    return credentials;
  }

  private static GoogleCredentials buildOAuthCredentials(String token) {
    AccessToken accessToken = new AccessToken(token, null /* no expiry known */);
    return OAuth2Credentials.create(accessToken);
  }
}
