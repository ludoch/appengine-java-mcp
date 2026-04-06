# Security Policy

## Reporting a vulnerability

To report a security issue, please use [g.co/vulnz](https://g.co/vulnz).

The Google Security Team will respond within 5 working days of your report on
[g.co/vulnz](https://g.co/vulnz).

We use [g.co/vulnz](https://g.co/vulnz) for intake, and coordinate disclosure
here using GitHub Security Advisories to privately discuss and fix the issue.

## Security considerations when deploying this server

### Authentication

By default the server is deployed with `SKIP_IAM_CHECK=true`, making the App
Engine service publicly accessible. For production deployments you should set
`SKIP_IAM_CHECK=false` and enforce authentication through one of these
mechanisms:

- **IAM** — Restrict invocations to specific service accounts or user identities
  using App Engine's built-in IAM. See the
  [App Engine access control documentation](https://cloud.google.com/appengine/docs/standard/java-gen2/access-control).
- **OAuth** — Enable `OAUTH_ENABLED=true` and supply Google OAuth client
  credentials via environment variables (see `.env.example`). The server will
  validate `Authorization: Bearer <token>` headers on all MCP endpoints before
  dispatching tool calls.

### Principle of least privilege

The service account associated with the App Engine service should be granted
only the permissions it needs:

| GCP API used | Minimum required role |
| :--- | :--- |
| App Engine Admin API | `roles/appengine.deployer` |
| Cloud Storage (staging bucket) | `roles/storage.objectAdmin` on the staging bucket |
| Cloud Logging | `roles/logging.viewer` |
| Resource Manager (project listing) | `roles/resourcemanager.projectViewer` |
| Service Usage (enabling APIs) | `roles/serviceusage.serviceUsageAdmin` |
| Cloud Billing (attach billing) | `roles/billing.user` |

Avoid using `roles/editor` or `roles/owner` on the service account.

### Network exposure

- Deploy the App Engine service with appropriate
  [ingress settings](https://cloud.google.com/appengine/docs/standard/java-gen2/reference/app-yaml#network)
  to restrict which traffic can reach the server (e.g. internal-only for
  corporate use cases).
- Consider using
  [VPC Service Controls](https://cloud.google.com/vpc-service-controls/docs/overview)
  to restrict API access to approved networks.

### Secrets

- Do not commit `.env` files or service account key files to version control.
- Prefer [Secret Manager](https://cloud.google.com/secret-manager/docs) or
  App Engine environment variables (set via `gcloud app deploy --set-env-vars`)
  over plaintext `.env` files in production.

### App Engine legacy APIs

This server uses `<app-engine-apis>true</app-engine-apis>` to access Memcache
and Datastore. Access to these APIs is scoped to the same GCP project and
cannot be reached from outside the App Engine standard environment sandbox.
No additional IAM configuration is needed for the legacy APIs themselves.

### Dependency management

Keep Maven dependencies up to date. Run the following to check for outdated
versions:

```bash
mvn versions:display-dependency-updates
```

Subscribe to [GitHub security advisories](https://github.com/GoogleCloudPlatform/cloud-run-mcp/security/advisories)
for this repository to receive notifications of known vulnerabilities in
dependencies.
