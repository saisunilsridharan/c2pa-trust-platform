import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Storage = {
  provider: string;
  endpoint: string;
  region: string;
  bucket: string;
  prefix: string;
  accessKeyCredential: string;
  secretKeyCredential: string;
  allowLoopbackHttp: boolean;
  trustedCaPem: string;
};
type Config = {
  enabled: boolean;
  storage: Storage | null;
  retentionDays: number;
  intervalMinutes: number;
};
type Version = { id: string; configuration: Config; testedAt: string | null };
type State = {
  revision: number;
  draft: Version | null;
  active: Version | null;
};
type Delivery = {
  id: string;
  revision: number;
  state: string;
  attempts: number;
  createdAt: string;
  checkpoint: { eventCount: number };
  receipt: { versionId: string; retainUntil: string } | null;
};
const provider: Storage = {
  provider: "S3",
  endpoint: "",
  region: "us-east-1",
  bucket: "",
  prefix: "audit",
  accessKeyCredential: "",
  secretKeyCredential: "",
  allowLoopbackHttp: false,
  trustedCaPem: "",
};
const initial: Config = {
  enabled: false,
  storage: provider,
  retentionDays: 30,
  intervalMinutes: 60,
};
export default function AuditStoragePanel({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [state, setState] = useState<State | null>(null),
    [form, setForm] = useState(initial),
    [history, setHistory] = useState<Version[]>([]),
    [deliveries, setDeliveries] = useState<Delivery[]>([]),
    [selected, setSelected] = useState(""),
    [page, setPage] = useState(0),
    [ack, setAck] = useState(false),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function request(path = "", method = "GET", body?: unknown) {
    const response = await fetch("/api/v1/admin/audit-storage" + path, {
      method,
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok)
      throw new Error(
        `Audit storage request failed (${response.status}). Refresh and check the selected version, credentials, versioning and Object Lock retention. Test objects may remain retained.`,
      );
    return response.json();
  }
  async function values() {
    return Promise.all([
      request(),
      request("/history?page=" + page),
      request("/deliveries?page=" + page),
    ]);
  }
  async function load() {
    const [s, h, d] = await values();
    setState(s);
    setHistory(h);
    setDeliveries(d);
  }
  useEffect(() => {
    let live = true;
    values()
      .then(([s, h, d]) => {
        if (live) {
          setState(s);
          setHistory(h);
          setDeliveries(d);
          setForm(s.draft?.configuration ?? initial);
          setSelected(s.draft?.id ?? "");
          setAck(false);
        }
      })
      .catch((e) => {
        if (live) setMessage(e.message);
      });
    return () => {
      live = false;
    };
  }, [token, page]);
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
  function update(key: keyof Storage, value: string | boolean) {
    setForm({
      ...form,
      storage: { ...(form.storage ?? provider), [key]: value },
    });
    setAck(false);
  }
  async function download(id: string) {
    const response = await fetch(
      `/api/v1/admin/audit-storage/deliveries/${id}/receipt`,
      { headers: { "X-Admin-Token": token } },
    );
    if (!response.ok) throw new Error("Receipt download failed");
    const url = URL.createObjectURL(await response.blob());
    const a = document.createElement("a");
    a.href = url;
    a.download = "audit-storage-receipt.json";
    a.click();
    URL.revokeObjectURL(url);
  }
  const storage = form.storage ?? provider;
  return (
    <section>
      <h2>Immutable audit storage</h2>
      <details className="help-details">
        <summary>More info</summary>
        <p>
          Store audit checkpoints in a separate private S3 bucket with
          versioning and Object Lock enabled. Checkpoints use COMPLIANCE
          retention. Tests also create retained objects that cannot be removed
          before their retention date. Keep downloaded receipts outside this
          portal, and administer the bucket independently of the application
          database.
        </p>
      </details>
      <details className="help-details">
        <summary>More info</summary>
        <p>
          The connection test checks the provider’s reported retention and
          refusal to delete a protected version. Provider administration and
          physical storage remain part of your trust policy.
        </p>
      </details>
      <label>
        <input
          type="checkbox"
          checked={form.enabled}
          onChange={(e) => {
            setForm({ ...form, enabled: e.target.checked });
            setAck(false);
          }}
        />
        Enable automatic checkpoints
      </label>
      {form.enabled && (
        <>
          <label>
            Private S3 endpoint
            <input
              value={storage.endpoint}
              onChange={(e) => update("endpoint", e.target.value)}
            />
          </label>
          <label>
            Region
            <input
              value={storage.region}
              onChange={(e) => update("region", e.target.value)}
            />
          </label>
          <label>
            Object Lock bucket
            <input
              value={storage.bucket}
              onChange={(e) => update("bucket", e.target.value)}
            />
          </label>
          <label>
            Object prefix
            <input
              value={storage.prefix}
              onChange={(e) => update("prefix", e.target.value)}
            />
          </label>
          <label>
            Access-key credential ID
            <input
              value={storage.accessKeyCredential}
              onChange={(e) => update("accessKeyCredential", e.target.value)}
            />
          </label>
          <label>
            Secret-key credential ID
            <input
              value={storage.secretKeyCredential}
              onChange={(e) => update("secretKeyCredential", e.target.value)}
            />
          </label>
          <label>
            Private TLS CA PEM
            <textarea
              value={storage.trustedCaPem ?? ""}
              onChange={(e) => update("trustedCaPem", e.target.value)}
            />
          </label>
          <label>
            <input
              type="checkbox"
              checked={storage.allowLoopbackHttp}
              onChange={(e) => update("allowLoopbackHttp", e.target.checked)}
            />
            Allow loopback HTTP for development
          </label>
          <label>
            Retention days
            <input
              type="number"
              min={1}
              max={3650}
              value={form.retentionDays}
              onChange={(e) => {
                setForm({ ...form, retentionDays: Number(e.target.value) });
                setAck(false);
              }}
            />
          </label>
          <label>
            Checkpoint interval in minutes
            <input
              type="number"
              min={5}
              max={1440}
              value={form.intervalMinutes}
              onChange={(e) => {
                setForm({ ...form, intervalMinutes: Number(e.target.value) });
                setAck(false);
              }}
            />
          </label>
        </>
      )}
      <button
        disabled={busy || !state}
        onClick={() =>
          run(async () => {
            const s = await request("/draft", "PUT", {
              revision: state!.revision,
              configuration: form,
            });
            setState(s);
            setSelected(s.draft.id);
            setAck(false);
            await load();
          })
        }
      >
        Save immutable draft
      </button>
      <p>
        Active version: {state?.active?.id ?? "none"} ·{" "}
        {state?.active?.configuration.enabled
          ? "automatic checkpoints enabled"
          : "disabled"}
        . Queued checkpoints retain their provider and retention configuration
        when future checkpoints are disabled or changed.
      </p>
      <label>
        Version
        <select
          value={selected}
          onChange={(e) => {
            setSelected(e.target.value);
            setAck(false);
          }}
        >
          <option value="">Select a version</option>
          {history.map((v) => (
            <option key={v.id} value={v.id}>
              {v.id} · {v.configuration.enabled ? "enabled" : "disabled"} ·{" "}
              {v.testedAt ? "tested" : "untested"}
            </option>
          ))}
        </select>
      </label>
      <label>
        <input
          type="checkbox"
          checked={ack}
          onChange={(e) => setAck(e.target.checked)}
        />
        I accept irreversible COMPLIANCE retention for tests and checkpoints,
        and scheduled writes while this version is active.
      </label>
      <button
        disabled={busy || !ack || !selected || !state}
        onClick={() =>
          run(async () => {
            await request("/test", "POST", {
              revision: state!.revision,
              versionId: selected,
              acknowledgeComplianceRetention: true,
            });
            await load();
            setMessage(
              "Versioning, retained payload and protected-version deletion checks passed.",
            );
          })
        }
      >
        Test selected version
      </button>
      <button
        disabled={busy || !ack || !selected || !state}
        onClick={() =>
          run(async () => {
            await request("/activate", "POST", {
              revision: state!.revision,
              versionId: selected,
              acknowledgeComplianceRetention: true,
            });
            await load();
            setAck(false);
          })
        }
      >
        Activate tested version
      </button>
      <button
        disabled={busy || !ack || !state?.active?.configuration.enabled}
        onClick={() =>
          run(async () => {
            await request("/checkpoint", "POST", {
              acknowledgeComplianceRetention: true,
            });
            await load();
          })
        }
      >
        Queue checkpoint now
      </button>
      <button disabled={busy} onClick={() => run(load)}>
        Refresh
      </button>
      <table>
        <thead>
          <tr>
            <th>Checkpoint</th>
            <th>Events</th>
            <th>Status</th>
            <th>Retention / actions</th>
          </tr>
        </thead>
        <tbody>
          {deliveries.map((d) => (
            <tr key={d.id}>
              <td>
                {d.id}
                <br />
                {d.createdAt}
              </td>
              <td>{d.checkpoint.eventCount}</td>
              <td>
                {d.state} · {d.attempts} attempts
              </td>
              <td>
                {d.receipt?.retainUntil}
                {d.state === "STORED" && (
                  <>
                    <button
                      disabled={busy}
                      onClick={() => run(() => download(d.id))}
                    >
                      Download independent receipt
                    </button>
                    <button
                      disabled={busy}
                      onClick={() =>
                        run(async () => {
                          await request(
                            `/deliveries/${d.id}/verify`,
                            "POST",
                            {},
                          );
                          setMessage(
                            "External protected version, checkpoint bytes and retention verified.",
                          );
                        })
                      }
                    >
                      Verify external checkpoint
                    </button>
                  </>
                )}
                {d.state === "FAILED" && (
                  <button
                    disabled={busy}
                    onClick={() =>
                      run(async () => {
                        await request(`/deliveries/${d.id}/retry`, "POST", {
                          revision: d.revision,
                        });
                        await load();
                      })
                    }
                  >
                    Retry original snapshot
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <button disabled={busy || page === 0} onClick={() => setPage(page - 1)}>
        Previous
      </button>
      <button
        disabled={busy || Math.max(history.length, deliveries.length) < 50}
        onClick={() => setPage(page + 1)}
      >
        Next
      </button>
      {message && <p role="status">{message}</p>}
    </section>
  );
}
