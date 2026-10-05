import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Key = {
  id: string;
  label: string;
  scopes: string[];
  createdAt: string;
  expiresAt: string;
  revoked: boolean;
};
export default function ApiKeysPanel({
  token,
  canSign,
}: {
  token: string;
  canSign: boolean;
}) {
  const fetch = workspaceFetch();
  const [keys, setKeys] = useState<Key[]>([]),
    [label, setLabel] = useState(""),
    [scopes, setScopes] = useState(["READ"]),
    [days, setDays] = useState(30),
    [issued, setIssued] = useState(""),
    [page, setPage] = useState(0),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function request(path = "", method = "GET", body?: unknown) {
    const response = await fetch("/api/v1/auth/api-keys" + path, {
      method,
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok)
      throw new Error(
        `API key request failed (${response.status}). Check your workspace permissions and selected scopes.`,
      );
    return response.json();
  }
  async function load() {
    setKeys(await request(`?page=${page}`));
  }
  useEffect(() => {
    let active = true;
    request(`?page=${page}`)
      .then((k) => {
        if (active) setKeys(k);
      })
      .catch((e) => {
        if (active) setMessage(e.message);
      });
    return () => {
      active = false;
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
      <h2>Personal API keys</h2>
      <p>
        Keys belong to your selected workspace and have only the chosen
        operations. They cannot administer the portal. Account and membership
        permissions are checked on every request. Password changes and recovery
        revoke your keys.
      </p>
      <label>
        Key label
        <input
          maxLength={80}
          value={label}
          onChange={(e) => setLabel(e.target.value)}
        />
      </label>
      <label>
        Expires after days
        <input
          type="number"
          min={1}
          max={365}
          value={days}
          onChange={(e) => setDays(Number(e.target.value))}
        />
      </label>
      {["READ", "VERIFY", ...(canSign ? ["SIGN"] : [])].map((scope) => (
        <label className="check" key={scope}>
          <input
            type="checkbox"
            checked={scopes.includes(scope)}
            onChange={(e) =>
              setScopes(
                e.target.checked
                  ? [...scopes, scope]
                  : scopes.filter((s) => s !== scope),
              )
            }
          />
          {scope === "READ"
            ? "Read profiles, capabilities and your jobs"
            : scope === "VERIFY"
              ? "Inspect provenance"
              : "Sign content and retry your jobs"}
        </label>
      ))}
      <button
        disabled={
          busy ||
          !label.trim() ||
          !scopes.length ||
          days < 1 ||
          days > 365 ||
          !!issued
        }
        onClick={() =>
          run(async () => {
            const result = await request("", "POST", { label, scopes, days });
            setIssued(result.token);
            setLabel("");
            await load();
            setMessage("Key created. Save it securely; it is shown only once.");
          })
        }
      >
        Create scoped API key
      </button>
      {issued && (
        <>
          <label>
            New key (shown once)
            <input type="password" readOnly value={issued} autoComplete="off" />
          </label>
          <p>
            Use Authorization: Bearer with this key. Select the field to copy it
            securely.
          </p>
          <button
            onClick={() => {
              setIssued("");
              setMessage("Key hidden. It cannot be retrieved again.");
            }}
          >
            I saved the key securely — hide it
          </button>
        </>
      )}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Label / scopes</th>
              <th>Expires</th>
              <th>Status</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            {keys.map((k) => (
              <tr key={k.id}>
                <td>
                  {k.label}
                  <br />
                  {k.scopes.join(", ")}
                </td>
                <td>{new Date(k.expiresAt).toLocaleString()}</td>
                <td>
                  {k.revoked
                    ? "Revoked"
                    : new Date(k.expiresAt).getTime() < Date.now()
                      ? "Expired"
                      : "Active"}
                </td>
                <td>
                  <button
                    disabled={busy || k.revoked}
                    onClick={() =>
                      run(async () => {
                        await request("/" + k.id, "DELETE");
                        await load();
                        setMessage("Key revoked.");
                      })
                    }
                  >
                    Revoke
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="actions">
        <button disabled={busy || !page} onClick={() => setPage(page - 1)}>
          Previous keys
        </button>
        <button
          disabled={busy || keys.length < 50}
          onClick={() => setPage(page + 1)}
        >
          Next keys
        </button>
      </div>
      <p role="status">{busy ? "Updating API keys…" : message}</p>
    </section>
  );
}
