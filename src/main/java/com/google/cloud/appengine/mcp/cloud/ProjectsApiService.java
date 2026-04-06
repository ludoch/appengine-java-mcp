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
package com.google.cloud.appengine.mcp.cloud;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.billing.v1.BillingAccountName;
import com.google.cloud.billing.v1.CloudBillingClient;
import com.google.cloud.billing.v1.CloudBillingSettings;
import com.google.cloud.billing.v1.ProjectBillingInfo;
import com.google.cloud.resourcemanager.v3.Project;
import com.google.cloud.resourcemanager.v3.ProjectsClient;
import com.google.cloud.resourcemanager.v3.ProjectsSettings;
import com.google.cloud.resourcemanager.v3.SearchProjectsRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GCP Project management operations, mirroring lib/cloud-api/projects.js.
 *
 * <p>Uses the legacy App Engine Memcache (via {@code appengine-api-1.0-sdk}) to cache the project
 * list so the Resource Manager API is not hit on every tool call.
 */
public final class ProjectsApiService {

  private static final Logger log = LoggerFactory.getLogger(ProjectsApiService.class);
  private static final String CACHE_KEY_PROJECTS = "gcp_projects_list";
  private static final int CACHE_TTL_SECONDS;

  static {
    int ttl = 120;
    try {
      ttl = Integer.parseInt(System.getenv("CACHE_TTL_SECONDS"));
    } catch (Exception ignored) {}
    CACHE_TTL_SECONDS = ttl;
  }

  private ProjectsApiService() {}

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Lists all GCP projects accessible to the caller.
   * Results are cached in App Engine Memcache for {@code CACHE_TTL_SECONDS}.
   */
  @SuppressWarnings("unchecked")
  public static List<String> listProjects(String accessToken) throws IOException {
    // Try Memcache first (only works when running on App Engine with legacy APIs)
    List<String> cached = getFromMemcache(CACHE_KEY_PROJECTS);
    if (cached != null) {
      log.debug("Returning project list from Memcache cache");
      return cached;
    }

    log.debug("Fetching project list from Resource Manager API");
    GoogleCredentials creds = AuthService.getCredentials(accessToken);
    ProjectsSettings settings = ProjectsSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(creds))
        .build();

    List<String> projectIds = new ArrayList<>();
    try (ProjectsClient client = ProjectsClient.create(settings)) {
      var response = client.searchProjects(SearchProjectsRequest.newBuilder().build());
      for (Project project : response.iterateAll()) {
        projectIds.add(project.getProjectId());
      }
    }

    putToMemcache(CACHE_KEY_PROJECTS, projectIds, CACHE_TTL_SECONDS);
    return projectIds;
  }

  /**
   * Creates a new GCP project and attempts to attach the first available billing account.
   * Mirrors createProjectAndAttachBilling() from projects.js.
   */
  public static String createProjectAndAttachBilling(
      String projectId, String parent, String accessToken) throws IOException {

    String newProjectId = (projectId != null && !projectId.isBlank())
        ? projectId
        : generateProjectId();

    log.info("Creating project: {}", newProjectId);
    GoogleCredentials creds = AuthService.getCredentials(accessToken);
    ProjectsSettings settings = ProjectsSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(creds))
        .build();

    try (ProjectsClient client = ProjectsClient.create(settings)) {
      Project.Builder builder = Project.newBuilder().setProjectId(newProjectId);
      if (parent != null && !parent.isBlank()) {
        builder.setParent(parent);
      }
      var operation = client.createProjectAsync(builder.build());
      Project created = operation.get();
      log.info("Project {} created", created.getProjectId());
    } catch (Exception e) {
      throw new IOException("Failed to create project " + newProjectId + ": " + e.getMessage(), e);
    }

    String billingMessage = "Project " + newProjectId + " created successfully.";
    billingMessage += attachBilling(newProjectId, accessToken);

    // Invalidate project list cache
    deleteFromMemcache(CACHE_KEY_PROJECTS);
    return billingMessage;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Generates an MCP-style project ID: mcp-cvc-cvc (consonant-vowel-consonant). */
  public static String generateProjectId() {
    String consonants = "bcdfghjklmnpqrstvwxyz";
    String vowels = "aeiou";
    Random rng = new Random();
    return "mcp-" + cvc(rng, consonants, vowels) + "-" + cvc(rng, consonants, vowels);
  }

  private static String cvc(Random rng, String c, String v) {
    return "" + c.charAt(rng.nextInt(c.length()))
        + v.charAt(rng.nextInt(v.length()))
        + c.charAt(rng.nextInt(c.length()));
  }

  private static String attachBilling(String projectId, String accessToken) {
    try {
      GoogleCredentials creds = AuthService.getCredentials(accessToken);
      CloudBillingSettings settings = CloudBillingSettings.newBuilder()
          .setCredentialsProvider(FixedCredentialsProvider.create(creds))
          .build();

      try (CloudBillingClient billingClient = CloudBillingClient.create(settings)) {
        var accounts = billingClient.listBillingAccounts().iterateAll();
        for (var account : accounts) {
          if (account.getOpen()) {
            String accountName = account.getName();
            ProjectBillingInfo info = ProjectBillingInfo.newBuilder()
                .setName("projects/" + projectId)
                .setBillingAccountName(accountName)
                .build();
            billingClient.updateProjectBillingInfo("projects/" + projectId, info);
            return " It has been attached to billing account " + account.getDisplayName() + ".";
          }
        }
        return " No open billing accounts found. Please link billing manually: "
            + "https://console.cloud.google.com/billing/linkedaccount?project=" + projectId;
      }
    } catch (Exception e) {
      log.warn("Billing attachment failed for {}: {}", projectId, e.getMessage());
      return " Billing attachment failed: " + e.getMessage()
          + ". Please link manually: https://console.cloud.google.com/billing/linkedaccount?project="
          + projectId;
    }
  }

  // ---------------------------------------------------------------------------
  // App Engine Memcache helpers (legacy App Engine API)
  // These methods gracefully no-op when not running on App Engine.
  // ---------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static <T> T getFromMemcache(String key) {
    if (CACHE_TTL_SECONDS <= 0) return null;
    try {
      com.google.appengine.api.memcache.MemcacheService memcache =
          com.google.appengine.api.memcache.MemcacheServiceFactory.getMemcacheService();
      return (T) memcache.get(key);
    } catch (Exception e) {
      // Not on App Engine or Memcache unavailable – silently skip
      return null;
    }
  }

  private static void putToMemcache(String key, Object value, int ttlSeconds) {
    if (CACHE_TTL_SECONDS <= 0) return;
    try {
      com.google.appengine.api.memcache.MemcacheService memcache =
          com.google.appengine.api.memcache.MemcacheServiceFactory.getMemcacheService();
      memcache.put(key, value,
          com.google.appengine.api.memcache.Expiration.byDeltaSeconds(ttlSeconds));
    } catch (Exception ignored) {}
  }

  private static void deleteFromMemcache(String key) {
    try {
      com.google.appengine.api.memcache.MemcacheService memcache =
          com.google.appengine.api.memcache.MemcacheServiceFactory.getMemcacheService();
      memcache.delete(key);
    } catch (Exception ignored) {}
  }
}
