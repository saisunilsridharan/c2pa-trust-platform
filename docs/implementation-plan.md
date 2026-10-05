# Implementation roadmap

## Product contract

Routine application configuration must be possible through React administration screens. Only database connection settings are external application configuration. Infrastructure provisioning (TLS, network policies, deployed capacity, and a managed root of trust) is a deployment prerequisite. Initial administrator enrollment needs a protected bootstrap credential. Production enrollment will replace the local development token flow.

Users upload content, inspect existing provenance, select an approved signing profile and identity, review the exact public claims, authorize a job, and download a separately stored signed version with its verification report. C2PA protects claim integrity; it does not establish factual truth.

## Architecture

React calls Java APIs. Java enforces authentication, workspace permissions, configuration, asset storage, and job state. Rust workers perform C2PA processing. Signing keys stay in KMS/HSM services. PostgreSQL stores users, memberships, asset versions, profiles, identities, configuration versions, jobs, verification reports, and audits. Private object storage holds originals and signed outputs.

The current worker invocation is a bounded local process for development inspection. Replace it with isolated, resource-limited workers and a durable queue before production. Preserve input hashes, profile versions, configuration versions, and job idempotency. Jobs use QUEUED, INSPECTING, SIGNING, VALIDATING, COMPLETED, and FAILED states.

## Completed development signing slice

UI-created development identities, reviewed user declarations, JPEG/PNG signed downloads, post-sign validation, original ingredient retention, and tampering checks are implemented. Configuration history/rollback, audit records, and development certificate status/rotation are implemented. Local files and synchronous workers are development-only; public certificate trust and production key providers remain outstanding.

## Next: production signing vertical slice

1. Add protected UI enrollment and workspace roles; use Spring Security and OIDC.
2. Add Flyway migrations and PostgreSQL integration tests.
3. Implement UI storage setup with test-before-activation and encrypted write-only credentials.
4. Add KMS/HSM providers and production certificate lifecycle. Development expiry status and UI rotation are implemented.
5. Extend the implemented Rust signing and inspection with isolated queued workers, timestamping, and production trust policy.
6. Extend the implemented upload, signing review, signed download, and verification UI with durable jobs and progress.
7. Validate JPEG and PNG signing, tampering detection, provenance preservation, and independent-verifier compatibility.

## Administration expansion

Organization and branding; users and signing permissions; authentication providers; storage and retention; signing certificates and key references; assertion profiles and privacy; supported formats and limits; trust anchors and trust lists; timestamp providers; processing retries and limits; API/webhook integrations; health and audit history.

Each configuration area supports draft, validation or connectivity test, activation, version history, and rollback. Concurrent edits must return a conflict. Active jobs retain their initial configuration version. Avoid authentication changes that lock out the last administrator; require successful provider tests and a recovery path.

Secrets are write-only, encrypted using a managed root of trust outside the database, and excluded from logs, OpenAPI examples, browser persistence, and config history. Connectivity tests must constrain destinations to prevent SSRF. Never accept raw production signing keys into general configuration records.

## Format strategy

Publish a capability registry derived from the installed SDK version and tested operations. Enable only tested formats. Evaluate image, audio, video, and PDF capabilities separately. External manifests require a discovery and compatibility strategy. Detached signatures for arbitrary files are a distinct product operation; do not label them embedded C2PA credentials.

## Production acceptance

End-to-end signing through the UI; unauthorized signing denied; workspace isolation; modified-content detection; invalid, expired, and untrusted certificate handling; timestamp checks; restart persistence; idempotent retries and worker recovery; key protection; malformed-file resource limits; certificate rotation; audits; rollback; independent verifier compatibility.
