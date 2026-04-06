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
package com.google.cloud.appengine.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.appengine.mcp.cloud.AppEngineApiService;
import com.google.cloud.appengine.mcp.cloud.LoggingApiService;
import com.google.cloud.appengine.mcp.cloud.ProjectsApiService;
import com.google.cloud.appengine.mcp.deployment.AppEngineDeployer;
import com.google.cloud.appengine.mcp.deployment.AppEngineDeployer.DeployConfig;
import com.google.cloud.appengine.mcp.deployment.AppEngineDeployer.FileEntry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool registry: registers all MCP tools, mirroring tools/register-tools.js.
 *
 * <p>Tools mirror the cloud-run-mcp tools but target App Engine:
 * <ol>
 *   <li>{@code list_projects}      – lists GCP projects (local mode only)
 *   <li>{@code create_project}     – creates a project + attaches billing (local mode only)
 *   <li>{@code list_services}      – lists App Engine services
 *   <li>{@code get_service}        – gets details for an App Engine service
 *   <li>{@code list_versions}      – lists versions of an App Engine service
 *   <li>{@code get_version}        – gets details for a specific version
 *   <li>{@code get_service_log}    – fetches App Engine service logs
 *   <li>{@code deploy_local_folder}    – deploys a local folder to App Engine
 *   <li>{@code deploy_file_contents}   – deploys file contents to App Engine
 * </ol>
 */
public final class ToolRegistry {

  private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Reads environment variable, falls back to system property, then to defaultValue. */
  private static String env(String key, String defaultValue) {
    String val = System.getenv(key);
    return val != null ? val : System.getProperty(key, defaultValue);
  }

  // Tool handler: (arguments JsonNode, accessToken) → CallResult
  private final Map<String, BiFunction<JsonNode, String, CallResult>> handlers = new HashMap<>();
  // Tool specs for tools/list
  private final ArrayNode toolSpecs;

  private ToolRegistry() {
    toolSpecs = MAPPER.createArrayNode();
  }

  // ---------------------------------------------------------------------------
  // Factory
  // ---------------------------------------------------------------------------

  /**
   * Creates a registry pre-populated with all App Engine MCP tools, reading defaults from
   * environment variables – mirroring the options object passed to registerTools() in tools.js.
   */
  public static ToolRegistry createDefault() {
    String defaultProject = env("GOOGLE_CLOUD_PROJECT", null);
    String defaultService = env("DEFAULT_SERVICE_NAME", "default");
    boolean skipIamCheck = !"false".equalsIgnoreCase(env("SKIP_IAM_CHECK", "true"));
    boolean gcpCredentialsAvailable = com.google.cloud.appengine.mcp.cloud.AuthService
        .ensureGCPCredentials();

    ToolRegistry registry = new ToolRegistry();
    registry.registerAllTools(defaultProject, defaultService, skipIamCheck,
        gcpCredentialsAvailable);
    return registry;
  }

  // ---------------------------------------------------------------------------
  // Tool registration
  // ---------------------------------------------------------------------------

  private void registerAllTools(
      String defaultProject,
      String defaultService,
      boolean skipIamCheck,
      boolean gcpCredentialsAvailable) {

    registerListProjectsTool(gcpCredentialsAvailable);
    registerCreateProjectTool(gcpCredentialsAvailable);
    registerListServicesTool(defaultProject, gcpCredentialsAvailable);
    registerGetServiceTool(defaultProject, defaultService, gcpCredentialsAvailable);
    registerListVersionsTool(defaultProject, defaultService, gcpCredentialsAvailable);
    registerGetVersionTool(defaultProject, defaultService, gcpCredentialsAvailable);
    registerGetServiceLogTool(defaultProject, defaultService, gcpCredentialsAvailable);
    registerDeployLocalFolderTool(defaultProject, defaultService, gcpCredentialsAvailable);
    registerDeployFileContentsTool(defaultProject, defaultService, gcpCredentialsAvailable);
  }

  private void register(
      String name,
      String description,
      ObjectNode inputSchema,
      BiFunction<JsonNode, String, CallResult> handler) {

    ObjectNode spec = MAPPER.createObjectNode();
    spec.put("name", name);
    spec.put("description", description);
    spec.set("inputSchema", inputSchema);
    toolSpecs.add(spec);
    handlers.put(name, handler);
  }

