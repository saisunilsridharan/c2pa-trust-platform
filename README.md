# C2PA Trust Portal

React + TypeScript portal, Java 21 Spring Boot API with Swagger UI, and a Rust worker using the maintained C2PA SDK.

## Implemented development workflow

- Initial administrator enrollment, named-account login, and administrator/signer/viewer permissions.
- Local account recovery with forced temporary-password replacement and administrator session revocation.
- UI-managed organization name, signing profile, supported-format selection, upload limit, and AI disclosure requirement.
- Persistent configuration drafts and active configuration, with optimistic revision checks.
- Schema validation before profile activation; activation does **not** enable signing.
- Multipart image/audio/video/PDF provenance inspection through Java and the Rust SDK. Reports contain validation details; a readable manifest is not proof of trusted content.
- UI-created development signing identity with owner-only private-key storage; explicit development-only acknowledgment.
- Image/audio/video/PDF signing with reviewed public creator/title/AI declarations, signed download, validation, and existing provenance preservation.
- UI development certificate status, expiry checks, and rotation.
- Configuration history, revision-protected rollback, and paginated audit records.
- Persistent single-instance signing jobs, batch upload, manual retry, and startup recovery.
- Saved original/signed assets and downloadable validation reports, with per-user ownership checks.
- UI-managed local retention and queue/database/worker diagnostics.
- UI import of password-protected PKCS#12 private CA identities, validated with the actual Rust signing worker.
- Swagger UI documents the APIs and supports the administrator token through its Authorize button.

KMS/HSM, trusted timestamps, public trust-list validation, and externally anchored audit retention remain pending. Local private-CA import is supported, but does not establish public trust or provide hardware key protection. Keys cannot be exported through the API. See [the implementation roadmap](docs/implementation-plan.md).

## Development

Requirements: Java 21, Maven 3.9+, Node 22.12+ or 24, Rust 1.96+, Python 3, OpenSSL CLI, a C compiler and Make for the SDK's vendored OpenSSL build. This cloud workspace has Maven and Rust under `/workspace/tools`; helper scripts use them when absent from PATH. Work in the existing checkout; cloud tasks are already isolated, so do not create a worktree.

From the repository root:

```sh
./scripts/maven.sh package
./scripts/rust.sh build --locked -j 2
cd frontend
npm ci --cache /workspace/tools/npm-cache
npm run build
```

Start the backend from `backend/` so local persistence and worker paths resolve correctly:

```sh
cd backend
java -jar target/portal-api-0.1.0.jar
```

In a separate terminal:

```sh
cd frontend
npm run dev
```

The backend generates `backend/.local/admin-token` with owner-only permissions. Retrieve this local bootstrap credential securely and enter it on the portal's Connect screen. Do not commit or share it. It is authentication, not an application setting. Tokens stay in browser memory and are cleared by Disconnect. The API and development portal bind to loopback by default; this foundation must not be exposed publicly.

Select settings, save the draft, validate it, then activate. In the signing panel, acknowledge development-only use and create an identity. Upload a supported content file, enter a title and creator attribution, select an AI declaration, review the public claims, and sign/download. Inspect the downloaded file in the verification panel. Development certificates expire after 30 days, are not trusted by public trust lists, and use no trusted timestamp. Existing provenance and its metadata are retained as a parent ingredient. Upload supported content containing an embedded C2PA manifest to inspect it. Unsigned or malformed content returns HTTP 422 rather than a false success result.

Swagger UI is served at `/swagger-ui/index.html` on the Java service; OpenAPI is at `/v3/api-docs`. The Vite development server proxies both paths and `/api`. Use `X-Admin-Token` in Swagger's Authorize dialog. Documentation is accessible locally without a token; administration and inspection require one.

## Database configuration

Database connection configuration is the only application setting supplied outside the UI:

- `DB_URL`: JDBC connection URL.
- `DB_USER`: database username.
- `DB_PASSWORD`: database password.

Development defaults to a persistent H2 database in `backend/.local/`. PostgreSQL JDBC support is included but production migrations and PostgreSQL validation are still pending. Automatic schema updates are for development only.

