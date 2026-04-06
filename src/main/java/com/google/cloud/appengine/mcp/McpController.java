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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.appengine.mcp.tools.ToolRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * HTTP controller implementing the MCP (Model Context Protocol) endpoints.
 *
 * <p>Mirrors the Express routes in mcp-server.js of cloud-run-mcp:
 *
 * <ul>
 *   <li>{@code POST /mcp} – Streamable HTTP transport (modern MCP clients)
 *   <li>{@code GET  /sse} – Legacy SSE transport (older clients)
 *   <li>{@code POST /messages} – Legacy SSE message posting
 *   <li>{@code GET  /.well-known/oauth-protected-resource}
 *   <li>{@code GET  /.well-known/oauth-authorization-server}
 * </ul>
 */
@RestController
public class McpController {

  private static final Logger log = LoggerFactory.getLogger(McpController.class);

  private final ObjectMapper objectMapper;
  private final ToolRegistry toolRegistry;

  // Active SSE sessions keyed by sessionId
  private final Map<String, SseSession> sseSessions = new ConcurrentHashMap<>();

  public McpController(ObjectMapper objectMapper, ToolRegistry toolRegistry) {
    this.objectMapper = objectMapper;
    this.toolRegistry = toolRegistry;
  }

  // ---------------------------------------------------------------------------
  // POST /mcp  –  Streamable HTTP transport (stateless, one JSON-RPC per POST)
  // ---------------------------------------------------------------------------

