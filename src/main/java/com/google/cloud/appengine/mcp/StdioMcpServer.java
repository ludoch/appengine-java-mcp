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
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stdio transport implementation for the MCP server using the official Java SDK.
 */
public final class StdioMcpServer {

  private static final Logger log = LoggerFactory.getLogger(StdioMcpServer.class);

  private StdioMcpServer() {}

  public static void run() throws Exception {
    log.info("App Engine MCP server starting in stdio transport mode");

    McpConfig mcpConfig = new McpConfig();
    
    // Setup Stdio transport
    var transportProvider = new StdioServerTransportProvider(McpJsonDefaults.getMapper());

    // Initialize the Sync Server
    var builder = McpServer.sync(transportProvider)
        .serverInfo(Constants.SERVER_NAME, Constants.SERVER_VERSION)
        .capabilities(McpSchema.ServerCapabilities.builder()
            .tools(true)
            .build());
    
    mcpConfig.getToolSpecifications().forEach(builder::tools);
    
    builder.build();

    log.info("Stdio transport active...");
    
    // Keep the process alive while the transport is running.
    while (System.in.read() != -1) {
        // Just wait
    }
    
    log.info("Stdio MCP server shutting down");
  }
}
