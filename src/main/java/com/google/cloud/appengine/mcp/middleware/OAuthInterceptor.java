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
package com.google.cloud.appengine.mcp.middleware;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * OAuth token validation interceptor, mirroring lib/middleware/oauth.js.
 *
 * <p>When {@code OAUTH_ENABLED=true} and an {@code Authorization: Bearer <token>} header is
 * present on {@code POST /mcp} or {@code POST /messages}, the token is verified against Google's
 * token-info endpoint.  Requests to non-tool paths (e.g. {@code /.well-known/...}) are allowed
 * through without checking.
 */
public class OAuthInterceptor implements HandlerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(OAuthInterceptor.class);

  private final ObjectMapper objectMapper;

  public OAuthInterceptor(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean preHandle(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler)
      throws Exception {

    boolean oauthEnabled = "true".equalsIgnoreCase(getEnv("OAUTH_ENABLED", "false"));
    if (!oauthEnabled) {
      return true; // OAuth not configured – let every request through
    }

    String path = request.getRequestURI();

    // Only enforce OAuth on the MCP endpoints
    if (!path.startsWith("/mcp") && !path.startsWith("/messages") && !path.startsWith("/sse")) {
      return true;
    }

    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      return sendUnauthorized(response, "Missing or invalid Authorization header");
    }

    String token = authHeader.substring(7).trim();
    if (!verifyToken(token)) {
      return sendUnauthorized(response, "Invalid or expired access token");
    }

    return true;
  }

  private boolean verifyToken(String token) {
    try {
      String audience = getEnv("GOOGLE_OAUTH_AUDIENCE", null);

      // Use Google's token info endpoint for simple verification
      var verifier = new GoogleIdTokenVerifier.Builder(
              new NetHttpTransport(), GsonFactory.getDefaultInstance())
          .setAudience(audience != null ? Collections.singletonList(audience) : Collections.emptyList())
          .build();

      // Try ID token verification first
      var idToken = verifier.verify(token);
      if (idToken != null) {
        return true;
      }

      // Fall back to access token info endpoint
      var url = new java.net.URL(
          "https://oauth2.googleapis.com/tokeninfo?access_token=" + token);
      var conn = (java.net.HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(3000);
      conn.setReadTimeout(3000);
      int status = conn.getResponseCode();
      conn.disconnect();
      return status == 200;

    } catch (Exception e) {
      log.warn("Token verification failed: {}", e.getMessage());
      return false;
    }
  }

  private boolean sendUnauthorized(HttpServletResponse response, String message)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    String body = objectMapper.writeValueAsString(java.util.Map.of(
        "jsonrpc", "2.0",
        "error", java.util.Map.of("code", -32001, "message", message),
        "id", (Object) null));
    response.getWriter().write(body);
    return false;
  }

  private static String getEnv(String key, String defaultValue) {
    String val = System.getenv(key);
    return val != null ? val : System.getProperty(key, defaultValue);
  }
}
