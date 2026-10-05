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

Private PKCS#11 signing is implemented and tested with SoftHSM. Vendor HSM/private-CA acceptance and official public trust-list validation remain pending. UI-configured Smallstep-compatible private issuance and explicitly authorized scheduled renewal are implemented. Token-signed CSR download and same-key certificate replacement are implemented. UI-configured private S3 Object Lock audit anchoring is implemented and protocol-tested. Private RFC 3161 timestamping is implemented and independently OpenSSL-tested. Local private-CA import is supported, but does not establish public trust or provide hardware key protection. Keys cannot be exported through the API. See [the implementation roadmap](docs/implementation-plan.md).

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

Development defaults to a persistent H2 database in `backend/.local/`. PostgreSQL JDBC support and versioned Flyway migrations are implemented and tested against a disposable PostgreSQL 17 cluster. Hibernate validates the migrated schema.

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

Passwords use BCrypt cost 12, require at least 12 characters, and accept at most 72 UTF-8 bytes. Five failed attempts lock a known account for 15 minutes. Session tokens expire after eight hours; only SHA-256 hashes are stored server-side. Tokens remain only in browser memory. Sign out revokes the current token; disabling users blocks their sessions immediately. UI password changes revoke all the account's sessions. Administrator-assisted recovery and active-session counts/revocation are implemented. UI-configured private OIDC, MFA and offline-key recovery are implemented. UI-configured shared database authentication rate limiting is implemented. Broader network-edge protections remain deployment work.

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

Database row locks and expiring leases coordinate job claims. Active leases survive another application's startup. Stale workers cannot complete a later attempt; local and remote outputs are separated by attempt. The UI configures a 10–120 second worker timeout, 1–100 maximum attempts, 256–4096 MiB memory limit, 10–120 CPU seconds and execution mode for future jobs. Each job captures these settings. Linux `prlimit` is required. LIMITED mode applies resource limits; NAMESPACE additionally uses bubblewrap with isolated user/PID/network namespaces, a read-only system and explicit read-only input/manifest/certificate/policy bindings. Only the attempt output directory and bounded temporary storage are writable; approved HSM/TSA operations use a private callback socket outside the worker. Worker environment variables are cleared. Save NAMESPACE only after the UI host-capability probe succeeds; it never silently falls back to LIMITED. Install bubblewrap and allow user namespaces on every worker host. Managed test shells may restrict namespace creation even when the host supports it. Interrupted jobs recover after their lease expires and fail with a persistent notification when their attempt limit is exhausted. This requires common storage and identity paths across instances and does not provide an isolated distributed worker pool.

Audits use SHA-256 chains over length-prefixed UTF-8 fields, ordered per workspace. Audit integrity administration verifies the chain, exports a checkpoint and compares a previously saved checkpoint. Store checkpoints in a trusted location outside the portal: a database administrator can rewrite an entire unanchored chain. Legacy records are marked as imported; hashing them does not establish their original authenticity. The immutable audit-storage UI can retain checkpoints externally using private S3 Object Lock COMPLIANCE mode, with version-specific receipts and durable retries.

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

### Private PKCS#11 signing

Hardware identities are configured through UI drafts: installed module path, slot-list index, key alias, encrypted workspace PIN credential and matching public certificate chain. Modules must be root-owned, protected libraries under `/usr/lib`, `/usr/local/lib` or `/opt`; the extracted development SoftHSM module is explicitly supported. Native module/token provisioning is infrastructure work. The adapter currently supports EC P-256/ES256 keys and validates certificate chain, validity, signing purpose and key matching. Provision production token keys as sensitive and non-extractable using your vendor's controls.

Run a real Rust C2PA signing probe before approving an identity with the active profile. Immutable identity versions and signing choices capture certificate and credential references; replacement creates a new draft/choice. Existing choices can be withdrawn or re-enabled. The UI/API never receives a production private key. Rust receives a public certificate and a private local socket; a bounded, isolated Java process accesses PKCS#11 and receives the PIN through stdin. PINs are absent from process arguments. Key export through the provider is rejected, and returned signatures are checked against the uploaded certificate. Local socket directories are owner-only.

