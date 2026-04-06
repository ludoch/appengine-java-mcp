# Copyright 2025 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# ── Build stage ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace

COPY pom.xml .
COPY src ./src

# Download dependencies first (improves layer caching)
RUN apt-get update && apt-get install -y --no-install-recommends maven \
    && mvn dependency:go-offline -q

# Build the WAR (skip tests in Docker build; run them in CI)
RUN mvn package -DskipTests -q

# ── Runtime stage ─────────────────────────────────────────────────────────────
# Use Eclipse Temurin JRE 25 slim for smaller image
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

# Copy the repackaged Spring Boot WAR (executable)
COPY --from=build /workspace/target/appengine-java-mcp-*.war app.war

# App Engine sets PORT to 8080 by default; expose it here for local Docker runs
EXPOSE 8080

ENV PORT=8080

# Run the embedded Jetty server via the Spring Boot WAR launcher
CMD ["java", "-jar", "app.war"]
