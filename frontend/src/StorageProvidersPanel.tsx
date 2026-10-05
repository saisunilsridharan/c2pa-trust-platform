import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Config = {
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
  provider: "LOCAL",
  endpoint: "",
  region: "us-east-1",
  bucket: "",
  prefix: "c2pa-portal",
  accessKeyCredential: "",
  secretKeyCredential: "",
  allowLoopbackHttp: false,
  trustedCaPem: "",
};
export default function StorageProvidersPanel({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [state, setState] = useState<State | null>(null),
    [form, setForm] = useState(initial),
    [history, setHistory] = useState<Version[]>([]),
    [credentials, setCredentials] = useState<{ id: string; label: string }[]>(
      [],
    ),
    [selected, setSelected] = useState(""),
    [page, setPage] = useState(0),
    [credentialPage, setCredentialPage] = useState(0),
    [ack, setAck] = useState(false),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function request(path: string, method = "GET", body?: unknown) {
    const response = await fetch("/api/v1/admin/" + path, {
      method,
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok)
      throw new Error(
        response.status === 409
          ? "Storage changed or the version needs testing. Refresh and retry."
          : `Storage request failed (${response.status}). Check endpoint, TLS, bucket and credentials.`,
      );
    return response.json();
  }
  async function values() {
    return Promise.all([
      request("storage/providers"),
      request(`storage/providers/history?page=${page}`),
      request(`credentials?page=${credentialPage}`),
    ]);
  }
  async function load() {
    const [s, h, c] = await values();
    setState(s);
    setHistory(h);
    setCredentials(c);
  }
  useEffect(() => {
    let active = true;
    values()
      .then(([s, h, c]) => {
        if (active) {
          setState(s);
          setHistory(h);
          setCredentials(c);
          setForm(s.draft?.configuration ?? initial);
          setSelected(s.draft?.id ?? "");
          setAck(false);
        }
      })
      .catch((e) => {
        if (active) setMessage(e.message);
      });
    return () => {
      active = false;
    };
  }, [token, page, credentialPage]);
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
      <h2>Private object storage</h2>
      <p>
        Active provider: {state?.active?.configuration.provider ?? "LOCAL"}.
        Changes apply to future jobs. Existing jobs keep their storage
        configuration and credentials. Local working files support processing
        and retries.
      </p>
      <button disabled={busy} onClick={() => run(load)}>
        Refresh settings & credentials
      </button>
      <label>
        Provider
        <select
          value={form.provider}
          onChange={(e) => update("provider", e.target.value)}
        >
          <option value="LOCAL">Managed local storage</option>
          <option value="S3">Private S3-compatible storage</option>
        </select>
      </label>
      {form.provider === "S3" && (
        <>
          {(
            [
              ["endpoint", "HTTPS endpoint"],
              ["region", "Region"],
              ["bucket", "Existing bucket"],
              ["prefix", "Object prefix"],
            ] as const
          ).map(([key, label]) => (
            <label key={key}>
              {label}
              <input
                maxLength={key === "endpoint" ? 2000 : 80}
                value={form[key] ?? ""}
                onChange={(e) => update(key, e.target.value)}
              />
            </label>
          ))}
          {(
            [
              ["accessKeyCredential", "Access key credential"],
              ["secretKeyCredential", "Secret key credential"],
            ] as const
          ).map(([key, label]) => (
            <label key={key}>
              {label}
              <select
                value={form[key] ?? ""}
                onChange={(e) => update(key, e.target.value)}
              >
                <option value="">Select saved encrypted credential</option>
                {form[key] && !credentials.some((c) => c.id === form[key]) && (
                  <option value={form[key]}>{form[key]}</option>
                )}
                {credentials.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.label}
                  </option>
                ))}
              </select>
            </label>
          ))}
          <div className="actions">
            <button
              disabled={busy || !credentialPage}
              onClick={() => setCredentialPage(credentialPage - 1)}
            >
              Previous credentials
            </button>
            <button
              disabled={busy || credentials.length < 50}
              onClick={() => setCredentialPage(credentialPage + 1)}
            >
              Next credentials
            </button>
          </div>
          <label>
            Private CA bundle (optional PEM)
            <textarea
              maxLength={12000}
              value={form.trustedCaPem ?? ""}
              onChange={(e) => update("trustedCaPem", e.target.value)}
            />
          </label>
          <label className="check">
            <input
              type="checkbox"
              checked={form.allowLoopbackHttp}
              onChange={(e) => update("allowLoopbackHttp", e.target.checked)}
            />
            Allow HTTP only for a loopback development service.
          </label>
        </>
      )}
      <button
        disabled={busy || !state}
        onClick={() =>
          run(async () => {
            const s = await request("storage/providers/draft", "PUT", {
              revision: state!.revision,
              configuration: form,
            });
            setState(s);
            setSelected(s.draft.id);
            setAck(false);
            await load();
            setMessage("Draft saved; test before activation.");
          })
        }
      >
        Save storage draft
      </button>
      <h3>Storage versions & rollback</h3>
      <p>
        Tests write, read and delete a random object. Select and test an earlier
        version to restore it.
      </p>
      <select
        value={selected}
        onChange={(e) => {
          setSelected(e.target.value);
          setAck(false);
        }}
      >
        <option value="">Select saved version</option>
        {history.map((v) => (
          <option key={v.id} value={v.id}>
            {v.configuration.provider} · {v.configuration.bucket ?? "local"} ·{" "}
            {new Date(v.createdAt).toLocaleString()} ·{" "}
            {v.testedAt ? "tested" : "untested"}
          </option>
        ))}
      </select>
      <div className="actions">
        <button disabled={busy || !page} onClick={() => setPage(page - 1)}>
          Previous versions
        </button>
        <button
          disabled={busy || history.length < 50}
          onClick={() => setPage(page + 1)}
        >
          Next versions
        </button>
        <button
          disabled={busy || !state || !selected}
          onClick={() =>
            run(async () => {
              setState(
                await request("storage/providers/test", "POST", {
                  revision: state!.revision,
                  versionId: selected,
                }),
              );
              await load();
              setMessage("Write, read and delete test passed.");
            })
          }
        >
          Test selected version
        </button>
      </div>
      <label className="check">
        <input
          type="checkbox"
          checked={ack}
          onChange={(e) => setAck(e.target.checked)}
        />
        Use this tested version for future jobs. Keep previous buckets and
        credentials available for existing jobs.
      </label>
      <button
        disabled={busy || !state || !selected || !ack}
        onClick={() =>
          run(async () => {
            setState(
              await request("storage/providers/activate", "POST", {
                revision: state!.revision,
                versionId: selected,
                acknowledgeActivation: true,
              }),
            );
            setAck(false);
            await load();
            setMessage("Storage version activated.");
          })
        }
      >
        Activate selected version
      </button>
      <p role="status">{busy ? "Updating storage…" : message}</p>
    </section>
  );
}