The full H2 and PostgreSQL flows pass with a real non-exportable SoftHSM token after deleting the original disposable fixture PEM key. Regression also covers test-before-approval, workspace boundaries, captured choices, queued native signing, restart and encrypted-PIN recovery. Run `python3 scripts/smoke-private-storage.py --softhsm-dir /workspace/tools/softhsm` (optionally with `--postgres-bin`); it provisions and removes only a disposable test token. This test imports a disposable fixture key; production token generation, issuance/renewal, actual vendor hardware and public trust remain separate acceptance work.

### Workspace private trust policy

Administrators configure private CA anchors and optional strict trusted signing through the UI. Save immutable versions, test each with a signed sample, activate it, and roll back by testing/activating an earlier version. Unrelated/invalid CA samples fail strict tests. Policies are workspace-scoped, and existing jobs retain the policy captured at submission. SDK networking, OCSP fetching and remote-manifest fetching are disabled in this embedded-content mode.

Rust uses the captured anchors for signing validation and inspection. Strict signing removes outputs that do not validate as `Trusted`; integrity failures also fail. Reports include `portal_trust_policy` with its version/source and `publicTrustVerified: false`. A `Trusted` result under organization anchors establishes private policy trust, not membership in an official public C2PA trust list. Signed declarations capture the private policy version. Official public trust-list distribution and revocation fetching remain outstanding. Private timestamping is implemented as described below. Real H2/PostgreSQL/SoftHSM flows cover correct anchors, wrong-CA rejection, test-before-activation, workspace isolation and policy snapshots across later configuration changes.

### Private RFC 3161 timestamps

The UI configures immutable TSA versions: endpoint, optional encrypted bearer credential, private TLS CA and separate TSA signing CA anchors. Test a real timestamped C2PA signature using a selected approved identity, then activate. History supports rollback, including a tested disabled version. HTTPS and destination checks apply; HTTP is allowed only for explicitly enabled loopback development. Responses are bounded to 64 KiB with a deadline, and redirects are rejected.

Java forwards timestamp requests over authenticated/private HTTP; Rust uses a private local socket and has no network access through the SDK. Request nonce and message imprint must match. Rust independently validates the timestamp signature and private TSA certificate trust. When enabled, invalid, unavailable or untrusted timestamps fail signing and remove the output. Signed declarations and reports identify the captured provider version. This is private TSA trust, not official public trust-list membership. Inspection reports distinguish private timestamp trust from content integrity.

The full H2/PostgreSQL/SoftHSM flows pass against an independent OpenSSL RFC 3161 service. Regression rejects malformed replies, oversized bodies, redirects, nonce mismatch, message-imprint mismatch and wrong TSA anchors; verifies test-before-activation and workspace isolation; and preserves a queued job's provider after timestamping is disabled for future jobs. Use `--timestamps` with the isolated private-storage smoke script. An actual deployed TSA still needs its own compatibility/availability check. OIDC response parsing also uses the bounded whole-body HTTP handler.

Worker sandbox verification uses disposable fixtures: `python3 scripts/smoke-private-storage.py --softhsm-dir /workspace/tools/softhsm --timestamps --namespace --formats`. In this cloud environment namespace tests require an execution context that permits user namespaces. H2 and PostgreSQL end-to-end namespace signing pass; all nine embedded formats pass software-HSM signing, inspection, re-signing and tamper rejection inside the sandbox. This does not establish arbitrary-file support, complex PDF compatibility or independent public trust.

## Protected external audit checkpoints

