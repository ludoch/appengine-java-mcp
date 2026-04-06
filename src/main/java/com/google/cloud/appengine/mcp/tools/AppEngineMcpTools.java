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

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import com.google.cloud.appengine.mcp.cloud.AppEngineApiService;
import com.google.cloud.appengine.mcp.cloud.LoggingApiService;
import com.google.cloud.appengine.mcp.cloud.ProjectsApiService;
import com.google.cloud.appengine.mcp.deployment.AppEngineDeployer;
import com.google.cloud.appengine.mcp.deployment.AppEngineDeployer.DeployConfig;
import com.google.cloud.appengine.mcp.deployment.AppEngineDeployer.FileEntry;
import com.google.cloud.appengine.mcp.deployment.DeploymentConstants;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP tools implementation using the official MCP Java SDK.
 */
public class AppEngineMcpTools {

  public CallToolResult list_projects(String token) throws IOException {
    List<String> ids = ProjectsApiService.listProjects(token);
    String text = "Available GCP Projects:\n"
        + ids.stream().map(id -> "- " + id).collect(Collectors.joining("\n"));
    return result(text);
  }

  public CallToolResult create_project(Map<String, Object> args, String token) throws IOException {
    String projectId = (String) args.get("projectId");
    String msg = ProjectsApiService.createProjectAndAttachBilling(projectId, null, token);
    return result(msg);
  }

  public CallToolResult list_services(Map<String, Object> args, String token) throws IOException {
    String project = (String) args.get("project");
    List<AppEngineApiService.ServiceInfo> services =
        AppEngineApiService.listServices(project, token);
    
    if (services.isEmpty()) {
      return result("No App Engine services found in project " + project + ".\n"
          + "Has the App Engine application been initialised? https://console.cloud.google.com/appengine?project=" + project);
    }
    
    StringBuilder sb = new StringBuilder("App Engine services in project ")
        .append(project).append(":\n");
    for (var svc : services) {
      sb.append("- ").append(svc.serviceId())
        .append(" (URL: ").append(svc.url()).append(")")
        .append(" [traffic: ").append(svc.trafficSplit()).append("]")
        .append("\n");
    }
    return result(sb.toString().strip());
  }

  public CallToolResult get_service(Map<String, Object> args, String token) throws IOException {
    String project = (String) args.get("project");
    String service = (String) args.get("service");
    var svc = AppEngineApiService.getService(project, service, token);
    String consoleUrl = "https://console.cloud.google.com/appengine/services?project=" + project;
    String text = """
        Name: %s
        Project: %s
        URL: %s
        Traffic: %s
        Console URL: %s
        """.formatted(svc.serviceId(), project, svc.url(), svc.trafficSplit(), consoleUrl).strip();
    return result(text);
  }

  public CallToolResult list_versions(Map<String, Object> args, String token) throws IOException {
    String project = (String) args.get("project");
    String service = (String) args.get("service");
    var versions = AppEngineApiService.listVersions(project, service, token);
    if (versions.isEmpty()) {
      return result("No versions found for " + service + " in " + project);
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
    return result(sb.toString().strip());
  }

  public CallToolResult get_version(Map<String, Object> args, String token) throws IOException {
    String project = (String) args.get("project");
    String service = (String) args.get("service");
    String versionId = (String) args.get("version");
    var v = AppEngineApiService.getVersion(project, service, versionId, token);
    String text = """
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
            v.servingStatus(), v.createTime(), v.createdBy(), v.diskUsageBytes()).strip();
    return result(text);
  }

  public CallToolResult get_service_log(Map<String, Object> args, String token) throws IOException {
    String project = (String) args.get("project");
    String service = (String) args.get("service");
    String logs = LoggingApiService.getServiceLogs(project, service, token);
    return result(logs.isBlank()
        ? "No log entries found for service " + service + " in project " + project
        : logs);
  }

  public CallToolResult deploy_local_folder(Map<String, Object> args, String token) throws IOException {
    String project = (String) args.get("project");
    String service = (String) args.get("service");
    String folderPath = (String) args.get("folderPath");
    String runtime = (String) args.get("runtime");

    List<String> progressLog = new ArrayList<>();
    String actualRuntime = (runtime == null || runtime.isBlank()) ? DeploymentConstants.DEFAULT_RUNTIME : runtime;
    
    String url = AppEngineDeployer.deploy(new DeployConfig(
        project, service, actualRuntime,
        List.of(FileEntry.ofPath(folderPath)),
        token, progressLog::add));
        
    return result("App Engine service '%s' deployed from folder '%s' in project '%s'.\n"
        + "Service URL: %s\n"
        + "Console: https://console.cloud.google.com/appengine/services?project=%s\n"
        + "\nDeployment log:\n%s"
            .formatted(service, folderPath, project, url, project, String.join("\n", progressLog)));
  }

  @SuppressWarnings("unchecked")
  public CallToolResult deploy_file_contents(Map<String, Object> args, String token) throws IOException {
    String project = (String) args.get("project");
    String service = (String) args.get("service");
    List<Map<String, String>> files = (List<Map<String, String>>) args.get("files");
    String runtime = (String) args.get("runtime");

    List<FileEntry> entries = files.stream()
        .map(f -> FileEntry.ofContent(f.get("filename"), f.get("content")))
        .collect(Collectors.toList());

    List<String> progressLog = new ArrayList<>();
    String actualRuntime = (runtime == null || runtime.isBlank()) ? DeploymentConstants.DEFAULT_RUNTIME : runtime;

    String url = AppEngineDeployer.deploy(new DeployConfig(
        project, service, actualRuntime, entries, token, progressLog::add));
        
    return result("App Engine service '%s' deployed in project '%s'.\n"
        + "Service URL: %s\n"
        + "Console: https://console.cloud.google.com/appengine/services?project=%s\n"
        + "\nDeployment log:\n%s"
            .formatted(service, project, url, project, String.join("\n", progressLog)));
  }

  private CallToolResult result(String text) {
    return CallToolResult.builder()
        .content(List.of(new TextContent(text)))
        .isError(false)
        .build();
  }
}
