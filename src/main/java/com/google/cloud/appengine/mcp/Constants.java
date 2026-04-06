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

/** Global constants, mirroring constants.js from cloud-run-mcp. */
public final class Constants {

  private Constants() {}

  public static final String SCOPE_OPENID = "openid";
  public static final String SCOPE_EMAIL = "https://www.googleapis.com/auth/userinfo.email";
  public static final String SCOPE_CLOUD_PLATFORM = "https://www.googleapis.com/auth/cloud-platform";

  /** Sentinel value used when ADC (Application Default Credentials) are in use. */
  public static final String GCLOUD_AUTH = "gcloud_auth";

  public static final String MCP_PROTOCOL_VERSION = "2024-11-05";
  public static final String SERVER_NAME = "appengine-mcp";
  public static final String SERVER_VERSION = "1.0.0";
}
