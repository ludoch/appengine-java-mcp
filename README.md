# App Engine Java MCP Server

Enable MCP-compatible AI agents to deploy and manage apps on Google App Engine standard environment.

## Tools

Available in all modes:

- `list_services` — Lists all App Engine services in a given project.
- `get_service` — Gets details for a specific App Engine service (URL, traffic split).
- `list_versions` — Lists deployed versions of an App Engine service.
- `get_version` — Gets details for a specific version (runtime, status, creation time).
- `get_service_log` — Gets logs and error messages for a specific App Engine service.
- `deploy_file_contents` — Deploys files to App Engine by providing their contents directly.

Available when running locally only\*:

- `deploy_local_folder` — Deploys a local folder to App Engine.
- `list_projects` — Lists available GCP projects.
- `create_project` — Creates a new GCP project and attaches it to the first available billing account.

_\* Tools that access the local filesystem or require broader project enumeration are restricted to local mode._

## Prompts

- `deploy` — Deploys the current working directory to App Engine. Uses `DEFAULT_SERVICE_NAME` if no service name is given.
- `logs` — Gets the logs for an App Engine service. Uses `DEFAULT_SERVICE_NAME` if no service name is given.

## Environment Variables

| Variable | Description |
| :--- | :--- |
| `GOOGLE_CLOUD_PROJECT` | Default GCP project ID for App Engine operations. |
| `DEFAULT_SERVICE_NAME` | Default App Engine service name (defaults to `default`). |
| `SKIP_IAM_CHECK` | Controls whether the invoker IAM check is enforced. `true` by default (service is publicly accessible). Set to `false` to require authentication. |
| `CACHE_TTL_SECONDS` | Seconds to cache GCP API responses in App Engine Memcache. Defaults to `120`. Set to `0` to disable caching. |
| `GCP_STDIO` | Set to `false` to force HTTP/SSE mode even when no GCP metadata server is detected. |
| `OAUTH_ENABLED` | Set to `true` to enable OAuth token validation on MCP endpoints. |
| `GOOGLE_OAUTH_CLIENT_ID` | Google OAuth client ID (required when `OAUTH_ENABLED=true`). |
| `GOOGLE_OAUTH_CLIENT_SECRET` | Google OAuth client secret (required when `OAUTH_ENABLED=true`). |
| `GOOGLE_OAUTH_AUDIENCE` | Expected OAuth audience. Defaults to `GOOGLE_OAUTH_CLIENT_ID`. |

## Use as a Gemini CLI extension

