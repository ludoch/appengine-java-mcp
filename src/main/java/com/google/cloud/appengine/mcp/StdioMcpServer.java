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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.appengine.mcp.tools.ToolRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stdio transport implementation for the MCP server.
 *
 * <p>Reads JSON-RPC 2.0 messages from stdin (newline-delimited) and writes responses to stdout.
 * Mirrors the StdioServerTransport used in the cloud-run-mcp Node.js server.
 *
 * <p>All logging must go to stderr; stdout is reserved for the MCP protocol stream.
 */
public final class StdioMcpServer {

  private static final Logger log = LoggerFactory.getLogger(StdioMcpServer.class);

  private StdioMcpServer() {}

  public static void run() throws Exception {
    log.info("App Engine MCP server starting in stdio transport mode");

    ObjectMapper objectMapper = new ObjectMapper();
    ToolRegistry toolRegistry = ToolRegistry.createDefault();

    // Stdout is the JSON-RPC transport; stderr is used for logs
    PrintStream out = System.out;
    BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    String line;
    while ((line = in.readLine()) != null) {
      line = line.trim();
      if (line.isEmpty()) continue;

      log.debug("stdin << {}", line);
      try {
        JsonNode req = objectMapper.readTree(line);
        String method = req.path("method").asText();
        JsonNode id = req.path("id");
        JsonNode params = req.path("params");

        // Dispatch using the same logic as the HTTP controller
        McpController controller = new McpController(objectMapper, toolRegistry);
        // Reflectively access the private processRequest via a dedicated method
        String response =
            processStdioRequest(controller, objectMapper, method, id, params);

        // Notifications (no id) produce no response
        if (id.isMissingNode() || id.isNull()) {
          if (method.startsWith("notifications/")) continue;
        }

        if (response != null && !response.isBlank()) {
          out.println(response);
          out.flush();
          log.debug("stdout >> {}", response);
        }

      } catch (Exception e) {
        log.error("Error processing stdin message", e);
        String errResp =
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error\"},\"id\":null}";
        out.println(errResp);
        out.flush();
      }
    }

    log.info("stdin closed – stdio MCP server shutting down");
  }

  /**
   * Process a single JSON-RPC request (extracted so tests can call it directly without going
   * through HTTP).
   */
  static String processStdioRequest(
      McpController controller,
      ObjectMapper mapper,
      String method,
      JsonNode id,
      JsonNode params)
      throws Exception {

    // Delegate to the controller's internal dispatcher via the public HTTP endpoint
    // by reconstructing a minimal JSON-RPC body.
    ObjectNode body = mapper.createObjectNode();
    body.put("jsonrpc", "2.0");
    body.put("method", method);
    if (!id.isMissingNode()) body.set("id", id);
    if (params != null && !params.isMissingNode()) body.set("params", params);

    var responseEntity =
        controller.handleMcp(
            mapper.writeValueAsString(body),
            null, // no Authorization header in stdio mode
            null  // no HttpServletRequest
        );

    return responseEntity.getBody();
  }
}