Application settings are stored in the database and survive backend restarts. Flyway manages versioned schema changes; Hibernate validates the resulting schema. Bootstrap authentication survives through its protected local token file. Private-service credentials can be saved through the UI as immutable, write-only AES-256-GCM encrypted records. Platform administrators can download a passphrase-protected encryption-key backup and restore its matching key through the UI. Back up this key and the database together; losing the key makes saved credentials unavailable. The development signing key stays in owner-only `backend/.local/development-identity/`; this file-based development provider is not suitable for production.

## Administration and recovery

The administration panel shows certificate validity, expiry, and SHA-256 fingerprint. Explicit UI rotation switches future signing requests to a new identity. Expired certificates cannot sign. Existing keys/certificates are retained in owner-only version directories under `backend/.local/development-identities/` so in-flight jobs keep a stable pair. The atomic current pointer survives restart. Older installations continue using `backend/.local/development-identity/` until rotation.

Activation and rollback create configuration snapshots. Restoring a version replaces active settings and the draft as a new revision; stale revisions are rejected. Existing active settings are imported as RECOVERED on first access. Versions overwritten before this release cannot be reconstructed.

Audit records cover draft saves, activation, rollback, identity creation/rotation, successful signing, and completed inspection (including invalid integrity results). They exclude tokens, private keys, file contents, and creator declarations. New records identify authenticated users. Historical records keep their original actor. New records use a workspace hash chain; imported historical records are explicitly marked. Export and compare checkpoints through the UI. Queued-job failures are audited. Rejected requests and synchronous process failures are not audited yet. History and audit views support pages of 50 records.

## Checks

```sh
./scripts/maven.sh test
./scripts/rust.sh test --locked -j 2
cd frontend
npm run build
```

Java tests cover access control, revisions, history, rollback, audits, real certificate generation/rotation, restart persistence, expiration, stable in-flight material, and OpenAPI metadata. Worker integration is additionally checked with representative manifest-bearing and malformed files. Run `python scripts/smoke-signing.py` against the running backend to exercise PNG signing, claims, re-signing/provenance preservation, and tampering detection. This smoke test creates a development identity if needed and activates the current draft only when no profile is active; PNG must be enabled. No universal format support or production trust is claimed.

## Named accounts and permissions

Before any users exist, connect using the local bootstrap token under Initial setup and enroll the first administrator in Users and permissions. Enrollment permanently disables the bootstrap token. Sign in with the new username and password thereafter. Administrators can create, disable, and assign ADMIN/SIGNER/VIEWER roles through the UI; the last enabled administrator cannot be disabled or demoted.

Platform ADMIN manages accounts and workspaces. Workspace ADMIN manages its settings, identity, membership, history, and audits and may sign/verify. SIGNER signs and verifies using active settings. VIEWER reads active settings, verifies, and reads its own saved assets. Existing users and data are assigned to the default workspace once. Profiles, identities, jobs, retention and audit history are isolated by workspace. Global ADMIN accounts are platform administrators and can access every workspace; other accounts use their workspace membership roles. Roles and account status are checked on every API request.

Passwords use BCrypt cost 12, require at least 12 characters, and accept at most 72 UTF-8 bytes. Five failed attempts lock a known account for 15 minutes. Session tokens expire after eight hours; only SHA-256 hashes are stored server-side. Tokens remain only in browser memory. Sign out revokes the current token; disabling users blocks their sessions immediately. UI password changes revoke all the account's sessions. Administrator-assisted recovery and active-session counts/revocation are implemented. UI-configured private OIDC, MFA and offline-key recovery are implemented. Broader edge/distributed rate limiting remains pending.

For Swagger UI, call `/api/v1/auth/login` and authorize with its returned session token as `X-Admin-Token`; `Authorization: Bearer` is also accepted. The smoke script's bootstrap-token workflow works only before enrollment. After enrollment, use an authorized account session internally for checks without printing credentials. Production use requires TLS and additional hardening; retain private development access.

## Saved jobs and private identities

Use Saved assets & signing jobs to select multiple supported image, audio, video, or PDF files, review shared creator/title/AI claims and the active profile, and submit a batch. Original files remain separate from signed outputs. Download the stored SDK verification report for completed jobs. A signer sees only their own jobs; administrators see all jobs. Batch requests include the reviewed profile revision and certificate fingerprint; changed configuration returns HTTP 409 before creating a job. Jobs capture immutable manifest and certificate/key paths before processing, support idempotency keys with conflicting-request rejection, and recover RUNNING jobs only after their database lease expires. Failed jobs can be manually retried using their original snapshot. The database coordinates worker leases. Multiple instances require shared asset/identity directories and the same encryption key; workers use separate output directories per attempt.

