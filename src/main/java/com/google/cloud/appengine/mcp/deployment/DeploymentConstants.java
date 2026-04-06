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
package com.google.cloud.appengine.mcp.deployment;

import java.util.List;

/** Deployment configuration constants, mirroring lib/deployment/constants.js. */
public final class DeploymentConstants {

  private DeploymentConstants() {}

  public static final String BUCKET_SUFFIX = "-appengine-mcp-source";
  public static final String ZIP_FILE_NAME = "source.zip";
  public static final String LABEL_CREATED_BY = "appengine-mcp";

  /** Default App Engine runtime for new deployments. */
  public static final String DEFAULT_RUNTIME = "java21";

  /** Default serving environment. */
  public static final String DEFAULT_ENV = "standard";

  /** Required GCP APIs for source deployments. */
  public static final List<String> REQUIRED_APIS_SOURCE = List.of(
      "serviceusage.googleapis.com",
      "appengine.googleapis.com",
      "cloudbuild.googleapis.com",
      "storage.googleapis.com");

  /** Required GCP APIs for all App Engine operations. */
  public static final List<String> REQUIRED_APIS_BASE = List.of(
      "serviceusage.googleapis.com",
      "appengine.googleapis.com");

  /** Maximum source archive size (250 MiB). */
  public static final long MAX_SOURCE_SIZE_BYTES = 250L * 1024 * 1024;
}
