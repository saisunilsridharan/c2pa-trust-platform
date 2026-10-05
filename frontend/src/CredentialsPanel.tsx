import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Credential = { id: string; label: string; createdAt: string };
type Security = {
  configured: boolean;
  available: boolean;
  state: string;
  fingerprint: string | null;
  encryptedCredentials: number;
};
export default function CredentialsPanel({
  token,
  platformAdmin,
}: {
  token: string;
  platformAdmin: boolean;
}) {
  const fetch = workspaceFetch();
  const [items, setItems] = useState<Credential[]>([]),
    [page, setPage] = useState(0),
    [label, setLabel] = useState(""),
    [value, setValue] = useState(""),
    [security, setSecurity] = useState<Security | null>(null),
    [password, setPassword] = useState(""),
    [backupAck, setBackupAck] = useState(false),
    [restoreAck, setRestoreAck] = useState(false),
    [file, setFile] = useState<File | null>(null),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function request(path: string, method = "GET", body?: unknown) {
    const response = await fetch("/api/v1/admin/" + path, {
      method,
      headers: {
        "X-Admin-Token": token,
        ...(body instanceof FormData
          ? {}
          : { "Content-Type": "application/json" }),
      },
      body:
        body === undefined
          ? undefined
          : body instanceof FormData
            ? body
            : JSON.stringify(body),
    });
    if (!response.ok)
      throw new Error(
        response.status === 503
          ? "The encryption key needs recovery by a platform administrator."
          : `Request failed (${response.status}). Check permissions, backup file and password.`,
      );
    return response.json();
  }
  async function load() {
    setItems(await request(`credentials?page=${page}`));
    if (platformAdmin) setSecurity(await request("security"));
  }
  useEffect(() => {
    let active = true;
    request(`credentials?page=${page}`)
      .then((c) => {
        if (active) setItems(c);
      })
      .catch((e) => {
        if (active) setMessage(e.message);
      });
    if (platformAdmin)
      request("security")
        .then((s) => {
          if (active) setSecurity(s);
        })
        .catch((e) => {
          if (active) setMessage(e.message);
        });
    return () => {
      active = false;
    };
  }, [token, page, platformAdmin]);
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
      <h2>Private-service credentials</h2>
      <p>
        Credential values are write-only and encrypted. Use saved credential IDs
        when configuring a private-service integration. Saving a replacement
        creates a new version for future configuration changes.
      </p>
      <label>
        Label
        <input
          maxLength={80}
          value={label}
          onChange={(e) => setLabel(e.target.value)}
        />
      </label>
      <label>
        Secret value
        <input
          type="password"
          autoComplete="off"
          maxLength={24000}
          value={value}
          onChange={(e) => setValue(e.target.value)}
        />
      </label>
      <button
        disabled={busy || !label.trim() || !value}
        onClick={() =>
          run(async () => {
            try {
              await request("credentials", "POST", { label, value });
              setLabel("");
              await load();
              setMessage("Encrypted credential saved.");
            } finally {
              setValue("");
            }
          })
        }
      >
        Save encrypted credential
      </button>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Label</th>
              <th>Credential ID</th>
              <th>Created</th>
            </tr>
          </thead>
          <tbody>
            {items.map((c) => (
              <tr key={c.id}>
                <td>{c.label}</td>
                <td>
                  <code>{c.id}</code>
                </td>
                <td>{new Date(c.createdAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="actions">
        <button disabled={busy || page === 0} onClick={() => setPage(page - 1)}>
          Previous
        </button>
        <button
          disabled={busy || items.length < 50}
          onClick={() => setPage(page + 1)}
        >
          Next
        </button>
      </div>
      {platformAdmin && security && (
        <>
          <h3>Encryption key backup & recovery</h3>
          <p>
            Status: {security.state} · encrypted credentials:{" "}
            {security.encryptedCredentials}. The master key is kept in an
            owner-only local file. Keep an encrypted key backup with your
            protected database backup.
          </p>
          {security.fingerprint && (
            <p className="fingerprint">
              Key SHA-256: <code>{security.fingerprint}</code>
            </p>
          )}
          <label>
            Backup passphrase (12–128 characters)
            <input
              type="password"
              autoComplete="new-password"
              minLength={12}
              maxLength={128}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>
          <label className="check">
            <input
              type="checkbox"
              checked={backupAck}
              onChange={(e) => setBackupAck(e.target.checked)}
            />
            Create a password-protected encryption-key backup.
          </label>
          <button
            disabled={
              busy || !security.available || password.length < 12 || !backupAck
            }
            onClick={() =>
              run(async () => {
                try {
                  const response = await fetch(
                    "/api/v1/admin/security/backup",
                    {
                      method: "POST",
                      headers: {
                        "X-Admin-Token": token,
                        "Content-Type": "application/json",
                      },
                      body: JSON.stringify({
                        password,
                        acknowledgeKeyBackup: true,
                      }),
                    },
                  );
                  if (!response.ok)
                    throw new Error(`Backup failed (${response.status}).`);
                  const url = URL.createObjectURL(await response.blob());
                  const link = document.createElement("a");
                  link.href = url;
                  link.download = "portal-encryption-key.backup";
                  link.click();
                  setTimeout(() => URL.revokeObjectURL(url), 1000);
                  setBackupAck(false);
                  setMessage("Encrypted key backup downloaded.");
                } finally {
                  setPassword("");
                }
              })
            }
          >
            Download encrypted key backup
          </button>
          <label>
            Encrypted key backup
            <input
              type="file"
              accept=".backup"
              onChange={(e) => {
                setFile(e.target.files?.[0] ?? null);
                setRestoreAck(false);
              }}
            />
          </label>
          <label className="check">
            <input
              type="checkbox"
              checked={restoreAck}
              onChange={(e) => setRestoreAck(e.target.checked)}
            />
            Restore the encryption key matching the stored credentials.
          </label>
          <button
            disabled={busy || !file || password.length < 12 || !restoreAck}
            onClick={() =>
              run(async () => {
                try {
                  if (!file) return;
                  const form = new FormData();
                  form.append("file", file);
                  form.append("password", password);
                  form.append("acknowledgeRestore", "true");
                  setSecurity(await request("security/restore", "POST", form));
                  setRestoreAck(false);
                  setMessage("Encryption key restored.");
                } finally {
                  setPassword("");
                }
              })
            }
          >
            Restore encryption key
          </button>
        </>
      )}
      <p role="status">{busy ? "Updating credentials…" : message}</p>
    </section>
  );
}