Workspace administrators configure a separate private S3 Object Lock bucket through Immutable audit storage: endpoint, region, bucket, prefix, encrypted access/secret credential IDs, TLS CA, retention days and scheduled interval. Save immutable versions, explicitly acknowledge COMPLIANCE retention, test and activate; tested history supports rollback and disable. The test creates a small retained probe, checks bucket versioning/Object Lock, verifies COMPLIANCE retention and exact version/payload, and requires protected-version deletion to be refused. Retained probes and checkpoints cannot be deleted before their retention date. Do not use the ordinary asset-retention bucket for checkpoints.

Manual and scheduled exports verify the audit chain before creating a durable outbox checkpoint. Provider configuration and the exact checkpoint are captured before delivery; switching or disabling future writes preserves pending deliveries. Failed writes retry with bounded exponential delays and eight attempts, then allow UI retry using the original snapshot. Confirmed receipts include the bucket, key, version ID, retain-until date, payload SHA-256 and original audit checkpoint. Download and keep receipts outside the portal database. The UI can fetch the protected version again and verify retention and payload; use the included checkpoint in Audit integrity to compare the database chain. Independent bucket administration and the provider's implementation remain trust assumptions.

The isolated `smoke-private-storage.py --audit-lock` option tests an independent authenticated S3 Object Lock protocol fixture, including missing versioning, incorrect retention, successful deletion, changed payload, cross-workspace denial, captured configuration, durable receipts and restart recovery. Actual MinIO/vendor acceptance still requires testing your configured bucket through the UI; this fixture is not a vendor certification.

## Hardware certificate requests and replacement

The hardware-identity UI displays expiry, downloads a reviewed token-signed PKCS#10 CSR, and accepts a replacement public certificate/issuer chain. Requests include digital-signature usage, C2PA signing usage and the email-protection EKU supported by the current SDK validation path. Your CA controls certificate policy and issuance; CSR extensions are requests, not an issuance guarantee. Generate CSRs before the selected certificate expires. The CSR contains public key/subject data and proof of possession, never the private key.

A replacement must use the same token public key, carry a valid C2PA-compatible chain and differ from the old certificate. The portal commits a separate immutable identity draft before running the callback-based SDK probe, then records successful testing. An unavailable token or failed SDK probe preserves a draft for later testing. Approval with a profile is separate; existing choices/jobs retain their original certificate. Expired certificates cannot sign new content. For a new token key, configure a new hardware identity instead. Automatic CA-specific issuance/renewal still requires a selected private CA integration.

The disposable `--certificates` smoke option independently verifies CSRs using OpenSSL, issues a replacement chain, rejects mismatched/unchanged certificates, checks retry after token unavailability, signs with the replacement and verifies original-job/restart persistence.

## Shared authentication request limits

Platform administrators configure the time window, per-client-address budget, global budget and trusted reverse-proxy CIDRs through Shared login rate limits. Defaults allow 30 requests per address and 1000 globally in 60 seconds. Password login, offline recovery, enrollment and OIDC start/completion share this budget across instances using the same database. Account lockout remains separate. Successful and failed attempts count; throttled clients do not consume other clients' remaining global budget. HTTP 429 includes Retry-After, which login/recovery screens display. Database counter failure refuses authentication with HTTP 503.

Forwarded addresses are ignored unless the direct peer belongs to a configured trusted CIDR. The service walks X-Forwarded-For from the nearest hop toward the client and stops at the first untrusted address. Invalid, excessive or duplicate forwarded headers fall back to the direct peer. Configure only proxies you control and require them to sanitize/append headers correctly. IPv4/IPv6 literals are normalized without hostname lookup, and stored client counter keys are SHA-256 digests rather than raw addresses. Shared NAT clients use one budget. Keep application clocks synchronized; limits use fixed windows and complement deployment edge controls rather than providing network-level DDoS protection.

Counter admission is serialized by a shared database row lock, including creation of new client buckets. Inactive buckets are cleaned after ten minutes and the table has a fixed 50,000-client bound. UI policy changes preserve existing counters and apply immediately; existing authenticated administrators can update policy even when login budgets are exhausted. `smoke-private-storage.py --rate-limits` tests UI changes/stale revisions, trusted-proxy handling, unknown-account throttling, shared OIDC/recovery budgets and restart persistence. Java concurrency tests exercise separate limiter instances against shared storage.