Local job assets are in `backend/.local/assets/`. Retention defaults to 30 days for completed/failed jobs; administrators can set 1–3650 days through the UI with deletion acknowledgment. Cleanup runs hourly, deleting expired originals, outputs, reports, and job records, and retains queued/running jobs. Back up the database and the entire protected `.local` directory together; database-only backups cannot restore file assets or identity keys.

Administration & recovery accepts a password-protected PKCS#12 bundle (up to 1 MiB) containing exactly one EC P-256 key and a valid matching C2PA-compatible certificate chain. Import checks validity, issuer signatures, signing usage, and key matching, then exercises actual Rust C2PA signing before atomically replacing the active identity. The password/bundle are not persisted; the extracted key is an owner-only local PEM file. This provider requires a private connection or HTTPS, provides no HSM protection or at-rest encryption, and has no trusted timestamp. Imported identities report `private-certificate`; public trust stays unverified. Replacement retains old keys for captured jobs; old key retention currently has no automatic purge. Development rotation intentionally switches back to an untrusted development certificate.

Run `python scripts/smoke-jobs.py` against the running backend before enrollment to check real queue processing, idempotency, conflicting claims, stored downloads/reports, and verification. It retains one generated test asset. After enrollment, adapt the internal smoke helper to use an authorized session without printing it. A signed output passing integrity checks must still be evaluated independently for signer trust.

Administrators can reset another local account through Users and permissions. A reset revokes all sessions, clears the account lockout, and marks the account as requiring a password change. The user signs in with the temporary password and can only read their account, change their password, or sign out until choosing a different password. Share temporary passwords through your secure channel; the portal does not send them. Administrators use the ordinary password-change screen for their own account. Active-session counts and Sign out all sessions are also available in the UI; revoking your own sessions requires signing in again.

## Content format capabilities

The portal supports signing and inspection of JPEG, PNG, WebP, TIFF, WAV, MP3, FLAC, MP4, and PDF. Administrators enable formats through profile settings; existing profiles retain their selected formats until edited. `/api/v1/portal/capabilities` provides MIME types, labels, and download extensions. Upload detection uses file bytes, disregarding the supplied filename and MIME type. MP4 currently accepts tested MP4 brands; HEIC, QuickTime, and audio-only M4A are not automatically treated as MP4 video.

The Rust worker includes the SDK's PDF feature. Basic generated PDF signing, inspection, re-signing and tamper checks pass; encrypted PDFs and existing digital signatures need separate compatibility evaluation. Arbitrary files do not automatically support embedded C2PA credentials. Format parsers still reject malformed or unsupported variants.

For a repeatable multi-format check, enable the nine formats through the UI and run `python scripts/smoke-formats.py`. It generates small fixtures using FFmpeg (a test-only dependency), checks signing, inspection, retained provenance and altered-content detection, and deletes temporary fixtures. A dedicated in-memory database was used for implementation checks so the real active profile was preserved.

## Workspace controls

Use Workspaces to create or rename a workspace, select it, and assign existing accounts ADMIN/SIGNER/VIEWER membership roles. Platform administrators create accounts; workspace administrators cannot manage platform accounts, reset other users' passwords, or view their sessions. An account created while a workspace is selected gets membership in that workspace. Workspace names and the public organization/profile claims are separate settings.

API requests select a workspace with `X-Workspace-Id`. Omission uses the first accessible workspace (the default workspace for platform administrators). Swagger documents this optional header. Membership changes are checked on every request, and removal blocks access without revoking sessions for other workspaces. Workspace administrators must retain an enabled administrator; platform administrators can recover memberships. Platform administrators retain all-workspace access even if their explicit membership is removed. Accounts without any memberships may change their password or sign out, but cannot access content.

Legacy configuration, jobs and audits retain workspace 1. Existing users receive default memberships only during the initial migration; startup never restores removed access. New identities are in protected `backend/.local/workspaces/<id>/development-identities/` directories. The default identity keeps its legacy paths. Job IDs are still UUIDs and assets stay under the protected shared assets directory, with API access checked against workspace and owner. Idempotency keys are scoped to workspace and owner; default-workspace legacy keys remain supported.

