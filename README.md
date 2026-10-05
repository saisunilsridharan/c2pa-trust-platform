# C2PA Trust Portal

React + TypeScript portal, Java 21 Spring Boot API with Swagger UI, and a Rust worker using the maintained C2PA SDK.

## Implemented development workflow

- Initial administrator enrollment, named-account login, and administrator/signer/viewer permissions.
- Local account recovery with forced temporary-password replacement and administrator session revocation.
- UI-managed organization name, signing profile, JPEG/PNG selection, upload limit, and AI disclosure requirement.
- Persistent configuration drafts and active configuration, with optimistic revision checks.
- Schema validation before profile activation; activation does **not** enable signing.
- Multipart JPEG/PNG provenance inspection through Java and the Rust SDK. Reports contain validation details; a readable manifest is not proof of trusted content.
- UI-created development signing identity with owner-only private-key storage; explicit development-only acknowledgment.
- JPEG/PNG signing with reviewed public creator/title/AI declarations, signed download, validation, and existing provenance preservation.
- UI development certificate status, expiry checks, and rotation.
- Configuration history, revision-protected rollback, and paginated audit records.
- Persistent single-instance signing jobs, batch upload, manual retry, and startup recovery.
- Saved original/signed assets and downloadable validation reports, with per-user ownership checks.
- UI-managed local retention and queue/database/worker diagnostics.
- UI import of password-protected PKCS#12 private CA identities, validated with the actual Rust signing worker.
- Swagger UI documents the APIs and supports the administrator token through its Authorize button.

KMS/HSM, external storage providers, OIDC/SSO, workspace isolation, secret encryption, trusted timestamps, public trust-list validation, and tamper-evident audit storage remain pending. Local private-CA import is supported, but does not establish public trust or provide hardware key protection. Keys cannot be exported through the API. See [the implementation roadmap](docs/implementation-plan.md).

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

Select settings, save the draft, validate it, then activate. In the signing panel, acknowledge development-only use and create an identity. Upload a JPEG or PNG, enter a title and creator attribution, select an AI declaration, review the public claims, and sign/download. Inspect the downloaded file in the verification panel. Development certificates expire after 30 days, are not trusted by public trust lists, and use no trusted timestamp. Existing provenance and its metadata are retained as a parent ingredient. Upload a JPEG or PNG containing an embedded C2PA manifest to inspect it. Unsigned or malformed content returns HTTP 422 rather than a false success result.

Swagger UI is served at `/swagger-ui/index.html` on the Java service; OpenAPI is at `/v3/api-docs`. The Vite development server proxies both paths and `/api`. Use `X-Admin-Token` in Swagger's Authorize dialog. Documentation is accessible locally without a token; administration and inspection require one.

## Database configuration

Database connection configuration is the only application setting supplied outside the UI:

- `DB_URL`: JDBC connection URL.
- `DB_USER`: database username.
- `DB_PASSWORD`: database password.

Development defaults to a persistent H2 database in `backend/.local/`. PostgreSQL JDBC support is included but production migrations and PostgreSQL validation are still pending. Automatic schema updates are for development only.

Application settings are stored in the database and survive backend restarts. Bootstrap authentication survives through its protected local token file. No storage credentials are stored yet. The development signing key stays in owner-only `backend/.local/development-identity/`; this file-based development provider is not suitable for production.

## Administration and recovery

The administration panel shows certificate validity, expiry, and SHA-256 fingerprint. Explicit UI rotation switches future signing requests to a new identity. Expired certificates cannot sign. Existing keys/certificates are retained in owner-only version directories under `backend/.local/development-identities/` so in-flight jobs keep a stable pair. The atomic current pointer survives restart. Older installations continue using `backend/.local/development-identity/` until rotation.

Activation and rollback create configuration snapshots. Restoring a version replaces active settings and the draft as a new revision; stale revisions are rejected. Existing active settings are imported as RECOVERED on first access. Versions overwritten before this release cannot be reconstructed.

