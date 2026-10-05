import { useEffect, useState } from "react";
type Identity = {
  configured: boolean;
  available: boolean;
  state: string;
  fingerprint: string | null;
  expiresAt: string | null;
};
type Version = {
  id: number;
  revision: number;
  createdAt: string;
  action: string;
  settings: { organizationName: string; profileName: string };
};
type Event = {
  id: number;
  createdAt: string;
  action: string;
  actor: string;
  reference: string;
};
type Props = { token: string; revision: number; onChange: () => Promise<void> };
export default function AdministrationPanel({
  token,
  revision,
  onChange,
}: Props) {
  const [identity, setIdentity] = useState<Identity | null>(null),
    [history, setHistory] = useState<Version[]>([]),
    [events, setEvents] = useState<Event[]>([]);
  const [historyPage, setHistoryPage] = useState(0),
    [auditPage, setAuditPage] = useState(0),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  const [rotateConfirmed, setRotateConfirmed] = useState(false),
    [selected, setSelected] = useState<number | null>(null),
    [rollbackConfirmed, setRollbackConfirmed] = useState(false);
  async function request(path: string, body?: unknown) {
    const response = await fetch("/api/v1/admin/" + path, {
      method: body === undefined ? "GET" : "POST",
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok)
      throw new Error(
        response.status === 409
          ? "Settings or identity changed. Refresh before retrying."
          : `Request failed (${response.status}).`,
      );
    return response.json();
  }
  async function load() {
    const [i, h, a] = await Promise.all([
      request("signing-identity"),
      request(`configuration/history?page=${historyPage}`),
      request(`audit-events?page=${auditPage}`),
    ]);
    setIdentity(i);
    setHistory(h);
    setEvents(a);
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
  useEffect(() => {
    let cancelled = false;
    setBusy(true);
    Promise.all([
      request("signing-identity"),
      request(`configuration/history?page=${historyPage}`),
      request(`audit-events?page=${auditPage}`),
    ])
      .then(([i, h, a]) => {
        if (!cancelled) {
          setIdentity(i);
          setHistory(h);
          setEvents(a);
        }
      })
      .catch((e) => {
        if (!cancelled) setMessage(e.message);
      })
      .finally(() => {
        if (!cancelled) setBusy(false);
      });
    return () => {
      cancelled = true;
    };
  }, [token, revision, historyPage, auditPage]);
  useEffect(() => {
    setSelected(null);
    setRollbackConfirmed(false);
  }, [revision, historyPage]);
  return (
    <section>
      <div className="section-title">
        <h2>Administration & recovery</h2>
        <button
          disabled={busy}
          className="secondary"
          onClick={() =>
            run(async () => {
              await onChange();
              await load();
            })
          }
        >
          Refresh
        </button>
      </div>
      <h3>Development certificate</h3>
      {identity ? (
        <>
          <p>
            Status: <strong>{identity.state}</strong> · production trusted: no
            <br />
            Expiry:{" "}
            {identity.expiresAt
              ? new Date(identity.expiresAt).toLocaleString()
              : "Not configured"}
          </p>
          {identity.fingerprint && (
            <p className="fingerprint">
              SHA-256: <code>{identity.fingerprint}</code>
            </p>
          )}
          {identity.configured && (
            <>
              <label className="check">
                <input
                  type="checkbox"
                  checked={rotateConfirmed}
                  onChange={(e) => setRotateConfirmed(e.target.checked)}
                />
                Rotate for future signatures. Existing signed files and
                in-progress requests keep their original identity.
              </label>
              <button
                disabled={busy || !rotateConfirmed}
                onClick={() =>
                  run(async () => {
                    await request("signing-identity/development/rotate", {
                      expectedFingerprint: identity.fingerprint,
                      acknowledgeUntrusted: true,
                    });
                    setRotateConfirmed(false);
                    await onChange();
                    await load();
                    setMessage(
                      "Development certificate rotated; production trust remains unavailable.",
                    );
                  })
                }
              >
                Rotate development certificate
              </button>
            </>
          )}
        </>
      ) : (
        <p>Loading status…</p>
      )}
      <h3>Configuration history</h3>
      <p>
        Restore replaces both active settings and the draft. Only versions
        recorded by this release are available.
      </p>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Select</th>
              <th>Revision</th>
              <th>Organization / profile</th>
              <th>Action / date</th>
            </tr>
          </thead>
          <tbody>
            {history.map((v) => (
              <tr key={v.id}>
                <td>
                  <input
                    type="radio"
                    name="version"
                    aria-label={`Select revision ${v.revision}`}
                    checked={selected === v.id}
                    onChange={() => {
                      setSelected(v.id);
                      setRollbackConfirmed(false);
                    }}
                  />
                </td>
                <td>{v.revision}</td>
                <td>
                  {v.settings.organizationName}
                  <br />
                  {v.settings.profileName}
                </td>
                <td>
                  {v.action}
                  <br />
                  {new Date(v.createdAt).toLocaleString()}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!history.length && <p>No versions on this page.</p>}
      <div className="actions">
        <button
          className="secondary"
          disabled={busy || historyPage === 0}
          onClick={() => setHistoryPage((p) => p - 1)}
        >
          Previous versions
        </button>
        <button
          className="secondary"
          disabled={busy || history.length < 50}
          onClick={() => setHistoryPage((p) => p + 1)}
        >
          Next versions
        </button>
      </div>
      <label className="check">
        <input
          type="checkbox"
          checked={rollbackConfirmed}
          disabled={selected === null}
          onChange={(e) => setRollbackConfirmed(e.target.checked)}
        />
        Restore the selected version and replace my draft.
      </label>
      <button
        disabled={busy || selected === null || !rollbackConfirmed}
        onClick={() =>
          run(async () => {
            await request("configuration/rollback", {
              versionId: selected,
              revision,
            });
            await onChange();
            await load();
            setRollbackConfirmed(false);
            setMessage("Configuration restored as a new revision.");
          })
        }
      >
        Restore selected version
      </button>
      <h3>Audit records</h3>
      <p>
        Local-administrator actions; credentials and content declarations are
        excluded.
      </p>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Action</th>
              <th>Actor / reference</th>
            </tr>
          </thead>
          <tbody>
            {events.map((e) => (
              <tr key={e.id}>
                <td>{new Date(e.createdAt).toLocaleString()}</td>
                <td>{e.action}</td>
                <td className="fingerprint">
                  {e.actor}
                  <br />
                  {e.reference}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!events.length && <p>No audit records on this page.</p>}
      <div className="actions">
        <button
          className="secondary"
          disabled={busy || auditPage === 0}
          onClick={() => setAuditPage((p) => p - 1)}
        >
          Previous events
        </button>
        <button
          className="secondary"
          disabled={busy || events.length < 50}
          onClick={() => setAuditPage((p) => p + 1)}
        >
          Next events
        </button>
      </div>
      <p role="status" aria-live="polite">
        {busy ? "Loading administration…" : message}
      </p>
    </section>
  );
}
