# Implementation roadmap

## Product contract

Routine application configuration must be possible through React administration screens. Only database connection settings are external application configuration. Infrastructure provisioning (TLS, network policies, deployed capacity, and a managed root of trust) is a deployment prerequisite. Initial administrator enrollment needs a protected bootstrap credential. Production enrollment will replace the local development token flow.

Users upload content, inspect existing provenance, select an approved signing profile and identity, review the exact public claims, authorize a job, and download a separately stored signed version with its verification report. C2PA protects claim integrity; it does not establish factual truth.

## Architecture

React calls Java APIs. Java enforces authentication, workspace permissions, configuration, asset storage, and job state. Rust workers perform C2PA processing. Signing keys stay in KMS/HSM services. PostgreSQL stores users, memberships, asset versions, profiles, identities, configuration versions, jobs, verification reports, and audits. The implemented local/private mode stores originals and signed outputs in protected local directories; external object storage remains pending.

The implemented persistent queue invokes one bounded local Rust process at a time and recovers interrupted jobs. Add distributed leasing and isolated, resource-limited workers before running multiple instances. Preserve input hashes, profile versions, configuration versions, and job idempotency. Implemented jobs use QUEUED, RUNNING, COMPLETED, FAILED, and retention cleanup DELETING states.

## Completed development signing slice

UI-created development identities, reviewed user declarations, image/audio/video/PDF signed downloads, post-sign validation, original ingredient retention, and tampering checks are implemented. Configuration history/rollback, audit records, and development certificate status/rotation are implemented. Batch jobs, original/signed assets, downloadable validation reports, per-user job access, manual retries, startup recovery, UI retention, and operations diagnostics are implemented for a single local instance. UI PKCS#12 private identity import includes chain/key checks and a real Rust signing probe. Public certificate trust, trusted timestamps, external asset storage and hardware key providers remain outstanding. Write-only encrypted service credentials and UI encryption-key backup/restore are implemented.

## Next: production signing vertical slice

1. Named-user enrollment/login, organization roles, account disabling, password changes, and user-attributed audits are implemented. Administrator-assisted recovery with forced password change and session revocation/counts are implemented. Workspace-scoped profiles, identities, memberships, jobs, retention, audits and operations are implemented, with platform administrator recovery. Next add OIDC/SSO, MFA, self-service recovery, and stronger production login controls.
2. Add Flyway migrations and PostgreSQL integration tests.
3. Implement UI storage setup with test-before-activation and encrypted write-only credentials.
4. Add KMS/HSM providers and production certificate lifecycle. Development expiry status and UI rotation are implemented.
5. Extend the implemented Rust signing and inspection with isolated queued workers, timestamping, and production trust policy.
6. Persistent jobs and polling progress are implemented; next add distributed workers, notifications, and webhook integrations.
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

The user selected local/private services first. Prioritize private CA and local PKCS#12 signing (implemented), private object storage, local account recovery/session administration (implemented), private OIDC and PKCS#11/HSM adapters, then private trust/timestamp services. Do not label imported certificates publicly trusted without trust-list validation. Encrypted service credentials and UI master-key backup/recovery are implemented. Provider connectivity tests remain prerequisites for activating integrations.