Audit records cover draft saves, activation, rollback, identity creation/rotation, successful signing, and completed inspection (including invalid integrity results). They exclude tokens, private keys, file contents, and creator declarations. New records identify authenticated users. Historical records keep their original actor; tamper-evident storage remains pending. Queued-job failures are audited. Rejected requests and synchronous process failures are not audited yet. History and audit views support pages of 50 records.

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

ADMIN manages settings, users, identity rotation, history, and audits and may sign/verify. SIGNER signs and verifies using active settings. VIEWER reads active settings, verifies, and reads its own saved assets. All users currently share one organization; multi-workspace isolation is pending. Roles and account status are checked on every API request.

Passwords use BCrypt cost 12, require at least 12 characters, and accept at most 72 UTF-8 bytes. Five failed attempts lock a known account for 15 minutes. Session tokens expire after eight hours; only SHA-256 hashes are stored server-side. Tokens remain only in browser memory. Sign out revokes the current token; disabling users blocks their sessions immediately. UI password changes revoke all the account's sessions. Administrator-assisted recovery and active-session counts/revocation are implemented. OIDC/SSO, MFA, self-service forgotten-password recovery, and broader rate limiting remain pending.

For Swagger UI, call `/api/v1/auth/login` and authorize with its returned session token as `X-Admin-Token`; `Authorization: Bearer` is also accepted. The smoke script's bootstrap-token workflow works only before enrollment. After enrollment, use an authorized account session internally for checks without printing credentials. Production use requires TLS and additional hardening; retain private development access.

## Saved jobs and private identities

Use Saved assets & signing jobs to select multiple JPEG/PNG files, review shared creator/title/AI claims and the active profile, and submit a batch. Original files remain separate from signed outputs. Download the stored SDK verification report for completed jobs. A signer sees only their own jobs; administrators see all jobs. Batch requests include the reviewed profile revision and certificate fingerprint; changed configuration returns HTTP 409 before creating a job. Jobs capture immutable manifest and certificate/key paths before processing, support idempotency keys with conflicting-request rejection, and recover interrupted RUNNING jobs at startup. Failed jobs can be manually retried using their original snapshot. This is a single-instance queue; do not run multiple application instances against the same database/storage.

Local job assets are in `backend/.local/assets/`. Retention defaults to 30 days for completed/failed jobs; administrators can set 1–3650 days through the UI with deletion acknowledgment. Cleanup runs hourly, deleting expired originals, outputs, reports, and job records, and retains queued/running jobs. Back up the database and the entire protected `.local` directory together; database-only backups cannot restore file assets or identity keys.

Administration & recovery accepts a password-protected PKCS#12 bundle (up to 1 MiB) containing exactly one EC P-256 key and a valid matching C2PA-compatible certificate chain. Import checks validity, issuer signatures, signing usage, and key matching, then exercises actual Rust C2PA signing before atomically replacing the active identity. The password/bundle are not persisted; the extracted key is an owner-only local PEM file. This provider requires a private connection or HTTPS, provides no HSM protection or at-rest encryption, and has no trusted timestamp. Imported identities report `private-certificate`; public trust stays unverified. Replacement retains old keys for captured jobs; old key retention currently has no automatic purge. Development rotation intentionally switches back to an untrusted development certificate.

Run `python scripts/smoke-jobs.py` against the running backend before enrollment to check real queue processing, idempotency, conflicting claims, stored downloads/reports, and verification. It retains one generated test asset. After enrollment, adapt the internal smoke helper to use an authorized session without printing it. A signed output passing integrity checks must still be evaluated independently for signer trust.

Administrators can reset another local account through Users and permissions. A reset revokes all sessions, clears the account lockout, and marks the account as requiring a password change. The user signs in with the temporary password and can only read their account, change their password, or sign out until choosing a different password. Share temporary passwords through your secure channel; the portal does not send them. Administrators use the ordinary password-change screen for their own account. Active-session counts and Sign out all sessions are also available in the UI; revoking your own sessions requires signing in again.