1. Install the [Google Cloud SDK](https://cloud.google.com/sdk/docs/install) and authenticate:

   ```bash
   gcloud auth login
   gcloud auth application-default login
   ```

2. Install the extension:

   ```bash
   gemini extensions install https://github.com/GoogleCloudPlatform/cloud-run-mcp/tree/main/appengine-java-mcp
   ```

## Use in MCP Clients

Most MCP clients require a configuration file to add an MCP server. Refer to your client's documentation:

- [**Antigravity**](https://antigravity.google/docs/mcp)
- [**Windsurf**](https://docs.windsurf.com/windsurf/mcp)
- [**VS Code**](https://code.visualstudio.com/docs/copilot/chat/mcp-servers)
- [**Claude Desktop**](https://modelcontextprotocol.io/quickstart/user)
- [**Cursor**](https://docs.cursor.com/context/model-context-protocol)

### Set up as a local MCP server

Run the MCP server on your local machine using local Google Cloud credentials. Best suited for AI-assisted IDEs (e.g. Cursor) and desktop AI applications (e.g. Claude Desktop).

**Prerequisites**

1. Install [Java 21+](https://adoptium.net/) and [Maven](https://maven.apache.org/install.html).
2. Install the [Google Cloud SDK](https://cloud.google.com/sdk/docs/install) and log in:

   ```bash
   gcloud auth login
   gcloud auth application-default login
   ```

3. Build the server JAR:

   ```bash
   cd appengine-java-mcp
   mvn package -DskipTests
   ```

**Configure your MCP client** to launch the server in stdio mode:

```json
{
  "mcpServers": {
    "appengine": {
      "command": "java",
      "args": ["-jar", "/path/to/appengine-java-mcp/target/appengine-java-mcp-1.0.0-SNAPSHOT.war", "--stdio"],
      "env": {
        "GOOGLE_CLOUD_PROJECT": "YOUR_PROJECT_ID",
        "DEFAULT_SERVICE_NAME": "default"
      }
    }
  }
}
```

#### Using Docker

0. Install [Docker](https://www.docker.com/get-started/).

1. Build the image:

   ```bash
   cd appengine-java-mcp
   docker build -t appengine-java-mcp .
   ```

2. Configure your MCP client:

   ```json
   {
     "mcpServers": {
       "appengine": {
         "command": "docker",
         "args": [
           "run", "-i", "--rm",
           "-e", "GOOGLE_APPLICATION_CREDENTIALS",
           "-v", "/local-directory:/local-directory",
           "appengine-java-mcp:latest",
           "--stdio"
         ],
         "env": {
           "GOOGLE_APPLICATION_CREDENTIALS": "/Users/you/.config/gcloud/application_default_credentials.json",
           "DEFAULT_SERVICE_NAME": "default"
         }
       }
     }
   }
   ```

### Set up as a remote MCP server

> [!WARNING]
> Do not expose the remote MCP server without authentication. The instructions below use IAM authentication to secure the connection.

Deploy the MCP server to App Engine and connect to it from your local machine, authenticated via IAM.

1. Authenticate and set your project:

   ```bash
   gcloud auth login
   gcloud config set project YOUR_PROJECT_ID
   ```

2. Deploy to App Engine:

   ```bash
   cd appengine-java-mcp
   mvn appengine:deploy
   ```

3. Create a secure tunnel using the IAP TCP forwarding or use `gcloud app browse` to verify the deployment.

4. Configure your MCP client to connect via the App Engine URL:

   ```json
   {
     "mcpServers": {
       "appengine": {
         "url": "https://YOUR_PROJECT_ID.appspot.com/sse"
       }
     }
   }
   ```

   If your client does not support the `url` attribute, use [mcp-remote](https://www.npmjs.com/package/mcp-remote):

   ```json
   {
     "mcpServers": {
       "appengine": {
         "command": "npx",
         "args": ["-y", "mcp-remote", "https://YOUR_PROJECT_ID.appspot.com/sse"]
       }
     }
   }
   ```

## Using OAuth

The MCP server supports OAuth as an authentication mechanism. Copy `.env.example` to `.env` and fill in your OAuth client credentials:

```bash
cp .env.example .env
# edit .env with your GOOGLE_OAUTH_CLIENT_ID, GOOGLE_OAUTH_CLIENT_SECRET, etc.
mvn spring-boot:run
```

### Configure Gemini CLI to use OAuth

When the server is started in OAuth mode, configure Gemini CLI in `~/.gemini/settings.json`:

```json
{
  "mcpServers": {
    "appengine": {
      "httpUrl": "http://localhost:8080/mcp",
      "oauth": {
        "enabled": true,
        "clientId": "<GOOGLE_OAUTH_CLIENT_ID>",
        "clientSecret": "<GOOGLE_OAUTH_CLIENT_SECRET>"
      }
    }
  }
}
```

Then authenticate in the Gemini CLI:

```
/mcp auth appengine
```

## App Engine legacy APIs

This server uses [App Engine bundled services](https://cloud.google.com/appengine/docs/standard/services/bundled) (enabled via `<app-engine-apis>true</app-engine-apis>` in `appengine-web.xml`):

- **Memcache** — Caches GCP API responses (project list, service list) to reduce API quota usage. TTL is controlled by `CACHE_TTL_SECONDS`.
- **Datastore** — Records deployment history in a `McpDeployment` entity kind for auditability.

These APIs are available automatically when running on App Engine standard environment and are gracefully skipped when running locally.

---

The Google Cloud Platform Terms of Service (available at https://cloud.google.com/terms/) and the Data Processing and Security Terms (available at https://cloud.google.com/terms/data-processing-terms) do not apply to any component of the App Engine Java MCP Server software.
