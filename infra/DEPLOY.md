# Development server deployment

Requires Docker with the Compose plugin. Build on the destination machine so native dependencies match the runtime image. Development certificates are not publicly trusted; this remains a development portal.

From the project root:

```sh
docker compose -f infra/compose.yaml up -d --build
```

The web service listens on the server's loopback port 5173. The Java API is reachable only inside the container network. Nginx serves the React build, including the `/login` fallback, and proxies the API and Swagger UI.

Use an SSH tunnel for remote testing:

```sh
ssh -L 5173:127.0.0.1:5173 root@169.58.111.229
```

Then open port 5173 locally in your browser. Add `-p <port>` if SSH uses a different port. Retrieve the administrator token privately from the API container at `/app/backend/.local/admin-token`; do not publish it in logs or messages. Application configuration and development identity creation remain UI-managed.

The named volume preserves the development H2 database, bootstrap token, and development signing identity. Do not remove the volume when upgrading. Back up this volume before changes. Production PostgreSQL, TLS, access control, KMS/HSM, and other administration capabilities remain pending. Public HTTP exposure is not enabled in this configuration.

Check the portal, Swagger UI, and `/api/v1/health` through the web service, then complete a JPEG/PNG signing and verification test. Startup alone does not establish functional readiness.
