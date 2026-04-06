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
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import com.google.cloud.appengine.mcp.tools.AppEngineMcpTools;
import java.util.List;
import java.util.Map;

/**
 * Manager for MCP tools and server configuration, without Spring dependencies.
 */
public class McpConfig {

  private final AppEngineMcpTools tools;

  public McpConfig() {
    this.tools = new AppEngineMcpTools();
  }

  public AppEngineMcpTools getTools() {
    return tools;
  }

  public HttpServletStreamableServerTransportProvider createTransportProvider() {
    return HttpServletStreamableServerTransportProvider.builder()
        .mcpEndpoint("/mcp")
        .jsonMapper(McpJsonDefaults.getMapper())
        .build();
  }

  public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
    return List.of(
        listProjectsTool(),
        createProjectTool(),
        listServicesTool(),
        getServiceTool(),
        listVersionsTool(),
        getVersionTool(),
        getServiceLogTool(),
        deployLocalFolderTool(),
        deployFileContentsTool()
    );
  }

  private McpServerFeatures.SyncToolSpecification listProjectsTool() {
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(McpSchema.Tool.builder()
            .name("list_projects")
            .description("Lists available GCP projects.")
            .inputSchema(new McpSchema.JsonSchema("object", null, null, null, null, null))
            .build())
        .callHandler((exchange, request) -> {
          try {
            return tools.list_projects(extractToken(exchange));
          } catch (Exception e) {
            return error(e);
          }
        })
        .build();
  }

  private McpServerFeatures.SyncToolSpecification createProjectTool() {
    var schema = new McpSchema.JsonSchema("object", 
        Map.of("projectId", Map.of("type", "string", "description", "Optional GCP project ID.")),
        null, null, null, null);
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(McpSchema.Tool.builder()
            .name("create_project")
            .description("Creates a GCP project.")
            .inputSchema(schema)
            .build())
        .callHandler((exchange, request) -> {
          try {
            return tools.create_project(request.arguments(), extractToken(exchange));
          } catch (Exception e) {
            return error(e);
          }
        })
        .build();
  }

  private McpServerFeatures.SyncToolSpecification listServicesTool() {
    var schema = new McpSchema.JsonSchema("object", 
        Map.of("project", Map.of("type", "string", "description", "GCP project ID.")),
        List.of("project"), null, null, null);
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(McpSchema.Tool.builder()
            .name("list_services")
            .description("Lists App Engine services.")
            .inputSchema(schema)
            .build())
        .callHandler((exchange, request) -> {
          try {
            return tools.list_services(request.arguments(), extractToken(exchange));
          } catch (Exception e) {
            return error(e);
          }
        })
        .build();
  }

  private McpServerFeatures.SyncToolSpecification getServiceTool() {
    var schema = new McpSchema.JsonSchema("object", 
        Map.of(
            "project", Map.of("type", "string", "description", "GCP project ID."),
            "service", Map.of("type", "string", "description", "Service name.")
        ),
        List.of("project", "service"), null, null, null);
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(McpSchema.Tool.builder()
            .name("get_service")
            .description("Gets service details.")
            .inputSchema(schema)
            .build())
        .callHandler((exchange, request) -> {
          try {
            return tools.get_service(request.arguments(), extractToken(exchange));
          } catch (Exception e) {
            return error(e);
          }
        })
        .build();
  }

  private McpServerFeatures.SyncToolSpecification listVersionsTool() {
    var schema = new McpSchema.JsonSchema("object", 
        Map.of(
            "project", Map.of("type", "string", "description", "GCP project ID."),
            "service", Map.of("type", "string", "description", "Service name.")
        ),
        List.of("project", "service"), null, null, null);
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(McpSchema.Tool.builder()
            .name("list_versions")
            .description("Lists service versions.")
            .inputSchema(schema)
            .build())
        .callHandler((exchange, request) -> {
          try {
            return tools.list_versions(request.arguments(), extractToken(exchange));
          } catch (Exception e) {
            return error(e);
          }
        })
        .build();
  }

  private McpServerFeatures.SyncToolSpecification getVersionTool() {
    var schema = new McpSchema.JsonSchema("object", 
        Map.of(
            "project", Map.of("type", "string", "description", "GCP project ID."),
            "service", Map.of("type", "string", "description", "Service name."),
            "version", Map.of("type", "string", "description", "Version ID.")
        ),
        List.of("project", "service", "version"), null, null, null);
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(McpSchema.Tool.builder()
            .name("get_version")
            .description("Gets version details.")
            .inputSchema(schema)
            .build())
        .callHandler((exchange, request) -> {
          try {
            return tools.get_version(request.arguments(), extractToken(exchange));
          } catch (Exception e) {
            return error(e);
          }
        })
        .build();
  }

  private McpServerFeatures.SyncToolSpecification getServiceLogTool() {
    var schema = new McpSchema.JsonSchema("object", 
        Map.of(
            "project", Map.of("type", "string", "description", "GCP project ID."),
            "service", Map.of("type", "string", "description", "Service name.")
        ),
        List.of("project", "service"), null, null, null);
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(McpSchema.Tool.builder()
            .name("get_service_log")
            .description("Gets service logs.")
            .inputSchema(schema)
            .build())
        .callHandler((exchange, request) -> {
          try {
            return tools.get_service_log(request.arguments(), extractToken(exchange));
          } catch (Exception e) {
            return error(e);
          }
        })
        .build();
  }

  private McpServerFeatures.SyncToolSpecification deployLocalFolderTool() {
    var schema = new McpSchema.JsonSchema("object", 
        Map.of(
            "project", Map.of("type", "string", "description", "GCP project ID."),
            "service", Map.of("type", "string", "description", "Service name."),
            "folderPath", Map.of("type", "string", "description", "Local path."),
            "runtime", Map.of("type", "string", "description", "Runtime.")
        ),
        List.of("project", "service", "folderPath"), null, null, null);
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(McpSchema.Tool.builder()
            .name("deploy_local_folder")
            .description("Deploys local folder.")
            .inputSchema(schema)
            .build())
        .callHandler((exchange, request) -> {
          try {
            return tools.deploy_local_folder(request.arguments(), extractToken(exchange));
          } catch (Exception e) {
            return error(e);
          }
        })
        .build();
  }

  private McpServerFeatures.SyncToolSpecification deployFileContentsTool() {
    var fileSchema = new McpSchema.JsonSchema("object", 
        Map.of(
            "filename", Map.of("type", "string"),
            "content", Map.of("type", "string")
        ),
        List.of("filename", "content"), null, null, null);
    
    var schema = new McpSchema.JsonSchema("object", 
        Map.of(
            "project", Map.of("type", "string"),
            "service", Map.of("type", "string"),
            "files", Map.of("type", "array", "items", fileSchema, "description", "Files to deploy."),
            "runtime", Map.of("type", "string")
        ),
        List.of("project", "service", "files"), null, null, null);
        
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(McpSchema.Tool.builder()
            .name("deploy_file_contents")
            .description("Deploys file contents.")
            .inputSchema(schema)
            .build())
        .callHandler((exchange, request) -> {
          try {
            return tools.deploy_file_contents(request.arguments(), extractToken(exchange));
          } catch (Exception e) {
            return error(e);
          }
        })
        .build();
  }

  private String extractToken(McpSyncServerExchange exchange) {
    try {
        var context = exchange.transportContext();
        if (context != null) {
            Object headersObj = context.get("headers");
            if (headersObj instanceof Map<?, ?> headers) {
                Object authObj = headers.get("Authorization");
                if (authObj instanceof List<?> authList && !authList.isEmpty()) {
                    String val = String.valueOf(authList.get(0));
                    if (val.startsWith("Bearer ")) return val.substring(7).trim();
                }
            }
        }
    } catch (Exception ignored) {}
    return Constants.GCLOUD_AUTH;
  }

  private McpSchema.CallToolResult error(Exception e) {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(e.getMessage())))
        .isError(true)
        .build();
  }
}
