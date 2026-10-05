import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Config = {
  enabled: boolean;
  issuer: string;
  clientId: string;
  clientSecretCredential: string;
  redirectUri: string;
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
type Link = { id: number; userId: number; issuer: string; subject: string };
const initial: Config = {
  enabled: false,
  issuer: "",
  clientId: "",
  clientSecretCredential: "",
  redirectUri: "",
  allowLoopbackHttp: false,
  trustedCaPem: "",
};
export default function OidcAdministrationPanel({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [state, setState] = useState<State | null>(null),
    [form, setForm] = useState(initial),
    [history, setHistory] = useState<Version[]>([]),
    [links, setLinks] = useState<Link[]>([]),
    [username, setUsername] = useState(""),
    [subject, setSubject] = useState(""),
    [linkIssuer, setLinkIssuer] = useState(""),
    [selected, setSelected] = useState(""),
    [page, setPage] = useState(0),
    [linkPage, setLinkPage] = useState(0),
    [ack, setAck] = useState(false),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function request(path = "", method = "GET", body?: unknown) {
    const response = await fetch("/api/v1/admin/authentication/oidc" + path, {
      method,
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok)
      throw new Error(
        response.status === 409
          ? "Settings changed, a link exists or the version needs testing. Refresh before retrying."
          : `Private-login request failed (${response.status}). Check issuer, TLS, endpoints, client and account.`,
      );
    return response.json();
  }
  async function values() {
    return Promise.all([
      request(),
      request("/history?page=" + page),
      request("/links?page=" + linkPage),
    ]);
  }
  async function load() {
    const [s, h, l] = await values();
    setState(s);
    setHistory(h);
    setLinks(l);
  }
  useEffect(() => {
    let active = true;
    values()
      .then(([s, h, l]) => {
        if (active) {
          setState(s);
          setForm(s.draft?.configuration ?? initial);
          setHistory(h);
          setLinks(l);
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
  }, [token, page, linkPage]);
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
      <h2>Private OIDC login</h2>
      <p>
        Global login provider:{" "}
        {state?.active?.configuration.enabled ? "enabled" : "disabled"}. Local
        password login remains available. Users must have an existing account
        explicitly linked to the issuer and subject; email and provider roles do
        not grant access. Local MFA still applies.
      </p>
      <button disabled={busy} onClick={() => run(load)}>
        Refresh provider & links
      </button>
      <label className="check">
        <input
          type="checkbox"
          checked={form.enabled}
          onChange={(e) => update("enabled", e.target.checked)}
        />
        Enable private organization login
      </label>
      {form.enabled && (
        <>
          {(
            [
              ["issuer", "Exact issuer URL (no trailing slash)"],
              ["clientId", "Registered client ID"],
              ["redirectUri", "Portal callback URL ending /oidc/callback"],
              [
                "clientSecretCredential",
                "Client secret credential ID (optional; save it in Default workspace)",
              ],
            ] as const
          ).map(([key, label]) => (
            <label key={key}>
              {label}
              <input
                maxLength={2000}
                value={form[key] ?? ""}
                onChange={(e) => update(key, e.target.value)}
              />
            </label>
          ))}
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
            Allow HTTP only for loopback development services.
          </label>
          <p>
            Register the callback URL with your provider. Use authorization code
            flow, PKCE S256 and RS256 ID tokens. Discovery, authorization, token
            and key endpoints must share the issuer origin.
          </p>
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
            setMessage("Private-login draft saved. Test before activation.");
          })
        }
      >
        Save provider draft
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
          <option key={v.id} value={v.id}>
            {v.configuration.enabled ? "Enabled" : "Disabled"} ·{" "}
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
                await request("/test", "POST", {
                  revision: state!.revision,
                  versionId: selected,
                }),
              );
              await load();
              setMessage(
                "Discovery and signing-key test passed. Verify a linked-user login before rollout.",
              );
            })
          }
        >
          Test discovery & keys
        </button>
      </div>
      <label className="check">
        <input
          type="checkbox"
          checked={ack}
          onChange={(e) => setAck(e.target.checked)}
        />
        Use this tested provider for linked accounts and retain local password
        recovery.
      </label>
      <button
        disabled={busy || !state || !selected || !ack}
        onClick={() =>
          run(async () => {
            setState(
              await request("/activate", "POST", {
                revision: state!.revision,
                versionId: selected,
                acknowledgeActivation: true,
              }),
            );
            setAck(false);
            await load();
            setMessage("Provider version activated.");
          })
        }
      >
        Activate selected provider
      </button>
      <h3>Explicit account linking</h3>
      <label>
        Existing portal username
        <input
          maxLength={40}
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />
      </label>
      <label>
        Exact issuer
        <input
          maxLength={800}
          value={linkIssuer}
          onChange={(e) => setLinkIssuer(e.target.value)}
        />
      </label>
      <label>
        Provider subject identifier (sub)
        <input
          maxLength={255}
          value={subject}
          onChange={(e) => setSubject(e.target.value)}
        />
      </label>
      <button
        disabled={busy || !username || !linkIssuer || !subject}
        onClick={() =>
          run(async () => {
            await request("/links", "POST", {
              username,
              issuer: linkIssuer,
              subject,
            });
            setUsername("");
            setSubject("");
            await load();
            setMessage(
              "Existing account linked to the exact issuer and subject.",
            );
          })
        }
      >
        Link existing account
      </button>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Account ID</th>
              <th>Issuer / subject</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            {links.map((l) => (
              <tr key={l.id}>
                <td>{l.userId}</td>
                <td>
                  {l.issuer}
                  <br />
                  {l.subject}
                </td>
                <td>
                  <button
                    disabled={busy}
                    onClick={() =>
                      run(async () => {
                        await request("/links/" + l.id, "DELETE");
                        await load();
                        setMessage(
                          "Future provider logins unlinked. Existing sessions expire or can be revoked through user administration.",
                        );
                      })
                    }
                  >
                    Unlink
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="actions">
        <button
          disabled={busy || !linkPage}
          onClick={() => setLinkPage(linkPage - 1)}
        >
          Previous links
        </button>
        <button
          disabled={busy || links.length < 50}
          onClick={() => setLinkPage(linkPage + 1)}
        >
          Next links
        </button>
      </div>
      <p role="status">{busy ? "Updating private login…" : message}</p>
    </section>
  );
}
