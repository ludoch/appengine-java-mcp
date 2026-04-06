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
package com.google.cloud.appengine.mcp;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Main entry point for the App Engine Java MCP Server.
 *
 * <p>Supports two runtime modes, mirroring the cloud-run-mcp Node.js server:
 *
 * <ol>
 *   <li><b>Stdio mode</b> – used locally when no GCP metadata server is detected. Start with
 *       {@code java -jar appengine-java-mcp.jar --stdio}
 *   <li><b>HTTP/SSE mode</b> – used on App Engine (and locally with {@code mvn appengine:run}).
 *       Exposes {@code POST /mcp}, {@code GET /sse}, and {@code POST /messages}.
 * </ol>
 *
 * <p>The class extends {@link SpringBootServletInitializer} so that the WAR can be deployed to the
 * App Engine standard Jetty runtime without a separate {@code main()} entry point.
 */
@SpringBootApplication
public class AppEngineMcpApplication extends SpringBootServletInitializer {

  public static void main(String[] args) throws Exception {
    // Load .env file for local development (quiet – ignore missing file)
    loadDotEnv();

    // Check for explicit stdio flag
    boolean stdioMode = false;
    for (String arg : args) {
      if ("--stdio".equalsIgnoreCase(arg)) {
        stdioMode = true;
        break;
      }
    }

    if (stdioMode || shouldStartStdio()) {
      // Redirect stdout to stderr so MCP stdio transport is not polluted
      makeLoggingCompatibleWithStdio();
      StdioMcpServer.run();
    } else {
      SpringApplication.run(AppEngineMcpApplication.class, args);
    }
  }

  /**
   * Called by the servlet container (Jetty on App Engine) to initialise the Spring context when
   * running as a WAR.
   */
  @Override
  protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
    loadDotEnv();
    return builder.sources(AppEngineMcpApplication.class);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static void loadDotEnv() {
    try {
      Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
      dotenv.entries().forEach(e -> {
        // Only set if not already present in the environment
        if (System.getenv(e.getKey()) == null) {
          System.setProperty(e.getKey(), e.getValue());
        }
      });
    } catch (DotenvException ignored) {
      // No .env file – that's fine
    }
  }

  /**
   * Returns {@code true} when stdio mode should be used.
   *
   * <p>Stdio is preferred when {@code GCP_STDIO} is not explicitly {@code "false"} AND the app is
   * not running on GCP (detected by the absence of the metadata server).
   */
  static boolean shouldStartStdio() {
    String gcpStdio = System.getenv("GCP_STDIO");
    if ("false".equalsIgnoreCase(gcpStdio)) {
      return false;
    }
    // If GOOGLE_CLOUD_PROJECT is set via env (typical on App Engine), stay in HTTP mode
    if (System.getenv("GOOGLE_CLOUD_PROJECT") != null) {
      return false;
    }
    // Try a quick non-blocking check for the GCP metadata server
    try {
      var url = new java.net.URL("http://metadata.google.internal/computeMetadata/v1/project/project-id");
      var conn = (java.net.HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(500);
      conn.setReadTimeout(500);
      conn.setRequestProperty("Metadata-Flavor", "Google");
      int status = conn.getResponseCode();
      conn.disconnect();
      return status != 200; // On GCP → HTTP mode; off GCP → stdio
    } catch (Exception e) {
      return true; // Not on GCP → stdio mode
    }
  }

  private static void makeLoggingCompatibleWithStdio() {
    // Redirect System.out to System.err so that log output does not corrupt the
    // JSON-RPC stream that MCP clients read from stdout.
    System.setOut(System.err);
  }
}
