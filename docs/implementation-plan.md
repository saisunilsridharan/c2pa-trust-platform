# Implementation roadmap

## Product contract

Routine application configuration must be possible through React administration screens. Only database connection settings are external application configuration. Infrastructure provisioning (TLS, network policies, deployed capacity, and a managed root of trust) is a deployment prerequisite. Initial administrator enrollment needs a protected bootstrap credential. Production enrollment will replace the local development token flow.

Users upload content, inspect existing provenance, select an approved signing profile and identity, review the exact public claims, authorize a job, and download a separately stored signed version with its verification report. C2PA protects claim integrity; it does not establish factual truth.

## Architecture

React calls Java APIs. Java enforces authentication, workspace permissions, configuration, asset storage, and job state. Rust workers perform C2PA processing. Signing keys stay in KMS/HSM services. PostgreSQL stores users, memberships, asset versions, profiles, identities, configuration versions, jobs, verification reports, and audits. The implemented local/private mode stores originals and signed outputs in protected local directories; UI-configured private S3-compatible storage is implemented alongside local processing files.

The implemented persistent queue invokes one bounded local Rust process per instance, uses database leases to coordinate claims, and recovers expired leases. Multiple instances require common storage and identity paths. Isolated, resource-limited distributed workers remain future work. Preserve input hashes, profile versions, configuration versions, and job idempotency. Implemented jobs use QUEUED, RUNNING, COMPLETED, FAILED, and retention cleanup DELETING states.

## Completed development signing slice

UI-created development identities, reviewed user declarations, image/audio/video/PDF signed downloads, post-sign validation, original ingredient retention, and tampering checks are implemented. Configuration history/rollback, audit records, and development certificate status/rotation are implemented. Batch jobs, original/signed assets, downloadable validation reports, per-user job access, manual retries, startup recovery, UI retention, and operations diagnostics are implemented for a single local instance. UI PKCS#12 private identity import includes chain/key checks and a real Rust signing probe. Public certificate trust, trusted timestamps, Private PKCS#11 signing is implemented and SoftHSM-tested; vendor hardware compatibility and issuance/renewal remain outstanding. Private S3-compatible asset storage, version history/rollback, connectivity tests and per-job snapshots are implemented. Write-only encrypted service credentials and UI encryption-key backup/restore are implemented.

## Next: production signing vertical slice

1. Named-user enrollment/login, organization roles, account disabling, password changes, and user-attributed audits are implemented. Administrator-assisted recovery with forced password change and session revocation/counts are implemented. Workspace-scoped profiles, identities, memberships, jobs, retention, audits and operations are implemented, with platform administrator recovery. Authenticator MFA and one-time-key self-service recovery are implemented. Private OIDC with explicit account linking is implemented and protocol-tested. Broader distributed/edge login rate controls remain pending.
2. Flyway migrations, legacy additive-upgrade checks and a real PostgreSQL 17.11 Java/Rust integration flow are implemented.
3. UI private S3-compatible storage setup, test-before-activation, encrypted write-only credentials, version history/rollback and job snapshots are implemented. Test each deployment against its actual service.
4. Private PKCS#11 adapters, UI identity drafts, real signing probes and approved hardware choices are implemented and tested with non-exportable SoftHSM keys. Add vendor deployment verification, other KMS providers and production certificate issuance/renewal. Development expiry status and UI rotation are implemented.
5. Extend the implemented Rust signing and inspection with isolated queued workers, timestamping, and production trust policy.
6. Persistent jobs and polling progress are implemented; persistent notifications, scoped personal API keys and HMAC webhook outbox/delivery/retry administration are implemented. Database worker leases, attempt isolation and UI processing limits are implemented; isolated distributed workers remain pending.
7. JPEG, PNG, WebP, TIFF, WAV, MP3, FLAC, MP4, and PDF pass real signing, inspection, re-signing and tamper checks. Expand representative fixtures and independent-verifier compatibility testing.

## Administration expansion

