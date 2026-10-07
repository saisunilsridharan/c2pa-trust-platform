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
    <section>
      <div className="step-heading">
        <span>1</span>
        <h2>Choose a signer</h2>
      </div>
      <details className="help-details">
        <summary>Signing checks</summary>
        <TrustPolicyStatus token={token} />
        <RevocationStatus token={token} />
        <TimestampStatus token={token} />
      </details>
      <SigningChoiceSelector
        token={token}
        value={choice}
        onChange={setChoice}
      />
      <div className="notice">
        Public trust is not verified here. The details you enter will be public.
      </div>
      {!canConfigure && !(choice ? choice.available : available) && (
        <p>An administrator must configure the signing identity.</p>
      )}
      {!(choice ? choice.available : available) && canConfigure ? (
        <>
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
        </>
      ) : (choice ? choice.available : available) ? (
        <p>Signer ready.</p>
      ) : null}
      {!active && <p>Activate a profile before signing.</p>}
      <div className="step-heading">
        <span>2</span>
        <h2>Add your file</h2>
      </div>
      <label>
        File
        <input
          type="file"
          accept={active?.formats.join(",")}
          onChange={(e) => {
            setFile(e.target.files?.[0] ?? null);
            setReviewed(false);
          }}
        />
      </label>
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
      <div className="step-heading">
        <span>3</span>
        <h2>Check and sign</h2>
      </div>
      <p>
        Public details: <strong>{title || "Title required"}</strong> · creator{" "}
        {creator || "required"} ·{" "}
        {active?.organizationName || "organization pending"} · profile{" "}
        {active?.profileName || "pending"} · AI disclosure {ai}. These details
        are your statements. The file’s earlier history is kept.
      </p>
      <label className="check">
        <input
          type="checkbox"
          checked={reviewed}
          onChange={(e) => setReviewed(e.target.checked)}
        />
        I checked these details and agree to sign.
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
              body.append("expectedIdentityFingerprint", choice.fingerprint);
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
            const url = URL.createObjectURL(await response.blob());
            const link = document.createElement("a");
            link.href = url;
            const mime = response.headers.get("Content-Type")?.split(";")[0];
            link.download =
              "signed-content" +
              (capabilities.find((c) => c.mime === mime)?.extension || ".bin");
            link.click();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
            setMessage(
              "Your signed file is downloaded. Use Verify to check it.",
            );
          })
        }
      >
        {busy ? "Processing…" : "Sign and download"}
      </button>
      <p role="status" aria-live="polite">
        {message}
      </p>
    </section>
  );
}