The browser holds workspace selection in memory. Pending requests capture their original workspace, preventing a batch or configuration save from moving into a newly selected workspace. Run `cd frontend && npm test` for these client checks. Backend tests and isolated HTTP checks cover membership/role boundaries, configuration/history/rollback isolation, independent identity keys, asset access, audits, operations, and restart persistence.

### Private S3-compatible storage

Save the access key and secret key separately in **Private-service credentials**. In **Private object storage**, choose S3, its HTTPS endpoint, region, existing bucket and object prefix, then select the encrypted credentials. A private CA bundle can be entered in the UI; TLS and hostname verification remain enabled. HTTP is allowed only for an explicitly selected loopback development service. Endpoint checks reject metadata/link-local addresses and URL credentials; the client does not follow redirects. Administrators control outbound destinations; infrastructure egress restrictions remain necessary against DNS rebinding.

Save a draft, run its write/read/delete test and activate that tested version. Saved versions support rollback through the same test and activation controls. Each queued job stores its configuration snapshot, including immutable credential references. Originals, signed assets and verification reports go to that job's provider; switching future jobs to another provider preserves existing downloads. Retention deletes remote assets before local working files and database records. Preserve old buckets, credentials and local working files while their jobs exist.

`python3 scripts/smoke-private-storage.py` starts isolated temporary services, validates AWS SigV4 with an independent protocol fixture, signs PNG through the real Rust worker, checks workspace isolation, downloads after provider changes and restart, and restores a missing encryption key through the API. This is a protocol regression check; a deployment must also test its actual MinIO/S3 service. MinIO downloads were unavailable in the cloud environment.

### API keys, notifications and webhooks

Named users can create personal API keys through the UI, selecting READ, VERIFY and SIGN scopes permitted by their current workspace role, with a 1–365 day lifetime. Tokens are displayed once and stored only as SHA-256 hashes. Use `Authorization: Bearer` for integration requests. API keys cannot administer the portal or select another workspace. Membership removal and account disabling take effect on each request; password changes and assisted recovery revoke keys. Keys can be revoked in the UI.

Job completion persists the outcome, audit event, personal notification and webhook outbox together. The notification panel polls for outcomes, supports pagination and marking notifications read, and retains state after restart. Notifications contain job IDs and outcomes, without public declarations or file contents.

Workspace administrators can configure a private webhook receiver through the UI using an immutable encrypted HMAC credential (at least 32 bytes), HTTPS and an optional private CA bundle. Save, send a connection test and activate; version history supports tested rollback. Loopback HTTP must be explicitly selected for development. Disabling stops new deliveries; queued deliveries keep their original configuration. The delivery panel shows status, attempts and HTTP result and allows retrying exhausted failures. Retries use exponential delay and a configurable 1–8 attempt limit.

Receivers must verify `X-C2PA-Signature` as `sha256=` plus hexadecimal HMAC-SHA256 over UTF-8 `X-C2PA-Timestamp + "." + raw request body`, reject old timestamps and deduplicate `X-C2PA-Delivery-Id`. Delivery is at least once; a crash after receiver acceptance can repeat the same ID and payload with a fresh timestamp/signature. Redirects are rejected. Payloads contain type, deliveryId, jobId, attempt, workspaceId and createdAt; they exclude credentials and creator claims. Failed receiver bodies are not retained.

### Database migrations, worker coordination and audit integrity

Flyway creates the schema on empty databases and applies an additive version-1 migration to pre-Flyway portal databases after baseline version 0. Legacy profile drafts, active settings, jobs, key paths, audit actors and retention values are preserved. Back up the database and protected `.local` files before upgrading; use a dedicated portal database. Future schema changes require new migration files; do not edit an applied migration. Hibernate now validates the schema instead of changing it automatically.

Database row locks and expiring leases coordinate job claims. Active leases survive another application's startup. Stale workers cannot complete a later attempt; local and remote outputs are separated by attempt. The UI configures a 10–120 second worker timeout and 1–100 maximum attempts for future jobs. Interrupted jobs recover after their lease expires and fail with a persistent notification when their attempt limit is exhausted. This requires common storage and identity paths across instances and does not provide an isolated distributed worker pool.

