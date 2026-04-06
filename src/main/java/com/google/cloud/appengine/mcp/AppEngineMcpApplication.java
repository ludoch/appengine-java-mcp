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

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Entry point for the Jakarta EE 10 MCP Server.
 * Initialises the MCP server and tools on startup.
 */
@WebListener
public class AppEngineMcpApplication implements ServletContextListener {

  public static void main(String[] args) throws Exception {
    loadDotEnv();

    boolean stdioMode = false;
    for (String arg : args) {
      if ("--stdio".equalsIgnoreCase(arg)) {
        stdioMode = true;
        break;
      }
    }

    if (stdioMode || shouldStartStdio()) {
      makeLoggingCompatibleWithStdio();
      StdioMcpServer.run();
    } else {
      System.err.println("This application is intended to run as a Servlet or in Stdio mode.");
      System.err.println("To start in Stdio mode, use the --stdio flag.");
      System.exit(1);
    }
  }

  @Override
  public void contextInitialized(ServletContextEvent sce) {
    loadDotEnv();
    
    McpConfig mcpConfig = new McpConfig();
    HttpServletStreamableServerTransportProvider transportProvider = mcpConfig.createTransportProvider();

    var builder = McpServer.sync(transportProvider)
        .serverInfo(Constants.SERVER_NAME, Constants.SERVER_VERSION)
        .capabilities(McpSchema.ServerCapabilities.builder()
            .tools(true)
            .build());
    
    mcpConfig.getToolSpecifications().forEach(builder::tools);
    McpSyncServer mcpSyncServer = builder.build();

    // Store in servlet context for the servlet to find (though the SDK handles registration via transportProvider)
    sce.getServletContext().setAttribute("mcpSyncServer", mcpSyncServer);
    sce.getServletContext().setAttribute("mcpTransportProvider", transportProvider);
    
    // Register the transport provider servlet manually if not in web.xml
    var registration = sce.getServletContext().addServlet("mcpServlet", transportProvider);
    if (registration != null) {
        registration.addMapping("/mcp/*");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
    }
  }

  private static void loadDotEnv() {
    try {
      Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
      dotenv.entries().forEach(e -> {
        if (System.getenv(e.getKey()) == null) {
          System.setProperty(e.getKey(), e.getValue());
        }
      });
    } catch (DotenvException ignored) {}
  }

  static boolean shouldStartStdio() {
    String gcpStdio = System.getenv("GCP_STDIO");
    if ("false".equalsIgnoreCase(gcpStdio)) return false;
    if (System.getenv("GOOGLE_CLOUD_PROJECT") != null) return false;
    try {
      var url = new URL("http://metadata.google.internal/computeMetadata/v1/project/project-id");
      var conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(500);
      conn.setReadTimeout(500);
      conn.setRequestProperty("Metadata-Flavor", "Google");
      int status = conn.getResponseCode();
      conn.disconnect();
      return status != 200;
    } catch (Exception e) {
      return true;
    }
  }

  private static void makeLoggingCompatibleWithStdio() {
    System.setOut(System.err);
  }
}
