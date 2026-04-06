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
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for managing GCP authentication credentials.
 */
public final class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  private AuthService() {}

  /**
   * Returns GoogleCredentials. If an access token is provided, uses it to create
   * OAuth2Credentials. Otherwise, falls back to Application Default Credentials (ADC).
   */
  public static GoogleCredentials getCredentials(String accessToken) throws IOException {
    if (accessToken != null && !accessToken.isBlank() && !"gcloud_auth".equals(accessToken)) {
      log.debug("Using provided access token for credentials");
      return buildOAuthCredentials(accessToken);
    }

    log.debug("Falling back to Application Default Credentials (ADC)");
    GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
    
    // Scopes are generally needed when using ADC to call GCP APIs
    if (credentials.createScopedRequired()) {
      credentials = credentials.createScoped(
          "https://www.googleapis.com/auth/cloud-platform"
      );
    }
    return credentials;
  }

  private static GoogleCredentials buildOAuthCredentials(String token) {
    AccessToken accessToken = new AccessToken(token, null /* no expiry known */);
    return (GoogleCredentials) OAuth2Credentials.create(accessToken);
  }
}
