import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Config = {
  enabled: boolean;
  endpoint: string;
  credentialId: string;
  allowLoopbackHttp: boolean;
  trustedCaPem: string;
  maxAttempts: number;
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
type Delivery = {
  id: string;
  state: string;
  attempts: number;
  statusCode: number | null;
  createdAt: string;
  nextAttemptAt: string;
};
const initial: Config = {
  enabled: false,
  endpoint: "",
  credentialId: "",
  allowLoopbackHttp: false,
  trustedCaPem: "",
  maxAttempts: 5,
};
export default function WebhooksPanel({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [state, setState] = useState<State | null>(null),
    [form, setForm] = useState(initial),
    [history, setHistory] = useState<Version[]>([]),
    [deliveries, setDeliveries] = useState<Delivery[]>([]),
    [credentials, setCredentials] = useState<{ id: string; label: string }[]>(
      [],
    ),
    [selected, setSelected] = useState(""),
    [page, setPage] = useState(0),
    [historyPage, setHistoryPage] = useState(0),
    [credentialPage, setCredentialPage] = useState(0),
    [busy, setBusy] = useState(false),
    [ack, setAck] = useState(false),
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
          ? "Settings changed or this version needs testing. Refresh before retrying."
          : `Webhook request failed (${response.status}). Check TLS, endpoint, credential and receiver.`,
      );
    return response.json();
  }
  async function values() {
    return Promise.all([
      request("webhooks"),
      request(`webhooks/history?page=${historyPage}`),
      request(`webhooks/deliveries?page=${page}`),
      request(`credentials?page=${credentialPage}`),
    ]);
  }
  async function load() {
    const [s, h, d, c] = await values();
    setState(s);
    setHistory(h);
    setDeliveries(d);
    setCredentials(c);
  }
  useEffect(() => {
    let active = true;
    values()
      .then(([s, h, d, c]) => {
        if (active) {
          setState(s);
          setHistory(h);
          setDeliveries(d);
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
  }, [token, page, historyPage, credentialPage]);
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
  function update(key: keyof Config, value: string | boolean | number) {
    setForm({ ...form, [key]: value });
    setAck(false);
  }
  return (
    <section>
      <h2>Job webhooks</h2>
      <p>
        Active: {state?.active?.configuration.enabled ? "enabled" : "disabled"}.
        Receivers get job IDs and outcomes. Existing deliveries retain their
        configuration after a change; disabling stops new deliveries. Receivers
        must check the HMAC signature and timestamp, and deduplicate delivery
        IDs.
      </p>
      <button disabled={busy} onClick={() => run(load)}>
        Refresh settings & deliveries
      </button>
      <label className="check">
        <input
          type="checkbox"
          checked={form.enabled}
          onChange={(e) => update("enabled", e.target.checked)}
        />
        Send future job outcomes to a private receiver
      </label>
      {form.enabled && (
        <>
          <label>
            HTTPS receiver URL
            <input
              maxLength={2000}
              value={form.endpoint ?? ""}
              onChange={(e) => update("endpoint", e.target.value)}
            />
          </label>
          <label>
            Saved HMAC credential (at least 32 bytes)
            <select
              value={form.credentialId ?? ""}
              onChange={(e) => update("credentialId", e.target.value)}
            >
              <option value="">Select encrypted credential</option>
              {form.credentialId &&
                !credentials.some((c) => c.id === form.credentialId) && (
                  <option value={form.credentialId}>{form.credentialId}</option>
                )}
              {credentials.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.label}
                </option>
              ))}
            </select>
          </label>
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
            Maximum attempts
            <input
              type="number"
              min={1}
              max={8}
              value={form.maxAttempts}
              onChange={(e) => update("maxAttempts", Number(e.target.value))}
            />
          </label>
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
            Allow HTTP only for a loopback development receiver.
          </label>
        </>
      )}
      <button
        disabled={busy || !state}
        onClick={() =>
          run(async () => {
            const s = await request("webhooks/draft", "PUT", {
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
        Save webhook draft
      </button>
      <h3>Versions & rollback</h3>
      <select
        value={selected}
        onChange={(e) => {
          setSelected(e.target.value);
          setAck(false);
        }}
      >
        <option value="">Select saved version</option>
        {history.map((v) => (
          <option value={v.id} key={v.id}>
            {v.configuration.enabled ? "Enabled" : "Disabled"} ·{" "}
            {new Date(v.createdAt).toLocaleString()} ·{" "}
            {v.testedAt ? "tested" : "untested"}
          </option>
        ))}
      </select>
      <div className="actions">
        <button
          disabled={busy || !historyPage}
          onClick={() => setHistoryPage(historyPage - 1)}
        >
          Previous versions
        </button>
        <button
          disabled={busy || history.length < 50}
          onClick={() => setHistoryPage(historyPage + 1)}
        >
          Next versions
        </button>
        <button
          disabled={busy || !state || !selected}
          onClick={() =>
            run(async () => {
              setState(
                await request("webhooks/test", "POST", {
                  revision: state!.revision,
                  versionId: selected,
                }),
              );
              await load();
              setMessage("Webhook test passed.");
            })
          }
        >
          Send connection test
        </button>
      </div>
      <label className="check">
        <input
          type="checkbox"
          checked={ack}
          onChange={(e) => setAck(e.target.checked)}
        />
        Apply this tested version to future signing outcomes.
      </label>
      <button
        disabled={busy || !state || !selected || !ack}
        onClick={() =>
          run(async () => {
            setState(
              await request("webhooks/activate", "POST", {
                revision: state!.revision,
                versionId: selected,
                acknowledgeActivation: true,
              }),
            );
            setAck(false);
            await load();
            setMessage("Webhook version activated.");
          })
        }
      >
        Activate selected version
      </button>
      <h3>Delivery history</h3>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Delivery</th>
              <th>Status / attempts</th>
              <th>HTTP result</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            {deliveries.map((d) => (
              <tr key={d.id}>
                <td>
                  <code>{d.id}</code>
                  <br />
                  {new Date(d.createdAt).toLocaleString()}
                </td>
                <td>
                  {d.state} · {d.attempts}
                </td>
                <td>{d.statusCode ?? "No response"}</td>
                <td>
                  <button
                    disabled={busy || d.state !== "FAILED"}
                    onClick={() =>
                      run(async () => {
                        await request(
                          "webhooks/deliveries/" + d.id + "/retry",
                          "POST",
                          {},
                        );
                        await load();
                        setMessage(
                          "Delivery queued using its original configuration.",
                        );
                      })
                    }
                  >
                    Retry failed delivery
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="actions">
        <button disabled={busy || !page} onClick={() => setPage(page - 1)}>
          Previous deliveries
        </button>
        <button
          disabled={busy || deliveries.length < 50}
          onClick={() => setPage(page + 1)}
        >
          Next deliveries
        </button>
      </div>
      <p role="status">{busy ? "Updating webhooks…" : message}</p>
    </section>
  );
}