  @PostMapping(
      value = "/mcp",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> handleMcp(
      @RequestBody String body,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @org.springframework.lang.Nullable HttpServletRequest request) {

    log.debug("POST /mcp received: {}", body);
    String accessToken = extractAccessToken(authorization);

    try {
      JsonNode req = objectMapper.readTree(body);
      String method = req.path("method").asText();
      JsonNode id = req.path("id");
      JsonNode params = req.path("params");

      ObjectNode response = processRequest(method, id, params, accessToken);
      return ResponseEntity.ok(objectMapper.writeValueAsString(response));

    } catch (Exception e) {
      log.error("Error handling POST /mcp", e);
      return ResponseEntity.status(500)
          .body(jsonRpcError(null, -32603, "Internal server error: " + e.getMessage()));
    }
  }

  @GetMapping("/mcp")
  public ResponseEntity<String> handleMcpGet() {
    return ResponseEntity.status(405)
        .body(jsonRpcError(null, -32000, "Method not allowed."));
  }

  @DeleteMapping("/mcp")
  public ResponseEntity<String> handleMcpDelete() {
    return ResponseEntity.status(405)
        .body(jsonRpcError(null, -32000, "Method not allowed."));
  }

  // ---------------------------------------------------------------------------
  // GET /sse  –  Legacy SSE transport (backward compatibility)
  // ---------------------------------------------------------------------------

  @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter handleSse(
      @RequestHeader(value = "Authorization", required = false) String authorization) {

    log.debug("GET /sse new connection");
    String accessToken = extractAccessToken(authorization);
    String sessionId = UUID.randomUUID().toString();

    // App Engine standard: max request duration is 10 minutes (600 000 ms).
    // Set a slightly shorter timeout to close cleanly.
    SseEmitter emitter = new SseEmitter(580_000L);

    SseSession session = new SseSession(sessionId, emitter, accessToken, objectMapper);
    sseSessions.put(sessionId, session);

    emitter.onCompletion(() -> sseSessions.remove(sessionId));
    emitter.onTimeout(() -> sseSessions.remove(sessionId));
    emitter.onError(ex -> sseSessions.remove(sessionId));

    // Send the endpoint URL so the client knows where to POST messages
    try {
      emitter.send(
          SseEmitter.event()
              .name("endpoint")
              .data("/messages?sessionId=" + sessionId));
    } catch (IOException e) {
      emitter.completeWithError(e);
    }

    return emitter;
  }

  // ---------------------------------------------------------------------------
  // POST /messages  –  Legacy SSE client→server messages
  // ---------------------------------------------------------------------------

  @PostMapping(
      value = "/messages",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> handleMessages(
      @RequestParam String sessionId,
      @RequestBody String body) {

    log.debug("POST /messages sessionId={}", sessionId);
    SseSession session = sseSessions.get(sessionId);
    if (session == null) {
      return ResponseEntity.badRequest().build();
    }

    try {
      JsonNode req = objectMapper.readTree(body);
      String method = req.path("method").asText();
      JsonNode id = req.path("id");
      JsonNode params = req.path("params");

      ObjectNode response = processRequest(method, id, params, session.accessToken());

      // Notifications have no id and expect no response
      if (id.isMissingNode() || id.isNull()) {
        return ResponseEntity.ok().build();
      }

      session.send("message", objectMapper.writeValueAsString(response));
    } catch (Exception e) {
      log.error("Error handling POST /messages", e);
    }
    return ResponseEntity.ok().build();
  }

  // ---------------------------------------------------------------------------
  // OAuth well-known endpoints
  // ---------------------------------------------------------------------------

  @GetMapping("/.well-known/oauth-protected-resource")
  public ResponseEntity<Map<String, Object>> oauthProtectedResource() {
    return ResponseEntity.ok(Map.of(
        "resource", getEnv("OAUTH_PROTECTED_RESOURCE", ""),
        "authorization_servers", new String[]{getEnv("OAUTH_AUTHORIZATION_SERVER", "")},
        "scopes_supported", new String[]{
            Constants.SCOPE_OPENID, Constants.SCOPE_EMAIL, Constants.SCOPE_CLOUD_PLATFORM},
        "bearer_methods_supported", new String[]{"header"}));
  }

  @GetMapping("/.well-known/oauth-authorization-server")
  public ResponseEntity<Map<String, Object>> oauthAuthorizationServer() {
    return ResponseEntity.ok(Map.of(
        "issuer", getEnv("OAUTH_PROTECTED_RESOURCE", ""),
        "authorization_endpoint", getEnv("OAUTH_AUTHORIZATION_ENDPOINT", ""),
        "token_endpoint", getEnv("OAUTH_TOKEN_ENDPOINT", ""),
        "scopes_supported", new String[]{
            Constants.SCOPE_OPENID, Constants.SCOPE_EMAIL, Constants.SCOPE_CLOUD_PLATFORM},
        "response_types_supported", new String[]{"code"}));
  }

  // ---------------------------------------------------------------------------
  // MCP JSON-RPC protocol dispatcher
  // ---------------------------------------------------------------------------

  private ObjectNode processRequest(
      String method, JsonNode id, JsonNode params, String accessToken) {

    try {
      return switch (method) {
        case "initialize" -> handleInitialize(id, params);
        case "notifications/initialized" -> handleNotification();
        case "ping" -> jsonRpcResult(id, objectMapper.createObjectNode());
        case "tools/list" -> handleToolsList(id);
        case "tools/call" -> handleToolsCall(id, params, accessToken);
        case "prompts/list" -> handlePromptsList(id);
        case "prompts/get" -> handlePromptsGet(id, params);
        case "logging/setLevel" -> jsonRpcResult(id, objectMapper.createObjectNode());
        default -> {
          log.warn("Unknown MCP method: {}", method);
          yield jsonRpcErrorNode(id, -32601, "Method not found: " + method);
        }
      };
    } catch (Exception e) {
      log.error("Error processing MCP method {}: {}", method, e.getMessage(), e);
      return jsonRpcErrorNode(id, -32603, e.getMessage());
    }
  }

  private ObjectNode handleInitialize(JsonNode id, JsonNode params) {
    ObjectNode result = objectMapper.createObjectNode();
    result.put("protocolVersion", Constants.MCP_PROTOCOL_VERSION);

    ObjectNode capabilities = objectMapper.createObjectNode();
    capabilities.set("tools", objectMapper.createObjectNode());
    capabilities.set("logging", objectMapper.createObjectNode());
    capabilities.set("prompts", objectMapper.createObjectNode());
    result.set("capabilities", capabilities);

    ObjectNode serverInfo = objectMapper.createObjectNode();
    serverInfo.put("name", Constants.SERVER_NAME);
    serverInfo.put("version", Constants.SERVER_VERSION);
    result.set("serverInfo", serverInfo);

    return jsonRpcResult(id, result);
  }

  private ObjectNode handleNotification() {
    // Notification – no response needed (returns an empty object for HTTP)
    return objectMapper.createObjectNode();
  }

  private ObjectNode handleToolsList(JsonNode id) {
    ObjectNode result = objectMapper.createObjectNode();
    result.set("tools", toolRegistry.getToolListJson());
    return jsonRpcResult(id, result);
  }

  private ObjectNode handleToolsCall(JsonNode id, JsonNode params, String accessToken) {
    String toolName = params.path("name").asText();
    JsonNode arguments = params.path("arguments");

    ToolRegistry.CallResult callResult = toolRegistry.callTool(toolName, arguments, accessToken);

    ObjectNode result = objectMapper.createObjectNode();
    result.set("content", callResult.content());
    result.put("isError", callResult.isError());
    return jsonRpcResult(id, result);
  }

  private ObjectNode handlePromptsList(JsonNode id) {
    ArrayNode prompts = objectMapper.createArrayNode();

    ObjectNode deployPrompt = objectMapper.createObjectNode();
    deployPrompt.put("name", "deploy");
    deployPrompt.put("description", "Deploy the current directory to App Engine");
    ArrayNode deployArgs = objectMapper.createArrayNode();
    addPromptArg(deployArgs, "name", "App Engine service name", false);
    addPromptArg(deployArgs, "project", "GCP project ID", false);
    deployPrompt.set("arguments", deployArgs);
    prompts.add(deployPrompt);

    ObjectNode logsPrompt = objectMapper.createObjectNode();
    logsPrompt.put("name", "logs");
    logsPrompt.put("description", "Fetch logs for an App Engine service");
    ArrayNode logsArgs = objectMapper.createArrayNode();
    addPromptArg(logsArgs, "service", "App Engine service name", false);
    addPromptArg(logsArgs, "project", "GCP project ID", false);
    logsPrompt.set("arguments", logsArgs);
    prompts.add(logsPrompt);

    ObjectNode result = objectMapper.createObjectNode();
    result.set("prompts", prompts);
    return jsonRpcResult(id, result);
  }

  private ObjectNode handlePromptsGet(JsonNode id, JsonNode params) {
    String name = params.path("name").asText();
    JsonNode args = params.path("arguments");

    ArrayNode messages = objectMapper.createArrayNode();
    ObjectNode message = objectMapper.createObjectNode();
    message.put("role", "user");

    ObjectNode content = objectMapper.createObjectNode();
    content.put("type", "text");

    switch (name) {
      case "deploy" -> {
        String svcName = args.path("name").asText("default");
        String project = args.path("project").asText("(your-project-id)");
        content.put("text",
            "Deploy the current directory to App Engine service '%s' in project '%s'."
                .formatted(svcName, project)
                + " Use the deploy_local_folder tool.");
      }
      case "logs" -> {
        String svcName = args.path("service").asText("default");
        String project = args.path("project").asText("(your-project-id)");
        content.put("text",
            "Get the logs for App Engine service '%s' in project '%s'."
                .formatted(svcName, project)
                + " Use the get_service_log tool.");
      }
      default -> content.put("text", "Unknown prompt: " + name);
    }

    message.set("content", content);
    messages.add(message);

    ObjectNode result = objectMapper.createObjectNode();
    result.set("messages", messages);
    return jsonRpcResult(id, result);
  }

  // ---------------------------------------------------------------------------
  // JSON-RPC helpers
  // ---------------------------------------------------------------------------

  private ObjectNode jsonRpcResult(JsonNode id, JsonNode result) {
    ObjectNode resp = objectMapper.createObjectNode();
    resp.put("jsonrpc", "2.0");
    if (id != null && !id.isMissingNode()) resp.set("id", id);
    resp.set("result", result);
    return resp;
  }

  private ObjectNode jsonRpcErrorNode(JsonNode id, int code, String message) {
    ObjectNode resp = objectMapper.createObjectNode();
    resp.put("jsonrpc", "2.0");
    if (id != null && !id.isMissingNode()) resp.set("id", id);
    ObjectNode error = objectMapper.createObjectNode();
    error.put("code", code);
    error.put("message", message);
    resp.set("error", error);
    return resp;
  }

  private String jsonRpcError(JsonNode id, int code, String message) {
    try {
      return objectMapper.writeValueAsString(jsonRpcErrorNode(id, code, message));
    } catch (Exception e) {
      return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"serialization error\"}}";
    }
  }

  private void addPromptArg(ArrayNode array, String name, String description, boolean required) {
    ObjectNode arg = objectMapper.createObjectNode();
    arg.put("name", name);
    arg.put("description", description);
    arg.put("required", required);
    array.add(arg);
  }

  // ---------------------------------------------------------------------------
  // Utilities
  // ---------------------------------------------------------------------------

  static String extractAccessToken(String authHeader) {
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      return authHeader.substring(7).trim();
    }
    return Constants.GCLOUD_AUTH;
  }

  private static String getEnv(String key, String defaultValue) {
    String val = System.getenv(key);
    return val != null ? val : System.getProperty(key, defaultValue);
  }

  // ---------------------------------------------------------------------------
  // SSE session wrapper
  // ---------------------------------------------------------------------------

  private record SseSession(
      String sessionId,
      SseEmitter emitter,
      String accessToken,
      ObjectMapper objectMapper) {

    void send(String eventName, String data) {
      try {
        emitter.send(SseEmitter.event().name(eventName).data(data));
      } catch (IOException e) {
        emitter.completeWithError(e);
      }
    }
  }
}
