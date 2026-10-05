import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Config = { privateAnchorsPem: string; requireTrustedSigning: boolean };
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
export default function TrustPolicyPanel({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [state, setState] = useState<State | null>(null),
    [form, setForm] = useState<Config>({
      privateAnchorsPem: "",
      requireTrustedSigning: false,
    }),
    [rows, setRows] = useState<Version[]>([]),
    [selected, setSelected] = useState(""),
    [sample, setSample] = useState<File | null>(null),
    [page, setPage] = useState(0),
    [ack, setAck] = useState(false),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function request(path = "", method = "GET", body?: unknown) {
    const r = await fetch("/api/v1/admin/trust-policy" + path, {
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
    if (!r.ok)
      throw new Error(
        r.status === 409
          ? "Policy changed or needs a successful sample test. Refresh and review again."
          : `Trust policy request failed (${r.status}). Check CA anchors, sample credentials and policy requirements.`,
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
          setForm(
            s.draft?.configuration ?? {
              privateAnchorsPem: "",
              requireTrustedSigning: false,
            },
          );
          setRows(h);
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
  return (
    <section>
      <h2>Private certificate trust policy</h2>
      <p>
        Organization trust is distinct from public C2PA trust. CA anchors
        entered here apply only to this workspace. Reports identify the policy
        version and retain cryptographic integrity results. Public trust-list
        verification and trusted timestamps require separate configuration.
      </p>
      <p>
        Active policy: {state?.active?.id ?? "SDK default"}. Strict signing:{" "}
        {state?.active?.configuration.requireTrustedSigning
          ? "required"
          : "not required"}
        .
      </p>
      <button disabled={busy} onClick={() => run(load)}>
        Refresh policy
      </button>
      <label>
        Private CA trust anchors (PEM)
        <textarea
          value={form.privateAnchorsPem ?? ""}
          maxLength={60000}
          onChange={(e) => {
            setForm({ ...form, privateAnchorsPem: e.target.value });
            setAck(false);
          }}
        />
      </label>
      <label className="check">
        <input
          type="checkbox"
          checked={form.requireTrustedSigning}
          onChange={(e) => {
            setForm({ ...form, requireTrustedSigning: e.target.checked });
            setAck(false);
          }}
        />
        Require every newly signed artifact to validate as trusted under this
        private policy. Untrusted outputs fail and are removed.
      </label>
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
            setMessage("Draft saved. Test a signed sample before activation.");
          })
        }
      >
        Save policy draft
      </button>
      <label>
        Policy version
        <select
          value={selected}
          onChange={(e) => {
            setSelected(e.target.value);
            setAck(false);
          }}
        >
          <option value="">Select version</option>
          {rows.map((v) => (
            <option key={v.id} value={v.id}>
              {new Date(v.createdAt).toLocaleString()} ·{" "}
              {v.configuration.requireTrustedSigning ? "strict" : "inspect"} ·{" "}
              {v.testedAt ? "tested" : "untested"}
            </option>
          ))}
        </select>
      </label>
      <label>
        Signed sample for policy test
        <input
          type="file"
          onChange={(e) => setSample(e.target.files?.[0] ?? null)}
        />
      </label>
      <button
        disabled={busy || !state || !selected || !sample}
        onClick={() =>
          run(async () => {
            const body = new FormData();
            body.append("file", sample!);
            body.append("revision", String(state!.revision));
            body.append("versionId", selected);
            setState(await request("/test", "POST", body));
            await load();
            setMessage(
              "Sample meets this policy. This does not verify public C2PA trust.",
            );
          })
        }
      >
        Test sample against policy
      </button>
      <label className="check">
        <input
          type="checkbox"
          checked={ack}
          onChange={(e) => setAck(e.target.checked)}
        />
        Activate this tested private policy for future requests. Existing jobs
        retain their captured policy.
      </label>
      <button
        disabled={busy || !ack || !state || !selected}
        onClick={() =>
          run(async () => {
            setState(
              await request("/activate", "POST", {
                revision: state!.revision,
                versionId: selected,
                acknowledgePrivatePolicy: true,
              }),
            );
            setAck(false);
            await load();
            setMessage(
              "Private policy activated. Roll back by testing and activating an earlier version.",
            );
          })
        }
      >
        Activate selected policy
      </button>
      <div className="actions">
        <button disabled={busy || !page} onClick={() => setPage(page - 1)}>
          Previous policies
        </button>
        <button
          disabled={busy || rows.length < 50}
          onClick={() => setPage(page + 1)}
        >
          Next policies
        </button>
      </div>
      <p role="status">{busy ? "Checking trust policy…" : message}</p>
    </section>
  );
}
export function TrustPolicyStatus({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [status, setStatus] = useState<{
      source: string;
      versionId?: string;
      requireTrustedSigning: boolean;
    } | null>(null),
    [message, setMessage] = useState("");
  async function load() {
    const r = await fetch("/api/v1/portal/trust-policy", {
      headers: { "X-Admin-Token": token },
    });
    if (!r.ok) throw new Error(`Trust policy unavailable (${r.status}).`);
    setStatus(await r.json());
  }
  useEffect(() => {
    load().catch((e) => setMessage(e.message));
  }, [token]);
  return (
    <>
      <p>
        Signing trust policy:{" "}
        {status?.source === "PRIVATE_WORKSPACE_POLICY"
          ? "Private workspace policy"
          : "SDK default"}{" "}
        {status?.versionId && <code>{status.versionId}</code>}.{" "}
        {status?.requireTrustedSigning
          ? "Trusted signing is required."
          : "Public trust has not been established."}{" "}
        Jobs capture the policy active at submission.
      </p>
      <button onClick={() => load().catch((e) => setMessage(e.message))}>
        Refresh trust policy
      </button>
      <p role="status">{message}</p>
    </>
  );
}
