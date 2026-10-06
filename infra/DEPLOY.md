# Container deployment

Install Docker with Compose on the destination host. From the repository root:

```sh
docker compose -f infra/compose.yaml up -d --build
```

Only database connection settings use environment variables: `DB_URL`, `DB_USER`,
and `DB_PASSWORD`. The default is persistent H2; PostgreSQL uses these same three
settings. Configure providers, credentials, identities, private CA issuance and
renewal, trust, CRLs, timestamps, storage, OIDC, webhooks and worker budgets in the
administrator UI. Test provider drafts before activation.

Containers run as non-root users with read-only root filesystems and dropped
capabilities. The API health check gates web startup. The persistent `.local`
volume contains the H2 database, encrypted credentials, encryption key, bootstrap
credential and local artifacts. Back up the complete volume before upgrading;
do not remove it. PostgreSQL additionally needs a consistent database backup.
Protect backups as secrets.

The web listener binds host loopback port 5173. Use a private SSH tunnel or a
separately configured HTTPS reverse proxy. Configure trusted proxy CIDRs and
login budgets in the UI for the actual proxy chain. Nginx forwards the client
chain, limits authentication requests, and serves React routes, API and Swagger.

Read `/app/backend/.local/admin-token` privately inside the API container for
initial enrollment. Do not publish it. Enroll an administrator and then use
account login. Development identities do not establish public C2PA trust.

Cloud environments with an injected HTTPS proxy and trusted proxy CA can use:

```sh
python3 scripts/build-container-images.py --tag local
```

This produces `c2pa-portal-api:local` and `c2pa-portal-web:local`; Compose otherwise
builds its own project image names. The helper passes proxy bindings and trusted
build certificates using temporary BuildKit secrets. They are not embedded in
runtime images. Private runtime TLS trust is configured separately in provider UI.

After deployment check health, login, Swagger, enrollment, signing and inspection.
Test sandbox capability through the UI before selecting namespace isolation:
container policy may prohibit nested namespaces. The application rejects
unavailable isolation instead of silently falling back. Native resource limits
are supplied by `prlimit`.

Acceptance against real providers requires their actual endpoints and credentials.
Remote deployment requires reachable SSH or another authorized deployment channel.
A local build does not establish either condition.


## Separate remote worker hosts

Deploy the API/web pair on each trusted agent host with a separate database and
persistent volume. Do not share hub encryption keys or copy HSM PINs to agents.
Use the hub Remote workers UI to create an expiring pairing credential; enter it
as an encrypted credential in the agent UI with the hub HTTPS URL and optional
public TLS CA. Test/start the agent, run the hub hardware probe, activate the
connection, and enable hardware-only queued routing. Keep the hub reachable from
agents and retain native resource/isolation prerequisites on each host. Agent
hosts are trusted members of the signing boundary. Revocation/rotation invalidates
old credentials and in-flight publication authority.

Official public inspection also uses UI settings. Allow verified HTTPS access to
raw.githubusercontent.com for the fixed C2PA conformance lists, or configure a
trusted HTTP CONNECT proxy and public CA bundle through the UI. Fetch/test/activate
the lists and refresh before their cache expiry. Public online revocation is not
checked; this deployment does not claim product certification.
