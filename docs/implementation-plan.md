# C2PA portal implementation and acceptance plan

## Product and architecture

React provides signing, verification and administration. Java/Spring Boot provides accounts, workspace authorization, durable jobs, encrypted provider credentials, audit records and Swagger UI. Rust/c2pa-rs 0.91.1 embeds and verifies manifests. All application/provider settings are configured through the UI; database connection settings remain external. Installed toolchains, trusted native modules and Linux isolation support are deployment prerequisites.

Users select approved profile/certificate combinations, review public creator/title/AI claims, and sign immediately or submit persistent batches. Inputs stay separate from outputs. The backend detects file bytes rather than trusting names or MIME headers. JPEG, PNG, WebP, TIFF, WAV, MP3, FLAC, MP4 and basic PDF are supported. Arbitrary files and every PDF/media variant are not automatically supported by embedded C2PA.

## Implemented application features

- Named accounts, ADMIN/SIGNER/VIEWER roles, platform recovery, workspace memberships and isolation, account disabling, BCrypt passwords, forced password replacement, session revocation and personal scoped API keys.
- Authenticator TOTP MFA with encrypted purpose-bound secrets, replay protection and hashed single-use backup codes. One-time offline account-recovery keys revoke prior sessions/API keys. Shared database account lockout persists across instances. UI-configured per-address/global authentication request limits also coordinate instances, enforce trusted-proxy handling and supply retry times before expensive authentication work.
- Private OIDC with immutable UI configuration, discovery/key tests, activation/history/rollback, PKCE, browser-bound single-use state, nonce and signed-token validation, explicit linking to existing accounts and local MFA enforcement. No automatic email/role provisioning.
- UI profiles and history/rollback; development identities; private EC P-256 PKCS#12 import with real SDK probes; approved immutable profile/certificate choices; rotation, withdrawal and stale-review/idempotency protection.
- Private PKCS#11 signing with encrypted PIN references, trusted module checks, bounded separate Java provider processes, private Rust callback sockets and real test-before-approval. Hardware keys are not exported through the API.
- Hardware certificate requests and renewal: token-signed PKCS#10 CSR download, public subject review, expiry display and same-key replacement-chain import. Replacement creates a separate identity version and must pass real SDK signing before approval. Failed probes retain a retryable draft; original choices and queued jobs preserve their original certificates. Smallstep-compatible private issuance is UI-configured with pinned TLS/issuance trust and CSR-bound ES256 provisioner tokens. Explicitly authorized scheduled plans publish tested same-key replacements, preserve reviewed profiles and existing jobs, and reject paused/stale/withdrawn work.
- UI-managed private CRL revocation: immutable issuer/complete-CRL versions, cryptographic signature/freshness validation, explicit expected-outcome tests and activation, strict optional coverage and current-policy checks for immediate/queued signing and readiness probes. Revoked or stale policies block output; manual refresh and issuer/device acceptance remain required.
- Versioned workspace private CA trust, signed-sample tests, activation/rollback and optional strict trusted signing. Private trust is identified separately from official public trust. SDK remote-manifest/OCSP/network fetching is disabled in embedded mode.
- Private RFC 3161 timestamp endpoints, encrypted bearer references, separate TLS/TSA CA anchors, mandatory verified timestamp signing probes, tested activation/rollback and immutable job snapshots. Responses are bounded, redirect-free and validated against the exact request nonce/imprint. Invalid or untrusted timestamps fail signing.
- Persistent jobs, original/signed assets and reports, per-user access, immutable claims/provider/certificate/trust/timestamp/budget snapshots, idempotency, polling, notifications, manual retry and UI retention.
- Flyway migrations, additive legacy upgrades and Hibernate schema validation. Database leases coordinate claims and preserve live attempts across restarts; stale completion is refused and attempt output directories isolate retries.
- UI worker timeout, retry count, memory/CPU limits and optional bubblewrap user/PID/network/filesystem namespaces. Native workers have a cleared environment and dedicated writable output directory. Namespace capability is tested before selection and does not fall back silently. Multiple application instances still require common storage/identity paths and the same encryption key.
- Private S3-compatible asset storage with TLS/private CA, encrypted credentials, tested immutable provider versions, rollback and per-job snapshots. Asset retention removes remote assets before local/database cleanup.
- Write-only AES-256-GCM provider credentials and UI passphrase-protected encryption-key backup/restore. Losing the matching key requires restoring it; credentials are never automatically replaced.
- Signed HMAC webhooks, durable outbox, bounded retries, version snapshots, delivery administration and deduplication IDs.
- Workspace audit hash chains, integrity verification and external checkpoint export/comparison. Private S3 Object Lock COMPLIANCE anchoring adds UI configuration/test/activation/rollback, retained probes, manual/scheduled checkpoints, durable delivery snapshots/retries, protected version re-verification and independently downloadable receipts. Bucket administration/provider integrity remain trust assumptions.

## Verified evidence

Java tests cover authorization, account protections, credentials, migrations, leasing, audit integrity, outbox failure/retry, workspace isolation and private OIDC protocol validation. React build and five workspace-request regressions pass; Rust tests exercise the timestamp transport.

The isolated `scripts/smoke-private-storage.py` uses temporary H2 or PostgreSQL 17, an independent SigV4/HMAC/Object Lock fixture, actual non-exportable SoftHSM keys and an independent OpenSSL RFC 3161 TSA. It verifies native signing, snapshots, MFA/recovery, restart persistence and encryption-key restoration without modifying real user data. `--namespace` requires genuine Linux namespaces; `--formats` exercises all nine formats, re-signing and tamper rejection through the software HSM. `--audit-lock` rejects providers with missing versioning, incorrect retention, deletion-enabled versions or changed contents. `--rate-limits` exercises shared UI authentication limits, trusted proxies and restart persistence. `--certificates` uses an independent OpenSSL CA to verify CSR proof of possession and same-key replacement signing, including unavailable-token retry recovery.

`--private-ca` exercises an independent OpenSSL-backed Smallstep protocol fixture, CSR-bound ES256 authorization, actual HSM issuance/signing, scheduled replacement and in-flight pause protection.

These tests establish the implemented protocols and selected fixtures, not compatibility with every vendor or official public trust.

## Remaining production work

1. Accept the deployed private OIDC provider, vendor HSM/module, S3/Object Lock bucket and TSA using their UI tests and real deployment policies. Endpoints, actual device/provider details and securely entered credentials are needed.
2. Accept the deployed Smallstep-compatible CA and dedicated C2PA provisioner/template through the issuance UI. Other CA protocols require a provider-specific adapter; manual CSR/replacement and PKCS#12 import remain available.
3. Implement official public trust-list validation and online/public revocation before claiming public trust. Private complete-CRL signing enforcement is implemented; private CA/TSA trust and CRLs do not establish official public trust.
4. Build a separately deployed distributed worker pool if shared-storage application instances are insufficient. Local namespace isolation and database leases are implemented; they do not provide independent remote worker registration, routing or deployment.
5. Expand independent-verifier and complex-media/PDF interoperability checks, deployment edge controls and any additional vendor KMS adapters required by the deployment.

The complete production roadmap is not finished. Keep completed local/private application work distinct from live provider acceptance and the remaining architectural features.
