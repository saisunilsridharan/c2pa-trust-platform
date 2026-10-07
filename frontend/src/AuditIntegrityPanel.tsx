import { useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Integrity = {
  valid: boolean;
  eventCount: number;
  legacyImported: number;
  hash: string | null;
  message: string;
};
export default function AuditIntegrityPanel({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [result, setResult] = useState<Integrity | null>(null),
    [file, setFile] = useState<File | null>(null),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function request(path = "", body?: unknown) {
    const response = await fetch("/api/v1/admin/audit-integrity" + path, {
      method: body === undefined ? "GET" : "POST",
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok)
      throw new Error(
        `Audit request failed (${response.status}). Check the checkpoint and selected workspace.`,
      );
    return response;
  }
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
      <h2>Audit integrity & checkpoints</h2>
      <details className="help-details">
        <summary>More info</summary>
        <p>
          Audit records form a hash chain. Keep exported checkpoints outside the
          portal and database backups. Comparing an earlier trusted checkpoint
          detects a rewritten chain. Legacy imported events have no proof of
          their state before import.
        </p>
      </details>
      <button
        disabled={busy}
        onClick={() =>
          run(async () => setResult(await (await request()).json()))
        }
      >
        Verify audit chain
      </button>
      <button
        disabled={busy}
        onClick={() =>
          run(async () => {
            const response = await request("/checkpoint");
            const url = URL.createObjectURL(await response.blob());
            const link = document.createElement("a");
            link.href = url;
            link.download = "audit-checkpoint.json";
            link.click();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
            setMessage(
              "Checkpoint downloaded. Preserve it in a trusted location outside the portal.",
            );
          })
        }
      >
        Export verified checkpoint
      </button>
      <label>
        Earlier checkpoint
        <input
          type="file"
          accept=".json"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        />
      </label>
      <button
        disabled={busy || !file}
        onClick={() =>
          run(async () => {
            if (!file) return;
            if (file.size > 4096) throw new Error("Checkpoint exceeds 4 KiB.");
            setResult(
              await (
                await request(
                  "/checkpoint/verify",
                  JSON.parse(await file.text()),
                )
              ).json(),
            );
          })
        }
      >
        Compare earlier checkpoint
      </button>
      {result && (
        <>
          <p>
            <strong>
              {result.valid ? "Verified" : "Integrity check failed"}
            </strong>{" "}
            · events: {result.eventCount} · legacy imported:{" "}
            {result.legacyImported}
          </p>
          <p>{result.message}</p>
          {result.hash && (
            <p className="fingerprint">
              Head SHA-256: <code>{result.hash}</code>
            </p>
          )}
        </>
      )}
      <p role="status">{busy ? "Checking audit records…" : message}</p>
    </section>
  );
}
