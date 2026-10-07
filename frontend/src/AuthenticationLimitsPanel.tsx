import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Policy = {
  revision: number;
  windowSeconds: number;
  perAddressLimit: number;
  globalLimit: number;
  trustedProxyCidrs: string;
};
export default function AuthenticationLimitsPanel({
  token,
}: {
  token: string;
}) {
  const fetch = workspaceFetch();
  const [policy, setPolicy] = useState<Policy | null>(null),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function request(method = "GET", body?: Policy) {
    const response = await fetch("/api/v1/admin/authentication/rate-limits", {
      method,
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!response.ok)
      throw new Error(
        response.status === 409
          ? "Policy changed. Refresh and review again."
          : `Authentication policy request failed (${response.status}). Check limits and proxy CIDRs.`,
      );
    return response.json();
  }
  useEffect(() => {
    let live = true;
    request()
      .then((p) => {
        if (live) setPolicy(p);
      })
      .catch((e) => {
        if (live) setMessage(e.message);
      });
    return () => {
      live = false;
    };
  }, [token]);
  async function run(save: boolean) {
    setBusy(true);
    setMessage("");
    try {
      setPolicy(
        await request(save ? "PUT" : "GET", save ? policy! : undefined),
      );
      setMessage(
        save ? "Shared authentication limits saved." : "Policy refreshed.",
      );
    } catch (e) {
      setMessage(e instanceof Error ? e.message : "Request failed");
    } finally {
      setBusy(false);
    }
  }
  return (
    <section>
      <h2>Shared login rate limits</h2>
      <details className="help-details">
        <summary>More info</summary>
        <p>
          Limits cover password login, recovery, enrollment and private-login
          start/completion across application instances sharing the database.
          Account lockout remains separate. Rejected requests return a retry
          time before expensive authentication work. These application controls
          complement your network edge protections.
        </p>
      </details>
      {policy && (
        <>
          <label>
            Window in seconds
            <input
              type="number"
              min={10}
              max={300}
              value={policy.windowSeconds}
              onChange={(e) =>
                setPolicy({ ...policy, windowSeconds: Number(e.target.value) })
              }
            />
          </label>
          <label>
            Requests per client address
            <input
              type="number"
              min={5}
              max={300}
              value={policy.perAddressLimit}
              onChange={(e) =>
                setPolicy({
                  ...policy,
                  perAddressLimit: Number(e.target.value),
                })
              }
            />
          </label>
          <label>
            Total authentication requests across instances
            <input
              type="number"
              min={20}
              max={10000}
              value={policy.globalLimit}
              onChange={(e) =>
                setPolicy({ ...policy, globalLimit: Number(e.target.value) })
              }
            />
          </label>
          <label>
            Trusted reverse-proxy CIDRs
            <textarea
              maxLength={8000}
              placeholder="Leave empty for direct connections"
              value={policy.trustedProxyCidrs}
              onChange={(e) =>
                setPolicy({ ...policy, trustedProxyCidrs: e.target.value })
              }
            />
          </label>
          <details className="help-details">
            <summary>More info</summary>
            <p>
              Only trust proxy addresses you control. Forwarded addresses are
              ignored for all other peers; trusted proxies must correctly append
              or replace X-Forwarded-For. Shared NAT users count together.
              Policy changes preserve current counters and apply immediately.
            </p>
          </details>
          <button disabled={busy} onClick={() => run(true)}>
            Save authentication policy
          </button>
        </>
      )}
      <button disabled={busy} onClick={() => run(false)}>
        Refresh policy
      </button>
      {message && <p role="status">{message}</p>}
    </section>
  );
}