Audits use SHA-256 chains over length-prefixed UTF-8 fields, ordered per workspace. Audit integrity administration verifies the chain, exports a checkpoint and compares a previously saved checkpoint. Store checkpoints in a trusted location outside the portal: a database administrator can rewrite an entire unanchored chain. Legacy records are marked as imported; hashing them does not establish their original authenticity. External immutable audit storage remains future work.

The isolated private-storage regression also exercises migrations, leases, processing settings and checkpoint comparison. H2 and PostgreSQL 17.11 pass the complete real Java/Rust workflow, restart persistence and encryption-key recovery checks. To test PostgreSQL independently, run `python3 scripts/smoke-private-storage.py --postgres-bin /workspace/tools/postgres/usr/lib/postgresql/17/bin`; it creates and stops a temporary password-protected cluster. PostgreSQL binaries here were extracted from signed Debian packages; their installation is optional for normal H2 development.

### Authenticator MFA and self-service recovery

Account security administration enrolls a six-digit, 30-second SHA-1 TOTP authenticator after current-password verification, confirms possession before activation and displays eight single-use backup codes once. TOTP replay is rejected, including reuse of the enrollment confirmation code. Sign in with a fresh authenticator code or an unused backup code. Enabling/disabling MFA revokes all sessions and API keys; disabling also requires second-factor verification. Failed password/code checks share the account lockout.

Account recovery keys are generated through the UI after password verification and, when enabled, MFA. Each key is shown once, hashed in the database and replaces the previous key. The sign-in screen can recover a forgotten password using that key, plus an authenticator or unused MFA backup code when MFA is enabled. Recovery consumes the key and second-factor backup code, replaces the password and revokes all sessions/API keys. Disabled accounts cannot recover. A lost recovery key still requires administrator-assisted recovery. Store all recovery credentials offline.

TOTP secrets use purpose-bound encrypted credentials in a separate account namespace; integration APIs cannot list or consume them. The existing encryption-key backup/restore protects these secrets too. V2 migrates existing integration credentials without changing their values. Real H2/PostgreSQL regression flows exercise MFA activation, encrypted persistence, recovery-code consumption, account recovery, restart and protected-key restoration.

### Private OIDC login

Platform administrators configure an authorization-code provider, client ID, optional encrypted client secret, exact `/oidc/callback` URL and private TLS CA through the UI. Save, test discovery and signing keys, then activate a version; history supports rollback. The discovery test does not validate a client registration or complete a provider login. Existing portal accounts must be explicitly linked to the exact issuer and subject. Provider email and role claims do not grant membership or permissions. Local password login and recovery remain available.

The backend performs PKCE S256, browser-bound state, single-use callbacks, nonce, RS256 signature, issuer, audience and token lifetime validation. Local MFA and account lockouts apply to linked logins. Login tokens stay in browser memory; callback URLs are cleared before completion. Provider endpoints must share the issuer origin, use HTTPS and pass destination checks; loopback HTTP requires explicit development configuration. Expired transaction proofs are removed after a grace period. Protocol tests cover successful login, incorrect browser binding, replay, wrong issuer/audience/nonce, expiry, forged signatures, unlinked subjects and disabled accounts. An actual Keycloak or other deployed provider still needs an end-to-end registration/login test.

### Selectable approved signing choices

Workspace administrators publish a named snapshot of the active profile and certificate through the UI. Publish several combinations by activating a different profile or importing/rotating its identity and publishing again. Each choice retains its profile fields, revision and certificate reference; key paths are never returned by the API. Users select a choice for immediate or batch signing, review its public declarations and certificate fingerprint, then authorize. The workspace default remains available.

Withdrawal prevents new requests from selecting that choice. Captured jobs and exact idempotent retries retain their original approved snapshot. Choices with expired, missing or changed certificates cannot sign. Stale reviews and cross-workspace references are rejected; using the same idempotency key with another choice returns a conflict. Re-enable valid choices through the UI. H2 and PostgreSQL regression flows cover profile changes, certificate rotation, original-certificate signing, withdrawal, stale reviews, isolation and idempotency. Certificate storage remains local and public trust remains unverified.