## Private CA issuance and scheduled renewal

Workspace administrators configure a Smallstep-compatible `/sign` endpoint, provisioner name, encrypted ES256 private JWK credential reference, TLS trust, issuance CA anchors and requested validity through the UI. Use a dedicated C2PA provisioner/template that issues EC P-256 certificates with digital-signature usage, email-protection/C2PA EKUs and exactly the reviewed DNS subject/SAN. The default web-server template is unsuitable. Tests issue a real certificate and exercise native C2PA signing before activation; manual issuance produces a separate identity for review and approval.

Scheduled plans require explicit UI authorization to request certificates, publish tested replacements and withdraw preceding choices. Plans pin the activated issuer, hardware key, reviewed subject and approved profile. Renewal windows must be shorter than certificate validity. Database leases coordinate bounded background work and retries. Pausing, withdrawing/revising the choice, or changing/disabling the issuer prevents replacement publication. Existing jobs retain their captured certificates. Expired source certificates require administrator recovery rather than automatic renewal. Failed issuance retains safe drafts for review where applicable.

`python scripts/smoke-private-storage.py --private-ca --softhsm-dir /workspace/tools/softhsm --namespace` verifies independently signed provisioner tokens, CSR binding, pinned trust, wrong-template/key/subject/SAN rejection, actual token signing, scheduled publication and pause during issuance. Add `--postgres-bin /workspace/tools/postgres/usr/lib/postgresql/17/bin` for disposable PostgreSQL coverage. The OpenSSL protocol fixture does not establish acceptance of an actual deployed Smallstep CA or vendor HSM. Online/public revocation and official public trust remain separate work.

## Private CRL revocation enforcement

Workspace administrators can upload trusted CRL issuer certificates and complete signed PEM CRLs in the Private certificate revocation UI. Save an immutable draft, test an approved choice against an explicitly expected allowed/revoked result, then acknowledge enforcement and activate. History permits reviewed replacement or disabling. Every uploaded issuer must be a current CA with CRL-signing usage and have one current signature-verified complete CRL. Duplicate, delta, scoped/indirect, unsupported-critical-extension and weak-signature CRLs are rejected. Only public certificate/CRL PEM blocks are accepted; private-key blocks and weak issuer keys are rejected. Upload limits are 60,000 characters of issuer certificates and 400,000 characters of CRLs, with at most 50 each.

Enabled enforcement rejects revoked manifest-signing leaf/intermediate certificates and stale configured CRLs. Requiring coverage also rejects certificates whose issuer is absent from the policy; coverage is evaluated for all non-root certificates. When coverage is optional, other issuers may sign, but configured CRLs must stay current. Self-signed trailing roots are trust anchors and are not checked as revoked signing certificates. This is a private issuer policy, separate from certificate-chain trust validation.

Immediate signing, queued jobs and readiness probes check the **current** revocation policy, before native work, at HSM callbacks and before publishing output. Job snapshots cannot bypass an emergency revocation. Existing downloaded content and stored verification reports remain unchanged; embedded SDK inspection does not perform these CRL checks. Timestamp-authority and ingredient certificate revocation are not covered by this policy. Signers can see freshness/enforcement status in immediate and batch signing panels. CRL refresh is manual through the UI before `nextUpdate`; no online CRL/OCSP fetching, official public trust or universal revocation coverage is claimed.

The disposable `--private-ca` smoke flow now generates independently OpenSSL-verified CRLs and exercises real hardware signing, revoked immediate/queued output rejection, expected-outcome tests, workspace isolation, restart persistence, old-output preservation and disabling. Java tests additionally reject stale, incorrectly signed, delta/scoped, duplicate and unauthorized issuer CRLs and a revoked intermediate. Production CA-issued CRLs still require deployment acceptance.
