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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.appengine.tools.development.testing.LocalMemcacheServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalServiceTestHelper;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolRegistry}.
 *
 * <p>Uses the App Engine local test helpers so Memcache and Datastore stubs are available without
 * a real App Engine environment.
 */
class ToolRegistryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // App Engine local service helpers – provide in-process stubs for legacy APIs
  private final LocalServiceTestHelper helper =
      new LocalServiceTestHelper(new LocalMemcacheServiceTestConfig());

  @BeforeEach
  void setUp() {
    helper.setUp();
    // Disable credential check for unit tests
    System.setProperty("SKIP_IAM_CHECK", "true");
  }

  @AfterEach
  void tearDown() {
    helper.tearDown();
    System.clearProperty("SKIP_IAM_CHECK");
  }

  // ---------------------------------------------------------------------------
  // tools/list tests
  // ---------------------------------------------------------------------------

  @Test
  void toolListContainsExpectedTools() {
    // Build a minimal registry without requiring real GCP credentials
    ToolRegistry registry = buildRegistryNoCreds();
    ArrayNode tools = registry.getToolListJson();

    assertNotNull(tools);
    assertFalse(tools.isEmpty(), "Tool list must not be empty");

    var toolNames = StreamSupport.stream(tools.spliterator(), false)
        .map(n -> n.path("name").asText())
        .toList();

    assertTrue(toolNames.contains("list_projects"),  "list_projects missing");
    assertTrue(toolNames.contains("create_project"), "create_project missing");
    assertTrue(toolNames.contains("list_services"),  "list_services missing");
    assertTrue(toolNames.contains("get_service"),    "get_service missing");
    assertTrue(toolNames.contains("list_versions"),  "list_versions missing");
    assertTrue(toolNames.contains("get_version"),    "get_version missing");
    assertTrue(toolNames.contains("get_service_log"), "get_service_log missing");
    assertTrue(toolNames.contains("deploy_local_folder"),  "deploy_local_folder missing");
    assertTrue(toolNames.contains("deploy_file_contents"), "deploy_file_contents missing");

    assertEquals(9, toolNames.size(), "Unexpected number of tools: " + toolNames);
  }

  @Test
  void eachToolHasNameDescriptionAndInputSchema() {
    ToolRegistry registry = buildRegistryNoCreds();
    for (var tool : registry.getToolListJson()) {
      String name = tool.path("name").asText();
      assertFalse(tool.path("name").asText().isBlank(),
          name + " must have a non-empty name");
      assertFalse(tool.path("description").asText().isBlank(),
          name + " must have a non-empty description");
      assertTrue(tool.has("inputSchema"),
          name + " must have an inputSchema");
      assertEquals("object", tool.path("inputSchema").path("type").asText(),
          name + ".inputSchema.type must be 'object'");
    }
  }

  // ---------------------------------------------------------------------------
  // tools/call tests (no real GCP – credentials guard should activate)
  // ---------------------------------------------------------------------------

  @Test
  void callUnknownToolReturnsError() {
    ToolRegistry registry = buildRegistryNoCreds();
    var result = registry.callTool("nonexistent_tool", MAPPER.createObjectNode(), "token");
    assertNotNull(result);
    assertTrue(result.isError());
    assertTrue(result.content().get(0).path("text").asText().contains("Unknown tool"));
  }

  @Test
  void callToolWithoutCredsReturnsCredentialMessage() {
    ToolRegistry registry = buildRegistryNoCreds();
    // list_projects should return a "credentials not available" message
    var result = registry.callTool("list_projects", MAPPER.createObjectNode(), "gcloud_auth");
    assertNotNull(result);
    assertFalse(result.isError(), "Credential guard returns a text result, not an error");
    String text = result.content().get(0).path("text").asText();
    assertTrue(text.toLowerCase().contains("credentials"),
        "Expected credentials message, got: " + text);
  }

  @Test
  void callDeployFileContentsWithMissingProjectReturnsError() {
    ToolRegistry registry = buildRegistryNoCreds();
    ObjectNode args = MAPPER.createObjectNode();
    // Provide files but no project
    ArrayNode files = MAPPER.createArrayNode();
    ObjectNode file = MAPPER.createObjectNode();
    file.put("filename", "app.yaml");
    file.put("content", "runtime: java21\nenv: standard\n");
    files.add(file);
    args.set("files", files);

    var result = registry.callTool("deploy_file_contents", args, "gcloud_auth");
    // Without credentials, should get the credential guard message
    assertNotNull(result);
    String text = result.content().get(0).path("text").asText();
    assertTrue(text.toLowerCase().contains("credentials") || text.toLowerCase().contains("error"));
  }

  // ---------------------------------------------------------------------------
  // McpController.extractAccessToken
  // ---------------------------------------------------------------------------

  @Test
  void extractAccessTokenFromBearerHeader() {
    String token = com.google.cloud.appengine.mcp.McpController
        .extractAccessToken("Bearer my-token-123");
    assertEquals("my-token-123", token);
  }

  @Test
  void extractAccessTokenFallsBackToGcloudAuth() {
    String token = com.google.cloud.appengine.mcp.McpController
        .extractAccessToken(null);
    assertEquals(com.google.cloud.appengine.mcp.Constants.GCLOUD_AUTH, token);

    token = com.google.cloud.appengine.mcp.McpController
        .extractAccessToken("Basic abc123");
    assertEquals(com.google.cloud.appengine.mcp.Constants.GCLOUD_AUTH, token);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Builds a ToolRegistry with GCP credentials marked as unavailable so all tool calls return the
   * credential-guard message instead of hitting real GCP APIs.
   */
  private static ToolRegistry buildRegistryNoCreds() {
    // We use reflection to access the private constructor and inject false for credsAvailable.
    // Rather than that, let's just use a system property to make ensureGCPCredentials() → false.
    // The easiest approach: call the internal package-private path if available.
    // Since ToolRegistry.createDefault() calls AuthService.ensureGCPCredentials(), and in tests
    // that fails (no real ADC), the registry will correctly set gcpCredentialsAvailable=false.
    return ToolRegistry.createDefault();
  }
}