  /** Returns a no-op handler when credentials are not available. */
  private static BiFunction<JsonNode, String, CallResult> credentialGuard(
      boolean available, BiFunction<JsonNode, String, CallResult> fn) {
    if (!available) {
      return (args, token) -> CallResult.text(
          "GCP credentials are not available. Please configure your environment using OAuth "
              + "or `gcloud auth application-default login`.");
    }
    return fn;
  }

  // ---------------------------------------------------------------------------
  // Individual tool registrations (mirroring register-tools.js)
  // ---------------------------------------------------------------------------

  private void registerListProjectsTool(boolean credsAvailable) {
    register("list_projects",
        "Lists available GCP projects.",
        schema(),
        credentialGuard(credsAvailable, (args, token) -> {
          try {
            List<String> ids = ProjectsApiService.listProjects(token);
            return CallResult.text("Available GCP Projects:\n"
                + ids.stream().map(id -> "- " + id).reduce("", (a, b) -> a + "\n" + b).strip());
          } catch (IOException e) {
            return CallResult.text("Error listing GCP projects: " + e.getMessage());
          }
        }));
  }

  private void registerCreateProjectTool(boolean credsAvailable) {
    ObjectNode schema = schema();
    addProp(schema, "projectId", "string",
        "Optional. Desired GCP project ID. Auto-generated if omitted.", false);

    register("create_project",
        "Creates a new GCP project and attempts to attach it to the first available billing "
            + "account. A project ID can be optionally specified; otherwise it will be "
            + "automatically generated.",
        schema,
        credentialGuard(credsAvailable, (args, token) -> {
          String projectId = strOpt(args, "projectId");
          try {
            String msg = ProjectsApiService.createProjectAndAttachBilling(
                projectId, null, token);
            return CallResult.text(msg);
          } catch (IOException e) {
            return CallResult.text(
                "Error creating GCP project or attaching billing: " + e.getMessage());
          }
        }));
  }

  private void registerListServicesTool(String defaultProject, boolean credsAvailable) {
    ObjectNode schema = schema();
    addPropWithDefault(schema, "project", "string",
        "Google Cloud project ID.", defaultProject);

    register("list_services",
        "Lists all App Engine services in a given project.",
        schema,
        credentialGuard(credsAvailable, (args, token) -> {
          String project = str(args, "project", defaultProject);
          if (project == null) return CallResult.text(
              "Error: Project ID must be provided and be a non-empty string.");
          try {
            List<AppEngineApiService.ServiceInfo> services =
                AppEngineApiService.listServices(project, token);
            if (services.isEmpty()) {
              return CallResult.text("No App Engine services found in project " + project + ".\n"
                  + "Has the App Engine application been initialised? "
                  + "https://console.cloud.google.com/appengine?project=" + project);
            }
            StringBuilder sb = new StringBuilder("App Engine services in project ")
                .append(project).append(":\n");
            for (var svc : services) {
              sb.append("- ").append(svc.serviceId())
                .append(" (URL: ").append(svc.url()).append(")")
                .append(" [traffic: ").append(svc.trafficSplit()).append("]")
                .append("\n");
            }
            return CallResult.text(sb.toString().strip());
          } catch (IOException e) {
            return CallResult.text(
                "Error listing services for project " + project + ": " + e.getMessage());
          }
        }));
  }

  private void registerGetServiceTool(
      String defaultProject, String defaultService, boolean credsAvailable) {
    ObjectNode schema = schema();
    addPropWithDefault(schema, "project", "string",
        "Google Cloud project ID containing the service.", defaultProject);
    addPropWithDefault(schema, "service", "string",
        "Name of the App Engine service.", defaultService);

    register("get_service",
        "Gets details for a specific App Engine service.",
        schema,
        credentialGuard(credsAvailable, (args, token) -> {
          String project = str(args, "project", defaultProject);
          String service = str(args, "service", defaultService);
          if (project == null) return CallResult.text("Error: Project ID must be provided.");
          if (service == null) return CallResult.text("Error: Service name must be provided.");
          try {
            var svc = AppEngineApiService.getService(project, service, token);
            String consoleUrl =
                "https://console.cloud.google.com/appengine/services?project=" + project;
            return CallResult.text("""
                Name: %s
                Project: %s
                URL: %s
                Traffic: %s
                Console URL: %s
                """.formatted(svc.serviceId(), project, svc.url(),
                    svc.trafficSplit(), consoleUrl).strip());
          } catch (IOException e) {
            return CallResult.text(
                "Error getting service " + service + " in project " + project
                    + ": " + e.getMessage());
          }
        }));
  }

