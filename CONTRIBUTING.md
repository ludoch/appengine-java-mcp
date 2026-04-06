# How to contribute

We'd love to accept your patches and contributions to this project.

## Before you begin

### Sign our Contributor License Agreement

Contributions to this project must be accompanied by a
[Contributor License Agreement](https://cla.developers.google.com/about) (CLA).
You (or your employer) retain the copyright to your contribution; this simply
gives us permission to use and redistribute your contributions as part of the
project.

If you or your current employer have already signed the Google CLA (even if it
was for a different project), you probably don't need to do it again.

Visit <https://cla.developers.google.com/> to see your current agreements or to
sign a new one.

### Review our community guidelines

This project follows
[Google's Open Source Community Guidelines](https://opensource.google/conduct/).

## Contribution process

### Start with an issue

Before sending a pull request, please open an issue describing the bug or feature
you would like to address. This allows maintainers to guide your design and
implementation before you invest significant time.

### Code reviews

All submissions, including submissions by project members, require review. We
use GitHub pull requests for this purpose. Consult
[GitHub Help](https://help.github.com/articles/about-pull-requests/) for more
information on using pull requests.

## Development

### Prerequisites

- Java 21 or later (Java 25 recommended to match the App Engine target runtime)
- Maven 3.9+
- [Google Cloud SDK](https://cloud.google.com/sdk/docs/install)
- A GCP project with App Engine enabled (for integration tests)

### Build

```bash
cd appengine-java-mcp
mvn compile
```

### Run unit tests

Unit tests use the App Engine local service helpers and do not require real GCP credentials:

```bash
mvn test
```

### Run the server locally (HTTP mode)

The embedded Jetty server starts on port 8080 by default:

```bash
mvn spring-boot:run
# or
mvn package -DskipTests && java -jar target/appengine-java-mcp-*.war
```

The MCP endpoints are available at:

- `POST http://localhost:8080/mcp` — Streamable HTTP transport
- `GET  http://localhost:8080/sse` — Legacy SSE transport
- `POST http://localhost:8080/messages?sessionId=<id>` — Legacy SSE message posting

### Run the server locally (stdio mode)

Useful when connecting directly from an MCP client such as Claude Desktop:

```bash
java -jar target/appengine-java-mcp-*.war --stdio
```

### Using MCP Inspector

[MCP Inspector](https://github.com/modelcontextprotocol/inspector) lets you interactively browse and call tools against a running server.

1. Start the server in HTTP mode (see above).
2. In a second terminal:

   ```bash
   npx @modelcontextprotocol/inspector http://localhost:8080/mcp
   ```

3. Open <http://localhost:6274/> in your browser.

### Using a real MCP client

Configure your client for **local stdio**:

```json
{
  "mcpServers": {
    "appengine": {
      "command": "java",
      "args": ["-jar", "/path/to/appengine-java-mcp/target/appengine-java-mcp-1.0.0-SNAPSHOT.war", "--stdio"]
    }
  }
}
```

Configure your client for **local HTTP**:

```json
{
  "mcpServers": {
    "appengine": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

Or, if your client does not support the `url` attribute:

```json
{
  "mcpServers": {
    "appengine": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8080/mcp"]
    }
  }
}
```

### Deploy to App Engine for testing

```bash
# Ensure gcloud is pointing at your test project
gcloud config set project YOUR_TEST_PROJECT_ID

mvn appengine:deploy
```

The App Engine Maven plugin reads the runtime and configuration from
`src/main/webapp/WEB-INF/appengine-web.xml`.

## Testing

### Unit tests (no GCP required)

```bash
mvn test
```

Uses the App Engine local service test helpers (`appengine-testing`, `appengine-api-stubs`)
to provide in-process Memcache and Datastore stubs so legacy API code can be exercised
without a live App Engine environment.

### Integration tests (GCP required)

Set the `GOOGLE_CLOUD_PROJECT` environment variable to a real project before running:

```bash
export GOOGLE_CLOUD_PROJECT=your-test-project
mvn verify -Pintegration
```

## Project structure

```
appengine-java-mcp/
├── pom.xml                                   # Maven build (WAR + App Engine plugin)
├── src/main/java/com/google/cloud/appengine/mcp/
│   ├── AppEngineMcpApplication.java          # Entry point (stdio + WAR init)
│   ├── McpController.java                    # HTTP endpoints (POST /mcp, GET /sse, …)
│   ├── StdioMcpServer.java                   # Stdio transport loop
│   ├── WebMvcConfig.java                     # Spring MVC + interceptor wiring
│   ├── Constants.java                        # Global constants
│   ├── tools/ToolRegistry.java               # Tool registration and dispatch
│   ├── cloud/
│   │   ├── AppEngineApiService.java          # App Engine Admin API (services, versions)
│   │   ├── ProjectsApiService.java           # Resource Manager API (projects)
│   │   ├── LoggingApiService.java            # Cloud Logging API
│   │   ├── AuthService.java                  # ADC / OAuth credentials
│   │   └── MetadataService.java              # GCE metadata server probe
│   ├── deployment/
│   │   ├── AppEngineDeployer.java            # Deploy source → GCS → App Engine version
│   │   └── DeploymentConstants.java          # Deployment configuration constants
│   └── middleware/OAuthInterceptor.java      # OAuth token validation
├── src/main/webapp/WEB-INF/
│   ├── appengine-web.xml                     # Runtime=java25, app-engine-apis=true
│   └── web.xml                               # Servlet configuration
└── src/test/…
```

## Releasing

1. Bump the version in `pom.xml`:

   ```bash
   mvn versions:set -DnewVersion=1.1.0
   ```

2. Run tests and build:

   ```bash
   mvn clean verify
   ```

3. Commit, tag, and push:

   ```bash
   git commit -am "chore: release 1.1.0"
   git tag v1.1.0
   git push --follow-tags
   ```

4. Create a [GitHub release](https://github.com/GoogleCloudPlatform/cloud-run-mcp/releases/new) for the tag. Attach the WAR artifact from `target/`.
