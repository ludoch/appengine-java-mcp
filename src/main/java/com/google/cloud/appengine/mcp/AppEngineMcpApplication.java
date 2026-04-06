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

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import com.google.cloud.appengine.mcp.tools.AppEngineMcpTools;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Bean;

/**
 * Main entry point for the App Engine Java MCP Server.
 */
@SpringBootApplication
public class AppEngineMcpApplication extends SpringBootServletInitializer {

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
      SpringApplication.run(AppEngineMcpApplication.class, args);
    }
  }

  @Bean
  public HttpServletStreamableServerTransportProvider transportProvider() {
    return HttpServletStreamableServerTransportProvider.builder()
        .mcpEndpoint("/mcp")
        .jsonMapper(McpJsonDefaults.getMapper())
        .build();
  }

  @Bean
  public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
      HttpServletStreamableServerTransportProvider transportProvider) {
    return new ServletRegistrationBean<>(transportProvider, "/mcp/*");
  }

  @Bean
  public McpSyncServer mcpSyncServer(
      HttpServletStreamableServerTransportProvider transportProvider,
      AppEngineMcpTools tools,
      McpConfig mcpConfig) {

    var builder = McpServer.sync(transportProvider)
        .serverInfo(Constants.SERVER_NAME, Constants.SERVER_VERSION)
        .capabilities(McpSchema.ServerCapabilities.builder()
            .tools(true)
            .build());
    
    mcpConfig.getToolSpecifications(tools).forEach(builder::tools);
    
    return builder.build();
  }

  @Override
  protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
    loadDotEnv();
    return builder.sources(AppEngineMcpApplication.class);
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
      var url = new java.net.URL("http://metadata.google.internal/computeMetadata/v1/project/project-id");
      var conn = (java.net.HttpURLConnection) url.openConnection();
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