Organization and branding; users and signing permissions; authentication providers; storage and retention; signing certificates and key references; assertion profiles and privacy; supported formats and limits; trust anchors and trust lists; timestamp providers; processing retries and limits; API/webhook integrations; health and audit history.

Each configuration area supports draft, validation or connectivity test, activation, version history, and rollback. Concurrent edits must return a conflict. Active jobs retain their initial configuration version. Avoid authentication changes that lock out the last administrator; require successful provider tests and a recovery path.

Secrets are write-only, encrypted using a managed root of trust outside the database, and excluded from logs, OpenAPI examples, browser persistence, and config history. Connectivity tests must constrain destinations to prevent SSRF. Never accept raw production signing keys into general configuration records.

## Format strategy

Publish a capability registry derived from the installed SDK version and tested operations. Enable only tested formats. Evaluate image, audio, video, and PDF capabilities separately. External manifests require a discovery and compatibility strategy. Detached signatures for arbitrary files are a distinct product operation; do not label them embedded C2PA credentials.

## Production acceptance

End-to-end signing through the UI; unauthorized signing denied; workspace isolation; modified-content detection; invalid, expired, and untrusted certificate handling; timestamp checks; restart persistence; idempotent retries and worker recovery; key protection; malformed-file resource limits; certificate rotation; audits; rollback; independent verifier compatibility.

## Selected provider direction

The user selected local/private services first. Prioritize private CA and local PKCS#12 signing (implemented), private object storage, local account recovery/session administration (implemented), private OIDC and PKCS#11/HSM adapters, then private trust/timestamp services. Do not label imported certificates publicly trusted without trust-list validation. Encrypted service credentials and UI master-key backup/recovery are implemented. Private S3-compatible storage connectivity tests and activation are implemented; Private OIDC is implemented; deployed provider verification, Private PKCS#11 signing is implemented and SoftHSM-tested. Vendor hardware acceptance, certificate issuance/renewal and trust/timestamp services remain pending.

## Latest verified application status

Completed: nine tested formats; workspace memberships and isolation; encrypted service credentials and key backup/restore; UI-configured private S3-compatible storage with tested versions and job snapshots; scoped personal API keys; persistent job notifications; signed webhook outbox, delivery retries and administration; Flyway migrations; database worker leases and attempt isolation; UI processing limits; audit hash chains and external-checkpoint export/comparison. The isolated H2 and PostgreSQL flows pass with the real Rust signer.

Remaining application work: deployed OIDC verification and broader login rate controls; vendor PKCS#11/HSM acceptance, other KMS adapters and certificate issuance/renewal; private trust-anchor policy, trusted timestamps and public trust-list validation;  isolated distributed workers; external immutable audit anchoring and wider independent-verifier compatibility. Private S3 has protocol regression coverage; each actual deployment still needs its own connectivity/compatibility test. Do not describe the complete production roadmap as finished.

Authenticator TOTP MFA and offline-key self-service recovery are implemented with encrypted, purpose-bound factor secrets, replay protection, hashed single-use codes, shared lockout and session/API-key revocation. Private OIDC is implemented with PKCE, browser-bound single-use state, signed-token validation, explicit account linking and UI version/test/activation/rollback. Protocol regression tests pass; actual deployed provider verification remains pending.

Selectable approved profile/certificate combinations are implemented for immediate and queued signing. UI publication snapshots claims and certificate references; withdrawal and re-enable affect future requests. Real H2/PostgreSQL tests cover rotation, stale reviews, withdrawal, workspace isolation, immutable job snapshots and idempotency. Independent profile/identity libraries with per-user approval workflows can extend these combinations later.

Private PKCS#11 EC P-256 signing is implemented through UI identity drafts/test/approval and immutable signing choices. Bounded Java native-provider processes use encrypted PIN references and private local Rust callback sockets. Real H2/PostgreSQL tests pass against a non-exportable SoftHSM token after removal of the fixture PEM key. Actual vendor devices require deployment acceptance; public trust is not implied by token use.
