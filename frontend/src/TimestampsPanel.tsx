import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
import {
  SigningChoiceSelector,
  type SigningChoice,
} from "./SigningChoicesPanel";
type Config = {
  enabled: boolean;
  endpoint: string;
  bearerCredential: string;
  tlsCaPem: string;
  tsaAnchorsPem: string;
  allowLoopbackHttp: boolean;
};
type Version = {
  id: string;
  configuration: Config;
  createdAt: string;
  testedAt: string | null;
};
type State = {
  revision: number;
  draft: Version | null;
  active: Version | null;
};
const initial: Config = {
  enabled: false,
  endpoint: "",
  bearerCredential: "",
  tlsCaPem: "",
  tsaAnchorsPem: "",
  allowLoopbackHttp: false,
};
export default function TimestampsPanel({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [state, setState] = useState<State | null>(null),
    [form, setForm] = useState(initial),
    [rows, setRows] = useState<Version[]>([]),
    [selected, setSelected] = useState(""),
    [choice, setChoice] = useState<SigningChoice | null>(null),
    [page, setPage] = useState(0),
    [ack, setAck] = useState(false),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function request(path = "", method = "GET", body?: unknown) {
    const r = await fetch("/api/v1/admin/timestamps" + path, {
      method,
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!r.ok)
      throw new Error(
        r.status === 409
          ? "Configuration changed or this version needs testing. Refresh and review again."
          : `Timestamp request failed (${r.status}). Check endpoint, TLS, authentication, TSA anchors and selected signing identity.`,
      );
    return r.json();
  }
  async function values() {
    return Promise.all([request(), request("/history?page=" + page)]);
  }
  async function load() {
    const [s, h] = await values();
    setState(s);
    setRows(h);
  }
  useEffect(() => {
    let live = true;
    values()
      .then(([s, h]) => {
        if (live) {
          setState(s);
          setRows(h);
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
  function update(key: keyof Config, value: string | boolean) {
    setForm({ ...form, [key]: value });
    setAck(false);
  }
  return (
    <section>
      <h2>Private trusted timestamps</h2>
      <p>
        RFC 3161 timestamps bind a signing time to the signature. This
        configuration establishes trust under your private TSA anchors; it does
        not establish public trust-list membership. Enabled timestamps are
        required: unavailable, invalid or untrusted responses fail signing. Jobs
        retain their captured TSA version and credential references.
      </p>
      <p>
        Active timestamping:{" "}
        {state?.active?.configuration.enabled ? "required" : "disabled"}.
      </p>
      <button disabled={busy} onClick={() => run(load)}>
        Refresh timestamp provider
      </button>
      <label className="check">
        <input
          type="checkbox"
          checked={form.enabled}
          onChange={(e) => update("enabled", e.target.checked)}
        />
        Require trusted private timestamps
      </label>
      {form.enabled && (
        <>
          <label>
            RFC 3161 endpoint URL
            <input
              maxLength={2000}
              value={form.endpoint ?? ""}
              onChange={(e) => update("endpoint", e.target.value)}
            />
          </label>
          <label>
            Bearer credential ID (optional, encrypted in this workspace)
            <input
              maxLength={255}
              value={form.bearerCredential ?? ""}
              onChange={(e) => update("bearerCredential", e.target.value)}
            />
          </label>
          <label>
            Private TLS CA bundle (optional PEM)
            <textarea
              maxLength={12000}
              value={form.tlsCaPem ?? ""}
              onChange={(e) => update("tlsCaPem", e.target.value)}
            />
          </label>
          <label>
            TSA signing CA trust anchors (PEM)
            <textarea
              maxLength={60000}
              value={form.tsaAnchorsPem ?? ""}
              onChange={(e) => update("tsaAnchorsPem", e.target.value)}
            />
          </label>
          <label className="check">
            <input
              type="checkbox"
              checked={form.allowLoopbackHttp}
              onChange={(e) => update("allowLoopbackHttp", e.target.checked)}
            />
            Allow HTTP only for loopback development services.
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
            setMessage(
              "Timestamp draft saved. Test a real timestamped signature before activation.",
            );
          })
        }
      >
        Save timestamp draft
      </button>
      <label>
        Provider version
        <select
          value={selected}
          onChange={(e) => {
            setSelected(e.target.value);
            setAck(false);
          }}
        >
          <option value="">Select saved version</option>
          {rows.map((v) => (
            <option key={v.id} value={v.id}>
              {new Date(v.createdAt).toLocaleString()} ·{" "}
              {v.configuration.enabled ? "required" : "disabled"} ·{" "}
              {v.testedAt ? "tested" : "untested"}
            </option>
          ))}
        </select>
      </label>
      <SigningChoiceSelector
        token={token}
        value={choice}
        onChange={setChoice}
      />
      <p>
        The signing probe uses the selected certificate and active private trust
        policy. Select a compatible approved choice when strict signer trust is
        enabled.
      </p>
      <button
        disabled={
          busy || !state || !selected || (choice !== null && !choice.available)
        }
        onClick={() =>
          run(async () => {
            setState(
              await request("/test", "POST", {
                revision: state!.revision,
                versionId: selected,
                signingOptionId: choice?.id,
                signingOptionRevision: choice?.revision,
              }),
            );
            await load();
            setMessage(
              "Timestamped C2PA signing and private TSA trust validation passed.",
            );
          })
        }
      >
        Test timestamped signing
      </button>
      <label className="check">
        <input
          type="checkbox"
          checked={ack}
          onChange={(e) => setAck(e.target.checked)}
        />
        Activate this tested private TSA version. Enabled timestamping is
        mandatory for future signing requests.
      </label>
      <button
        disabled={busy || !state || !selected || !ack}
        onClick={() =>
          run(async () => {
            setState(
              await request("/activate", "POST", {
                revision: state!.revision,
                versionId: selected,
                acknowledgePrivateTimestamp: true,
              }),
            );
            setAck(false);
            await load();
            setMessage(
              "Timestamp provider activated. Roll back by testing and activating an earlier version.",
            );
          })
        }
      >
        Activate selected provider
      </button>
      <div className="actions">
        <button disabled={busy || !page} onClick={() => setPage(page - 1)}>
          Previous providers
        </button>
        <button
          disabled={busy || rows.length < 50}
          onClick={() => setPage(page + 1)}
        >
          Next providers
        </button>
      </div>
      <p role="status">{busy ? "Testing private timestamps…" : message}</p>
    </section>
  );
}
export function TimestampStatus({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [status, setStatus] = useState<{
      enabled: boolean;
      versionId?: string;
    } | null>(null),
    [message, setMessage] = useState("");
  async function load() {
    const r = await fetch("/api/v1/portal/timestamps", {
      headers: { "X-Admin-Token": token },
    });
    if (!r.ok)
      throw new Error(`Timestamp configuration unavailable (${r.status}).`);
    setStatus(await r.json());
  }
  useEffect(() => {
    load().catch((e) => setMessage(e.message));
  }, [token]);
  return (
    <>
      <p>
        {status === null
          ? "Loading timestamp policy…"
          : status.enabled
            ? "A trusted private timestamp is required for signing."
            : "Trusted timestamping is disabled."}{" "}
        {status?.versionId && <code>{status.versionId}</code>} Jobs capture the
        provider active at submission.
      </p>
      <button onClick={() => load().catch((e) => setMessage(e.message))}>
        Refresh timestamp policy
      </button>
      <p role="status">{message}</p>
    </>
  );
}