  private void registerListVersionsTool(
      String defaultProject, String defaultService, boolean credsAvailable) {
    ObjectNode schema = schema();
    addPropWithDefault(schema, "project", "string",
        "Google Cloud project ID.", defaultProject);
    addPropWithDefault(schema, "service", "string",
        "App Engine service name.", defaultService);

    register("list_versions",
        "Lists versions of an App Engine service.",
        schema,
        credentialGuard(credsAvailable, (args, token) -> {
          String project = str(args, "project", defaultProject);
          String service = str(args, "service", defaultService);
          if (project == null) return CallResult.text("Error: Project ID must be provided.");
          if (service == null) return CallResult.text("Error: Service name must be provided.");
          try {
            var versions = AppEngineApiService.listVersions(project, service, token);
            if (versions.isEmpty()) {
              return CallResult.text("No versions found for " + service + " in " + project);
            }
            StringBuilder sb = new StringBuilder("Versions of ")
                .append(service).append(" in ").append(project).append(":\n");
            for (var v : versions) {
              sb.append("- ").append(v.versionId())
                .append(" [").append(v.servingStatus()).append("]")
                .append(" runtime=").append(v.runtime())
                .append(" created=").append(v.createTime())
                .append("\n");
            }
            return CallResult.text(sb.toString().strip());
          } catch (IOException e) {
            return CallResult.text("Error listing versions: " + e.getMessage());
          }
        }));
  }

  private void registerGetVersionTool(
      String defaultProject, String defaultService, boolean credsAvailable) {
    ObjectNode schema = schema();
    addPropWithDefault(schema, "project", "string",
        "Google Cloud project ID.", defaultProject);
    addPropWithDefault(schema, "service", "string",
        "App Engine service name.", defaultService);
    addProp(schema, "version", "string", "Version ID to retrieve.", true);

    register("get_version",
        "Gets details for a specific App Engine version.",
        schema,
        credentialGuard(credsAvailable, (args, token) -> {
          String project = str(args, "project", defaultProject);
          String service = str(args, "service", defaultService);
          String version = str(args, "version", null);
          if (project == null) return CallResult.text("Error: Project ID must be provided.");
          if (service == null) return CallResult.text("Error: Service name must be provided.");
          if (version == null) return CallResult.text("Error: Version ID must be provided.");
          try {
            var v = AppEngineApiService.getVersion(project, service, version, token);
            return CallResult.text("""
                Version: %s
                Service: %s
                Project: %s
                Runtime: %s
                Environment: %s
                Status: %s
                Created: %s
                Created by: %s
                Disk usage: %d bytes
                """.formatted(v.versionId(), service, project, v.runtime(), v.env(),
                    v.servingStatus(), v.createTime(), v.createdBy(), v.diskUsageBytes()).strip());
          } catch (IOException e) {
            return CallResult.text("Error getting version: " + e.getMessage());
          }
        }));
  }

  private void registerGetServiceLogTool(
      String defaultProject, String defaultService, boolean credsAvailable) {
    ObjectNode schema = schema();
    addPropWithDefault(schema, "project", "string",
        "Google Cloud project ID containing the service.", defaultProject);
    addPropWithDefault(schema, "service", "string",
        "Name of the App Engine service.", defaultService);

    register("get_service_log",
        "Gets logs and error messages for a specific App Engine service.",
        schema,
        credentialGuard(credsAvailable, (args, token) -> {
          String project = str(args, "project", defaultProject);
          String service = str(args, "service", defaultService);
          if (project == null) return CallResult.text("Error: Project ID must be provided.");
          if (service == null) return CallResult.text("Error: Service name must be provided.");
          try {
            String logs = LoggingApiService.getServiceLogs(project, service, token);
            return CallResult.text(logs.isBlank()
                ? "No log entries found for service " + service + " in project " + project
                : logs);
          } catch (IOException e) {
            return CallResult.text("Error getting logs for service " + service
                + " in project " + project + ": " + e.getMessage());
          }
        }));
  }

