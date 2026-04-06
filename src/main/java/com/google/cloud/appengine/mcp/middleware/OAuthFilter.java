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
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet filter that intercepts MCP requests to handle Bearer token authentication.
 */
@WebFilter(urlPatterns = {"/mcp", "/mcp/*"})
public class OAuthFilter implements Filter {

  private static final Logger log = LoggerFactory.getLogger(OAuthFilter.class);
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    
    if (!(request instanceof HttpServletRequest httpRequest)) {
      chain.doFilter(request, response);
      return;
    }

    String authHeader = httpRequest.getHeader("Authorization");
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      String token = authHeader.substring(7).trim();
      log.debug("Bearer token found in request");
      
      // In a real implementation, you might verify the token here
      // and attach user info to the request context.
      // For this MCP server, the tools will use the token via AuthService.
    }

    chain.doFilter(request, response);
  }

  /**
   * Optional: Verify a Google ID Token.
   * Not currently used in the main flow but available for enhanced security.
   */
  private boolean verifyGoogleIdToken(String idTokenString) {
    String clientId = System.getenv("OAUTH_CLIENT_ID");
    if (clientId == null || clientId.isBlank()) return true;

    GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
        new NetHttpTransport(), new GsonFactory())
        .setAudience(Collections.singletonList(clientId))
        .build();

    try {
      GoogleIdToken idToken = verifier.verify(idTokenString);
      return idToken != null;
    } catch (Exception e) {
      log.warn("ID Token verification failed: {}", e.getMessage());
      return false;
    }
  }

  private void sendError(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    Map<String, Object> error = Map.of(
        "jsonrpc", "2.0",
        "error", Map.of("code", -32000, "message", message)
    );
    response.getWriter().write(objectMapper.writeValueAsString(error));
  }
}
