import FileDropZone from "./FileDropZone";
import InspectionTrustSummary from "./InspectionTrustSummary";
import { RevocationStatus } from "./RevocationPanel";
import { TimestampStatus } from "./TimestampsPanel";
import { TrustPolicyStatus } from "./TrustPolicyPanel";
import {
  SigningChoiceSelector,
  type SigningChoice,
} from "./SigningChoicesPanel";
import { workspaceFetch } from "./portalFetch";
import { useEffect, useState } from "react";
type Props = {
  token: string;
  capabilities: { mime: string; extension: string }[];
  active: {
    organizationName: string;
    profileName: string;
    requireAiDisclosure: boolean;
    formats: string[];
  } | null;
  available: boolean;
  canConfigure: boolean;
  onConfigured: () => Promise<void>;
};
export default function SigningPanel({
  token,
  capabilities,
  canConfigure,
  onConfigured,
  active: defaultActive,
  available: initialAvailable,
}: Props) {
  const fetch = workspaceFetch();
  const [choice, setChoice] = useState<SigningChoice | null>(null);
  const active = choice?.settings ?? defaultActive;
  const [available, setAvailable] = useState(initialAvailable),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  const [ack, setAck] = useState(false),
    [file, setFile] = useState<File | null>(null),
    [creator, setCreator] = useState(""),
    [title, setTitle] = useState(""),
    [ai, setAi] = useState("unspecified"),
    [reviewed, setReviewed] = useState(false);
  const [signed, setSigned] = useState<{ blob: Blob; name: string } | null>(
      null,
    ),
    [validation, setValidation] = useState<unknown>(null),
    [validationError, setValidationError] = useState("");
  useEffect(() => {
    setSigned(null);
    setValidation(null);
    setValidationError("");
  }, [file, title, creator, ai, choice, active]);
  function download(blob: Blob, name: string) {
    const url = URL.createObjectURL(blob),
      a = document.createElement("a");
    a.href = url;
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  useEffect(() => setReviewed(false), [active, choice]);
  useEffect(() => setAvailable(initialAvailable), [initialAvailable]);
  async function run(action: () => Promise<void>) {
    setBusy(true);
    setMessage("");
    try {
      await action();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : "Request failed");
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="sign-workspace">
      <div className="console-two-column">
        <fieldset className="console-form" disabled={busy}>
          <FileDropZone
            files={file ? [file] : []}
            accept={active?.formats.join(",")}
            disabled={busy}
            onChange={(files) => {
              const next = files[0] ?? null;
              setFile(next);
              if (next && (!title.trim() || title === file?.name.slice(0, 200)))
                setTitle(next.name.slice(0, 200));
              setReviewed(false);
            }}
          />
          <SigningChoiceSelector
            token={token}
            value={choice}
            onChange={setChoice}
            checks={
              <>
                <TrustPolicyStatus token={token} />
                <RevocationStatus token={token} />
                <TimestampStatus token={token} />
              </>
            }
          />
          <p className="field-hint">
            A valid signature does not always mean a trusted signer.
          </p>
          {!canConfigure && !(choice ? choice.available : available) && (
            <p>An administrator must configure the signing identity.</p>
          )}
          {!(choice ? choice.available : available) && canConfigure ? (
            <details className="help-details">
              <summary>Create a test signer</summary>
              <label className="check">
                <input
                  type="checkbox"
                  checked={ack}
                  onChange={(e) => setAck(e.target.checked)}
                />
                I understand this identity is for development only.
              </label>
              <button
                className="secondary"
                disabled={busy || !ack || !canConfigure}
                onClick={() =>
                  run(async () => {
                    const response = await fetch(
                      "/api/v1/admin/signing-identity/development",
                      {
                        method: "POST",
                        headers: {
                          "Content-Type": "application/json",
                          "X-Admin-Token": token,
                        },
                        body: JSON.stringify({ acknowledgeUntrusted: true }),
                      },
                    );
                    if (!response.ok)
                      throw new Error(
                        `Identity creation failed (${response.status}).`,
                      );
                    const identity = await response.json();
                    setAvailable(identity.available);
                    await onConfigured();
                    setMessage(
                      identity.available
                        ? "Development identity is ready. Its private key stays on the backend."
                        : `Identity status: ${identity.state}. Rotate it in Administration & recovery.`,
                    );
                  })
                }
              >
                Create test signer
              </button>
            </details>
          ) : null}
          {!active && <p>Activate a profile before signing.</p>}
          <label>
            Title
            <input
              maxLength={200}
              value={title}
              onChange={(e) => {
                setTitle(e.target.value);
                setReviewed(false);
              }}
            />
          </label>
          <label>
            Creator
            <input
              maxLength={120}
              value={creator}
              onChange={(e) => {
                setCreator(e.target.value);
                setReviewed(false);
              }}
            />
          </label>
          <label>
            Was AI used?
            <select
              value={ai}
              onChange={(e) => {
                setAi(e.target.value);
                setReviewed(false);
              }}
            >
              <option value="unspecified">Choose an answer</option>
              <option value="none">No</option>
              <option value="generated">Made with AI</option>
              <option value="edited">Edited with AI</option>
            </select>
          </label>
          <p className="public-detail-note">
            Title, creator and AI details will be public.
          </p>
          <label className="check">
            <input
              type="checkbox"
              checked={reviewed}
              onChange={(e) => setReviewed(e.target.checked)}
            />
            I checked the public details and agree to sign.
          </label>
          <button
            disabled={
              busy ||
              !(choice ? choice.available : available) ||
              !active ||
              !file ||
              !creator.trim() ||
              !title.trim() ||
              !reviewed ||
              (active.requireAiDisclosure && ai === "unspecified")
            }
            onClick={() =>
              run(async () => {
                if (!file) return;
                setSigned(null);
                setValidation(null);
                setValidationError("");
                const body = new FormData();
                body.append("file", file);
                body.append("creator", creator);
                body.append("title", title);
                body.append("aiDisclosure", ai);
                body.append("acknowledgePublicClaims", "true");
                if (choice) {
                  body.append("signingOptionId", choice.id);
                  body.append("signingOptionRevision", String(choice.revision));
                  body.append(
                    "expectedProfileRevision",
                    String(choice.profileRevision),
                  );
                  body.append(
                    "expectedIdentityFingerprint",
                    choice.fingerprint,
                  );
                }
                const response = await fetch("/api/v1/signing", {
                  method: "POST",
                  headers: { "X-Admin-Token": token },
                  body,
                });
                if (!response.ok)
                  throw new Error(
                    `Signing failed (${response.status}). Check content, profile, certificate and current revocation policy.`,
                  );
                const blob = await response.blob();
                const mime = response.headers
                  .get("Content-Type")
                  ?.split(";")[0];
                const name =
                  "signed-" +
                  file.name.replace(/\.[^.]+$/, "") +
                  (capabilities.find((c) => c.mime === mime)?.extension ||
                    ".bin");
                setSigned({ blob, name });
                download(blob, name);
                const bodyForValidation = new FormData();
                bodyForValidation.append(
                  "file",
                  new File([blob], name, { type: mime || blob.type }),
                );
                try {
                  const checked = await fetch("/api/v1/verification", {
                    method: "POST",
                    headers: { "X-Admin-Token": token },
                    signal: AbortSignal.timeout(30000),
                    body: bodyForValidation,
                  });
                  if (!checked.ok) throw new Error();
                  setValidation(await checked.json());
                } catch {
                  setValidationError(
                    "Signed file ready. Validation is unavailable; try the Validate page.",
                  );
                }
                setMessage("Your signed file is ready.");
              })
            }
          >
            {busy ? "Processing…" : "Sign and download"}
          </button>
        </fieldset>
        <div className="console-result" aria-label="Signed file and validation">
          {!signed && (
            <p className="empty-result">
              The signed file and its validation will appear here.
            </p>
          )}
          {signed && (
            <>
              <h2>Signed file</h2>
              {busy && validation === null && !validationError && (
                <p role="status">Checking signature…</p>
              )}
              <p className="signed-name">{signed.name}</p>
              <button
                className="secondary"
                onClick={() => download(signed.blob, signed.name)}
              >
                Download
              </button>
              {validation !== null && (
                <>
                  <InspectionTrustSummary report={validation} />
                  <details className="help-details">
                    <summary>Technical report</summary>
                    <pre>{JSON.stringify(validation, null, 2)}</pre>
                  </details>
                </>
              )}
              {validationError && <p role="status">{validationError}</p>}
            </>
          )}
        </div>
      </div>
      <p role="status" aria-live="polite">
        {message}
      </p>
    </section>
  );
}
