import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
import type { SigningChoice } from "./SigningChoicesPanel";
type Config = {
  enabled: boolean;
  requireCoveredSigning: boolean;
  issuerCertificatesPem: string;
  crlsPem: string;
  onlineOcsp?: {
    endpoint: string;
    tlsCaPem: string;
    allowLoopbackHttp: boolean;
  } | null;
};
type Version = {
  id: string;
  configuration: Config;
  testedAt: string | null;
  current: boolean;
  summary: {
    issuers: number;
    crls: number;
    revokedEntries: number;
    nextUpdate: string | null;
  } | null;
};
type State = {
  revision: number;
  draft: Version | null;
  active: Version | null;
};
const initial: Config = {
  enabled: false,
  requireCoveredSigning: true,
  issuerCertificatesPem: "",
  crlsPem: "",
  onlineOcsp: null,
};
export default function RevocationPanel({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [state, setState] = useState<State | null>(null),
    [form, setForm] = useState(initial),
    [versions, setVersions] = useState<Version[]>([]),
    [selected, setSelected] = useState(""),
    [choices, setChoices] = useState<SigningChoice[]>([]),
    [choice, setChoice] = useState(""),
    [expectRevoked, setExpectRevoked] = useState(false),
    [ack, setAck] = useState(false),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState(""),
    [page, setPage] = useState(0),
    [choicePage, setChoicePage] = useState(0);
  async function request(path = "", method = "GET", body?: unknown) {
    const r = await fetch("/api/v1/admin/revocation" + path, {
      method,
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!r.ok)
      throw new Error(
        `Revocation request failed (${r.status}). Refresh and review issuer signatures, CRL freshness, certificate coverage and expected test outcome.`,
      );
    return r.json();
  }
  async function values() {
    const r = await fetch("/api/v1/admin/signing-options?page=" + choicePage, {
      headers: { "X-Admin-Token": token },
    });
    if (!r.ok) throw new Error("Signing choices unavailable");
    return Promise.all([request(), request("/history?page=" + page), r.json()]);
  }
  async function load() {
    const [s, v, c] = await values();
    setState(s);
    setVersions(v);
    setChoices(c);
  }
  useEffect(() => {
    let live = true;
    values()
      .then(([s, v, c]) => {
        if (live) {
          setState(s);
          setVersions(v);
          setChoices(c);
          setForm(s.draft?.configuration ?? initial);
          setSelected(s.draft?.id ?? "");
        }
      })
      .catch((e) => {
        if (live) setMessage(e.message);
      });
    return () => {
      live = false;
    };
  }, [token, page, choicePage]);
  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setMessage("");
    try {
      await action();
      await load();
      setMessage("Revocation policy updated.");
    } catch (e) {
      setMessage(e instanceof Error ? e.message : "Request failed");
    } finally {
      setBusy(false);
    }
  }
  const version =
    versions.find((v) => v.id === selected) ??
    (state?.draft?.id === selected ? state.draft : null);
  return (
    <section className="card">
      <h2>Private certificate revocation</h2>
      <details className="help-details">
        <summary>More info</summary>
        <p>
          Upload complete PEM CRLs and their trusted CA issuer certificates.
          Enabled enforcement checks the current policy for immediate signing,
          queued jobs and readiness probes. Revoked certificates and stale CRLs
          block signing. This policy applies to manifest signers. It does not
          check timestamp or ingredient revocation, change stored verification
          reports, or establish public trust.
        </p>
      </details>
      <p>
        Active version: {state?.active?.id ?? "None"} ·{" "}
        {state?.active?.configuration.enabled
          ? state.active.current
            ? "Enforced"
            : "Invalid or expired — signing blocked"
          : "Enforcement disabled"}
      </p>
      {state?.active?.summary?.nextUpdate && (
        <p>
          Refresh CRLs before{" "}
          {new Date(state.active.summary.nextUpdate).toLocaleString()}.
        </p>
      )}
      <label>
        <input
          type="checkbox"
          checked={form.enabled}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
        />
        Enable CRL enforcement
      </label>
      <label>
        <input
          type="checkbox"
          checked={form.requireCoveredSigning}
          disabled={!form.enabled}
          onChange={(e) =>
            setForm({ ...form, requireCoveredSigning: e.target.checked })
          }
        />
        Require CRL coverage for every non-root signing certificate
      </label>
      <p>
        If coverage is optional, certificates from other issuers may sign.
        Uploaded CRLs must still remain current. Scoped, indirect and delta CRLs
        are unsupported.
      </p>
      <label>
        Trusted CRL issuer certificates (PEM)
        <textarea
          rows={5}
          value={form.issuerCertificatesPem}
          disabled={!form.enabled}
          onChange={(e) =>
            setForm({ ...form, issuerCertificatesPem: e.target.value })
          }
        />
      </label>
      <label>
        Complete signed CRLs (PEM)
        <textarea
          rows={7}
          value={form.crlsPem}
          disabled={!form.enabled}
          onChange={(e) => setForm({ ...form, crlsPem: e.target.value })}
        />
      </label>
      <button
        disabled={busy || !state}
        onClick={() =>
          run(async () => {
            const s = await request("/draft", "PUT", {
              revision: state?.revision,
              configuration: form,
            });
            setSelected(s.draft.id);
            setForm(s.draft.configuration);
          })
        }
      >
        Save
      </button>
      <label>
        <input
          type="checkbox"
          checked={!!form.onlineOcsp}
          disabled={!form.enabled}
          onChange={(e) =>
            setForm({
              ...form,
              onlineOcsp: e.target.checked
                ? { endpoint: "", tlsCaPem: "", allowLoopbackHttp: false }
                : null,
            })
          }
        />
        Also require a fresh online OCSP response
      </label>
      {form.onlineOcsp && (
        <>
          <details className="help-details">
            <summary>More info</summary>
            <p>
              OCSP adds online checks to the uploaded CRLs for covered signing
              certificates. The responder must echo request nonces, return
              current responses with nextUpdate, and sign as the issuer or its
              authorized OCSP responder. Outages, unknown status and invalid
              responses block signing. This private policy does not establish
              official public trust.
            </p>
          </details>
          <label>
            OCSP endpoint
            <input
              type="url"
              value={form.onlineOcsp.endpoint}
              disabled={!form.enabled}
              onChange={(e) =>
                setForm({
                  ...form,
                  onlineOcsp: { ...form.onlineOcsp!, endpoint: e.target.value },
                })
              }
            />
          </label>
          <label>
            Private TLS CA certificates (PEM, optional)
            <textarea
              rows={4}
              value={form.onlineOcsp.tlsCaPem}
              disabled={!form.enabled}
              onChange={(e) =>
                setForm({
                  ...form,
                  onlineOcsp: { ...form.onlineOcsp!, tlsCaPem: e.target.value },
                })
              }
            />
          </label>
          <label>
            <input
              type="checkbox"
              checked={form.onlineOcsp.allowLoopbackHttp}
              disabled={!form.enabled}
              onChange={(e) =>
                setForm({
                  ...form,
                  onlineOcsp: {
                    ...form.onlineOcsp!,
                    allowLoopbackHttp: e.target.checked,
                  },
                })
              }
            />
            Allow loopback HTTP for local development
          </label>
        </>
      )}
      <label>
        Policy version
        <select
          value={selected}
          onChange={(e) => {
            setSelected(e.target.value);
            const v = versions.find((v) => v.id === e.target.value);
            if (v) setForm(v.configuration);
          }}
        >
          <option value="">Select version</option>
          {versions.map((v) => (
            <option key={v.id} value={v.id}>
              {v.id} · {v.current ? "current" : "expired/invalid"} ·{" "}
              {v.testedAt ? "tested" : "untested"}
            </option>
          ))}
        </select>
      </label>
      {version?.summary && (
        <p>
          {version.summary.issuers} issuers · {version.summary.crls} CRLs ·{" "}
          {version.summary.revokedEntries} revoked entries
        </p>
      )}
      <label>
        Signing choice for policy test
        <select value={choice} onChange={(e) => setChoice(e.target.value)}>
          <option value="">Select choice</option>
          {choices.map((c) => (
            <option key={c.id} value={c.id}>
              {c.label} · {c.fingerprint}
            </option>
          ))}
        </select>
      </label>
      <button
        disabled={busy || choicePage === 0}
        onClick={() => setChoicePage(choicePage - 1)}
      >
        Back
      </button>
      <button
        disabled={busy || choices.length < 50}
        onClick={() => setChoicePage(choicePage + 1)}
      >
        Next
      </button>
      <label>
        <input
          type="checkbox"
          checked={expectRevoked}
          onChange={(e) => setExpectRevoked(e.target.checked)}
        />
        Expect this certificate to be revoked
      </label>
      <button
        disabled={
          busy ||
          !version ||
          !state ||
          (version.configuration.enabled && !choice)
        }
        onClick={() =>
          run(() =>
            request("/test", "POST", {
              revision: state?.revision,
              versionId: selected,
              signingChoiceId: choice,
              expectRevoked,
            }),
          )
        }
      >
        Test expected outcome
      </button>
      <label>
        <input
          type="checkbox"
          checked={ack}
          onChange={(e) => setAck(e.target.checked)}
        />
        I authorize this policy for all new signing attempts, including queued
        jobs.
      </label>
      <button
        disabled={
          busy || !ack || !state || !version?.testedAt || !version.current
        }
        onClick={() =>
          run(() =>
            request("/activate", "POST", {
              revision: state?.revision,
              versionId: selected,
              acknowledgeSigningEnforcement: ack,
            }),
          )
        }
      >
        Activate reviewed version
      </button>
      <button disabled={busy} onClick={() => run(load)}>
        Refresh
      </button>
      <button disabled={busy || page === 0} onClick={() => setPage(page - 1)}>
        Previous versions
      </button>
      <button
        disabled={busy || versions.length < 50}
        onClick={() => setPage(page + 1)}
      >
        Next versions
      </button>
      <p>
        To update CRLs or disable enforcement, save, test and activate a new
        version. Refresh is manual; schedule it before the earliest CRL expiry.
      </p>
      {message && <p role="status">{message}</p>}
    </section>
  );
}

export function RevocationStatus({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [status, setStatus] = useState<{
      enabled: boolean;
      current: boolean;
      requireCoveredSigning: boolean;
      nextUpdate: string | null;
    } | null>(null),
    [message, setMessage] = useState("");
  async function load() {
    const r = await fetch("/api/v1/portal/revocation", {
      headers: { "X-Admin-Token": token },
    });
    if (!r.ok) throw new Error(`Revocation status unavailable (${r.status}).`);
    setStatus(await r.json());
    setMessage("");
  }
  useEffect(() => {
    let live = true;
    fetch("/api/v1/portal/revocation", { headers: { "X-Admin-Token": token } })
      .then(async (r) => {
        if (!r.ok)
          throw new Error(`Revocation status unavailable (${r.status}).`);
        const s = await r.json();
        if (live) setStatus(s);
      })
      .catch((e) => {
        if (live) setMessage(e.message);
      });
    return () => {
      live = false;
    };
  }, [token]);
  return (
    <>
      <p>
        Certificate revocation:{" "}
        {status
          ? status.enabled
            ? status.current
              ? "Current private CRLs enforced"
              : "CRLs invalid or expired — signing blocked"
            : "Private CRL enforcement disabled"
          : "Checking…"}
        .{" "}
        {status?.enabled &&
          "Immediate and queued signing use the current revocation policy."}{" "}
        {status?.enabled &&
          status.requireCoveredSigning &&
          "All non-root signing certificates require issuer coverage."}
      </p>
      {status?.nextUpdate && (
        <p>CRLs expire: {new Date(status.nextUpdate).toLocaleString()}.</p>
      )}
      <button onClick={() => load().catch((e) => setMessage(e.message))}>
        Refresh
      </button>
      {message && <p role="status">{message}</p>}
    </>
  );
}
