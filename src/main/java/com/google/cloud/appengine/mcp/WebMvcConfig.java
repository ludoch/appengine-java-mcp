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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.appengine.mcp.middleware.OAuthInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Spring MVC configuration: registers OAuth interceptor and shared beans. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  private final ObjectMapper objectMapper;

  public WebMvcConfig(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void addInterceptors(@NonNull InterceptorRegistry registry) {
    registry
        .addInterceptor(new OAuthInterceptor(objectMapper))
        .addPathPatterns("/mcp", "/messages", "/sse");
  }

  @Bean
  public com.google.cloud.appengine.mcp.tools.ToolRegistry toolRegistry() {
    return com.google.cloud.appengine.mcp.tools.ToolRegistry.createDefault();
  }
}