  private void registerDeployLocalFolderTool(
      String defaultProject, String defaultService, boolean credsAvailable) {
    ObjectNode schema = schema();
    addPropWithDefault(schema, "project", "string",
        "Google Cloud project ID. Do not select it yourself; make sure the user provides "
            + "or confirms the project ID.", defaultProject);
    addPropWithDefault(schema, "service", "string",
        "App Engine service name to deploy to.", defaultService);
    addProp(schema, "folderPath", "string",
        "Absolute path to the folder to deploy (e.g. \"/home/user/project/src\").", true);
    addProp(schema, "runtime", "string",
        "Optional. App Engine runtime to use (e.g. \"java21\"). Defaults to \""
            + com.google.cloud.appengine.mcp.deployment.DeploymentConstants.DEFAULT_RUNTIME
            + "\".", false);

    register("deploy_local_folder",
        "Deploy a local folder to App Engine. Takes an absolute folder path from the local "
            + "filesystem. Use this tool if the entire folder content needs to be deployed.",
        schema,
        credentialGuard(credsAvailable, (args, token) -> {
          String project = str(args, "project", defaultProject);
          String service = str(args, "service", defaultService);
          String folderPath = str(args, "folderPath", null);
          String runtime = strOpt(args, "runtime");

          if (project == null) return CallResult.text(
              "Error: Project must be specified. Please provide a valid GCP project ID.");
          if (folderPath == null || folderPath.isBlank()) return CallResult.text(
              "Error: Folder path must be specified and be a non-empty string.");

          List<String> progressLog = new ArrayList<>();
          try {
            String url = AppEngineDeployer.deploy(new DeployConfig(
                project, service, runtime,
                List.of(FileEntry.ofPath(folderPath)),
                token, progressLog::add));
            return CallResult.text(
                "App Engine service '%s' deployed from folder '%s' in project '%s'.\n"
                    + "Service URL: %s\n"
                    + "Console: https://console.cloud.google.com/appengine/services?project=%s\n"
                    + "\nDeployment log:\n%s"
                        .formatted(service, folderPath, project, url, project,
                            String.join("\n", progressLog)));
          } catch (IOException e) {
            return CallResult.text(
                "Error deploying folder to App Engine: " + e.getMessage()
                    + "\n\nDeployment log:\n" + String.join("\n", progressLog));
          }
        }));
  }

  private void registerDeployFileContentsTool(
      String defaultProject, String defaultService, boolean credsAvailable) {
    ObjectNode schema = schema();
    addPropWithDefault(schema, "project", "string",
        "Google Cloud project ID. Leave unset for the app to be deployed in a new project. "
            + "If provided, make sure the user confirms the project ID.", defaultProject);
    addPropWithDefault(schema, "service", "string",
        "App Engine service name to deploy to.", defaultService);

    // files array property
    ObjectNode filesSchema = MAPPER.createObjectNode();
    filesSchema.put("type", "array");
    ObjectNode fileItem = MAPPER.createObjectNode();
    fileItem.put("type", "object");
    ObjectNode fileProps = MAPPER.createObjectNode();
    addStringProp(fileProps, "filename",
        "Name and path of the file (e.g. \"src/Main.java\" or \"app.yaml\")");
    addStringProp(fileProps, "content", "Text content of the file");
    fileItem.set("properties", fileProps);
    fileItem.set("required", MAPPER.createArrayNode().add("filename").add("content"));
    filesSchema.set("items", fileItem);
    filesSchema.put("description", "Array of file objects containing filename and content");
    ((ObjectNode) schema.get("properties")).set("files", filesSchema);
    ((ArrayNode) schema.get("required")).add("files");

    addProp(schema, "runtime", "string",
        "Optional. App Engine runtime (e.g. \"java21\").", false);

    register("deploy_file_contents",
        "Deploy files to App Engine by providing their contents directly. Takes an array of "
            + "file objects containing filename and content. Use this tool if the files only "
            + "exist in the current chat context.",
        schema,
        credentialGuard(credsAvailable, (args, token) -> {
          String project = str(args, "project", defaultProject);
          String service = str(args, "service", defaultService);
          String runtime = strOpt(args, "runtime");
          JsonNode filesNode = args.get("files");

          if (project == null) return CallResult.text(
              "Error: Project must be specified. Please provide a valid GCP project ID.");
          if (filesNode == null || !filesNode.isArray() || filesNode.isEmpty()) return CallResult
              .text("Error: files array must be specified and non-empty.");

          List<FileEntry> files = new ArrayList<>();
          for (JsonNode f : filesNode) {
            String filename = f.path("filename").asText();
            String content = f.path("content").asText();
            if (filename.isBlank() || content.isBlank()) return CallResult.text(
                "Error: Each file must have a non-empty filename and content.");
            files.add(FileEntry.ofContent(filename, content));
          }

          List<String> progressLog = new ArrayList<>();
          try {
            String url = AppEngineDeployer.deploy(new DeployConfig(
                project, service, runtime, files, token, progressLog::add));
            return CallResult.text(
                "App Engine service '%s' deployed in project '%s'.\n"
                    + "Service URL: %s\n"
                    + "Console: https://console.cloud.google.com/appengine/services?project=%s\n"
                    + "\nDeployment log:\n%s"
                        .formatted(service, project, url, project,
                            String.join("\n", progressLog)));
          } catch (IOException e) {
            return CallResult.text(
                "Error deploying to App Engine: " + e.getMessage()
                    + "\n\nDeployment log:\n" + String.join("\n", progressLog));
          }
        }));
  }

