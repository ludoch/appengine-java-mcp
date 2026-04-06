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
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import com.google.cloud.appengine.mcp.tools.AppEngineMcpTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Stdio transport implementation for the MCP server using the official Java SDK.
 */
public final class StdioMcpServer {

  private static final Logger log = LoggerFactory.getLogger(StdioMcpServer.class);

  private StdioMcpServer() {}

  public static void run() throws Exception {
    log.info("App Engine MCP server starting in stdio transport mode");

    // Initialize Spring context to get beans
    try (ConfigurableApplicationContext context = SpringApplication.run(AppEngineMcpApplication.class, "--stdio")) {
      AppEngineMcpTools tools = context.getBean(AppEngineMcpTools.class);
      McpConfig mcpConfig = context.getBean(McpConfig.class);
      
      // Setup Stdio transport
      var transportProvider = new StdioServerTransportProvider(McpJsonDefaults.getMapper());

      // Initialize the Sync Server
      var builder = McpServer.sync(transportProvider)
          .serverInfo(Constants.SERVER_NAME, Constants.SERVER_VERSION)
          .capabilities(McpSchema.ServerCapabilities.builder()
              .tools(true)
              .build());
      
      mcpConfig.getToolSpecifications(tools).forEach(builder::tools);
      
      builder.build();

      log.info("Stdio transport active...");
      // For Stdio, the sync server usually starts the session loop in the background or blocks.
      // Since it's a SingleSessionSyncSpecification, it might be waiting for the connection.
      
      // We need to keep the process alive while the transport is running.
      // Based on typical SDK patterns, the session handles its own lifecycle.
      // However, we might need a way to wait for it.
      // For now, we'll wait for System.in to close.
      while (System.in.read() != -1) {
          // Just wait
      }
    }
    
    log.info("Stdio MCP server shutting down");
  }
}
