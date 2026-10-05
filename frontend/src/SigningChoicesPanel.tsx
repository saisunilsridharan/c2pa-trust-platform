import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
export type SigningChoice = {
  id: string;
  revision: number;
  label: string;
  profileRevision: number;
  fingerprint: string;
  settings: {
    organizationName: string;
    profileName: string;
    formats: string[];
    maxUploadMb: number;
    requireAiDisclosure: boolean;
  };
  development: boolean;
  enabled: boolean;
  available: boolean;
  createdAt: string;
};
export function SigningChoiceSelector({
  token,
  value,
  onChange,
}: {
  token: string;
  value: SigningChoice | null;
  onChange: (value: SigningChoice | null) => void;
}) {
  const fetch = workspaceFetch();
  const [choices, setChoices] = useState<SigningChoice[]>([]),
    [page, setPage] = useState(0),
    [message, setMessage] = useState("");
  async function load() {
    const r = await fetch("/api/v1/portal/signing-options?page=" + page, {
      headers: { "X-Admin-Token": token },
    });
    if (!r.ok) throw new Error(`Signing choices unavailable (${r.status}).`);
    setChoices(await r.json());
  }
  useEffect(() => {
    let live = true;
    fetch("/api/v1/portal/signing-options?page=" + page, {
      headers: { "X-Admin-Token": token },
    })
      .then(async (r) => {
        if (!r.ok)
          throw new Error(`Signing choices unavailable (${r.status}).`);
        const rows = await r.json();
        if (live) setChoices(rows);
      })
      .catch((e) => {
        if (live) setMessage(e.message);
      });
    return () => {
      live = false;
    };
  }, [token, page]);
  return (
    <>
      <label>
        Approved profile & signing certificate
        <select
          value={value?.id ?? ""}
          onChange={(e) =>
            onChange(choices.find((c) => c.id === e.target.value) ?? null)
          }
        >
          <option value="">Current workspace profile & certificate</option>
          {value && !choices.some((c) => c.id === value.id) && (
            <option value={value.id}>{value.label} · selected</option>
          )}
          {choices.map((c) => (
            <option key={c.id} value={c.id} disabled={!c.available}>
              {c.label} · {c.settings.profileName} ·{" "}
              {c.fingerprint.slice(0, 12)}
              {c.available ? "" : " · unavailable"}
            </option>
          ))}
        </select>
      </label>
      <div className="actions">
        <button disabled={!page} onClick={() => setPage(page - 1)}>
          Previous choices
        </button>
        <button
          disabled={choices.length < 50}
          onClick={() => setPage(page + 1)}
        >
          Next choices
        </button>
        <button onClick={() => load().catch((e) => setMessage(e.message))}>
          Refresh choices
        </button>
      </div>
      {value && (
        <p>
          Certificate SHA-256: <code>{value.fingerprint}</code>.{" "}
          {value.development
            ? "Development certificate; untrusted."
            : "Private certificate; public trust unverified."}{" "}
          No trusted timestamp.
        </p>
      )}
      <p role="status">{message}</p>
    </>
  );
}
export default function SigningChoicesPanel({
  token,
  profileRevision,
  fingerprint,
}: {
  token: string;
  profileRevision: number | null;
  fingerprint: string | null;
}) {
  const fetch = workspaceFetch();
  const [choices, setChoices] = useState<SigningChoice[]>([]),
    [label, setLabel] = useState(""),
    [page, setPage] = useState(0),
    [ack, setAck] = useState(false),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function request(path = "", method = "GET", body?: unknown) {
    const r = await fetch("/api/v1/admin/signing-options" + path, {
      method,
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!r.ok)
      throw new Error(
        r.status === 409
          ? "Profile, certificate or choice changed. Refresh and review again."
          : `Signing choice request failed (${r.status}).`,
      );
    return r.json();
  }
  async function load() {
    setChoices(await request("?page=" + page));
  }
  useEffect(() => {
    let live = true;
    request("?page=" + page)
      .then((rows) => {
        if (live) setChoices(rows);
      })
      .catch((e) => {
        if (live) setMessage(e.message);
      });
    return () => {
      live = false;
    };
  }, [token, page]);
  useEffect(() => setAck(false), [profileRevision, fingerprint]);
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
      <h2>Approved signing choices</h2>
      <p>
        Publish the current active profile and certificate as a named choice.
        Activate another profile or import/rotate its certificate, then publish
        another choice. Users can select any enabled choice. Published claims
        and certificate references stay fixed; later changes to the workspace
        default affect future default jobs.
      </p>
      <p>
        Current profile revision: {profileRevision ?? "unconfigured"}.
        Certificate: <code>{fingerprint ?? "unconfigured"}</code>.
      </p>
      <label>
        Choice name
        <input
          value={label}
          maxLength={120}
          onChange={(e) => {
            setLabel(e.target.value);
            setAck(false);
          }}
        />
      </label>
      <label className="check">
        <input
          type="checkbox"
          checked={ack}
          onChange={(e) => setAck(e.target.checked)}
        />
        I approve this profile and certificate for workspace signing. Public
        trust remains unverified.
      </label>
      <button
        disabled={
          busy ||
          !label.trim() ||
          !ack ||
          profileRevision == null ||
          !fingerprint
        }
        onClick={() =>
          run(async () => {
            await request("", "POST", {
              label,
              expectedProfileRevision: profileRevision,
              expectedIdentityFingerprint: fingerprint,
              acknowledgePublicClaims: true,
            });
            setLabel("");
            setAck(false);
            await load();
            setMessage("Signing choice published.");
          })
        }
      >
        Publish signing choice
      </button>
      <button disabled={busy} onClick={() => run(load)}>
        Refresh choices
      </button>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Choice / profile</th>
              <th>Certificate</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {choices.map((c) => (
              <tr key={c.id}>
                <td>
                  {c.label}
                  <br />
                  {c.settings.profileName} · {c.settings.organizationName} ·
                  revision {c.profileRevision}
                </td>
                <td>
                  <code>{c.fingerprint}</code>
                  <br />
                  {c.development ? "Development" : "Private certificate"}
                </td>
                <td>
                  {c.enabled ? "Enabled" : "Withdrawn"} ·{" "}
                  {c.available ? "available" : "unavailable"}
                  <button
                    disabled={busy || (!c.enabled && !c.available)}
                    onClick={() =>
                      run(async () => {
                        await request("/" + c.id, "PUT", {
                          revision: c.revision,
                          enabled: !c.enabled,
                        });
                        await load();
                        setMessage(
                          c.enabled
                            ? "Choice withdrawn for future requests. Captured jobs retain their reviewed configuration."
                            : "Choice enabled.",
                        );
                      })
                    }
                  >
                    {c.enabled ? "Withdraw" : "Enable"}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="actions">
        <button disabled={busy || !page} onClick={() => setPage(page - 1)}>
          Previous choices
        </button>
        <button
          disabled={busy || choices.length < 50}
          onClick={() => setPage(page + 1)}
        >
          Next choices
        </button>
      </div>
      <p role="status">{busy ? "Updating choices…" : message}</p>
    </section>
  );
}