  // ---------------------------------------------------------------------------
  // Public interface used by McpController
  // ---------------------------------------------------------------------------

  public ArrayNode getToolListJson() {
    return toolSpecs;
  }

  public CallResult callTool(String name, JsonNode arguments, String accessToken) {
    var handler = handlers.get(name);
    if (handler == null) {
      return CallResult.error("Unknown tool: " + name);
    }
    try {
      return handler.apply(arguments != null ? arguments : MAPPER.createObjectNode(), accessToken);
    } catch (Exception e) {
      log.error("Tool {} threw an exception", name, e);
      return CallResult.error("Tool execution error: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Schema helpers
  // ---------------------------------------------------------------------------

  private static ObjectNode schema() {
    ObjectNode s = MAPPER.createObjectNode();
    s.put("type", "object");
    s.set("properties", MAPPER.createObjectNode());
    s.set("required", MAPPER.createArrayNode());
    return s;
  }

  private static void addProp(
      ObjectNode schema, String name, String type, String description, boolean required) {
    ObjectNode prop = MAPPER.createObjectNode();
    prop.put("type", type);
    prop.put("description", description);
    ((ObjectNode) schema.get("properties")).set(name, prop);
    if (required) ((ArrayNode) schema.get("required")).add(name);
  }

  private static void addPropWithDefault(
      ObjectNode schema, String name, String type, String description, String defaultValue) {
    ObjectNode prop = MAPPER.createObjectNode();
    prop.put("type", type);
    prop.put("description", description);
    if (defaultValue != null) prop.put("default", defaultValue);
    ((ObjectNode) schema.get("properties")).set(name, prop);
  }

  private static void addStringProp(ObjectNode props, String name, String description) {
    ObjectNode p = MAPPER.createObjectNode();
    p.put("type", "string");
    p.put("description", description);
    props.set(name, p);
  }

  // ---------------------------------------------------------------------------
  // JSON field access helpers
  // ---------------------------------------------------------------------------

  private static String str(JsonNode args, String key, String defaultValue) {
    JsonNode node = args.get(key);
    if (node == null || node.isNull() || node.asText().isBlank()) return defaultValue;
    return node.asText();
  }

  private static String strOpt(JsonNode args, String key) {
    return str(args, key, null);
  }

  // ---------------------------------------------------------------------------
  // Result type
  // ---------------------------------------------------------------------------

  /**
   * Result of a tool call, mirrors the MCP CallToolResult schema.
   *
   * @param content  JSON array of content blocks to be set in the MCP response
   * @param isError  {@code true} when the tool call failed
   */
  public record CallResult(ArrayNode content, boolean isError) {

    static CallResult text(String text) {
      ArrayNode arr = MAPPER.createArrayNode();
      ObjectNode block = MAPPER.createObjectNode();
      block.put("type", "text");
      block.put("text", text);
      arr.add(block);
      return new CallResult(arr, false);
    }

    static CallResult error(String message) {
      ArrayNode arr = MAPPER.createArrayNode();
      ObjectNode block = MAPPER.createObjectNode();
      block.put("type", "text");
      block.put("text", message);
      arr.add(block);
      return new CallResult(arr, true);
    }
  }
}
