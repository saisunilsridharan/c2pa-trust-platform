import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
import type { SigningChoice } from "./SigningChoicesPanel";
type Plan = {
  id: string;
  revision: number;
  currentChoiceId: string;
  providerVersion: string;
  subject: { commonName: string; organization: string; country: string };
  state: string;
  enabled: boolean;
  label: string;
  fingerprint: string;
  renewBeforeHours: number;
  lastRenewedAt: string | null;
  error: string | null;
};
export default function CertificateRenewalPanel({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [plans, setPlans] = useState<Plan[]>([]),
    [choices, setChoices] = useState<SigningChoice[]>([]),
    [provider, setProvider] = useState(""),
    [choiceId, setChoiceId] = useState(""),
    [subject, setSubject] = useState({
      commonName: "",
      organization: "",
      country: "",
    }),
    [hours, setHours] = useState(6),
    [ack, setAck] = useState(false),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState(""),
    [page, setPage] = useState(0),
    [choicePage, setChoicePage] = useState(0);
  async function request(path: string, method = "GET", body?: unknown) {
    const r = await fetch("/api/v1" + path, {
      method,
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!r.ok)
      throw new Error(
        `Renewal request failed (${r.status}). Refresh and review the active issuer, choice and plan.`,
      );
    return r.json();
  }
  async function load() {
    const [p, c, s] = await Promise.all([
      request("/admin/private-ca/renewals?page=" + page),
      request("/admin/signing-options?page=" + choicePage),
      request("/admin/private-ca"),
    ]);
    setPlans(p);
    setChoices(c);
    setProvider(s.active?.configuration.enabled ? s.active.id : "");
  }
  useEffect(() => {
    let live = true;
    Promise.all([
      request("/admin/private-ca/renewals?page=" + page),
      request("/admin/signing-options?page=" + choicePage),
      request("/admin/private-ca"),
    ])
      .then(([p, c, s]) => {
        if (live) {
          setPlans(p);
          setChoices(c);
          setProvider(s.active?.configuration.enabled ? s.active.id : "");
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
      setMessage(
        "Renewal settings updated. Refresh to see background progress.",
      );
    } catch (e) {
      setMessage(e instanceof Error ? e.message : "Request failed");
    } finally {
      setBusy(false);
    }
  }
  const selected = choices.find((c) => c.id === choiceId);
  return (
    <section className="card">
      <h2>Scheduled certificate renewal</h2>
      <details className="help-details">
        <summary>More info</summary>
        <p>
          Choose an approved hardware identity and active private issuer. Tested
          replacements keep the reviewed profile and hardware key. The preceding
          choice is withdrawn for future signing; existing jobs retain their
          certificates. Changing the issuer or withdrawing a choice blocks its
          plan.
        </p>
      </details>
      <p>
        Active issuer version: {provider || "Activate a tested issuer first"}
      </p>
      <label>
        Approved hardware choice
        <select value={choiceId} onChange={(e) => setChoiceId(e.target.value)}>
          <option value="">Select choice</option>
          {choices
            .filter((c) => c.enabled && c.provider === "PKCS11")
            .map((c) => (
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
        Certificate DNS common name
        <input
          value={subject.commonName}
          onChange={(e) =>
            setSubject({ ...subject, commonName: e.target.value })
          }
        />
      </label>
      <label>
        Organization
        <input
          value={subject.organization}
          onChange={(e) =>
            setSubject({ ...subject, organization: e.target.value })
          }
        />
      </label>
      <label>
        Country (two uppercase letters)
        <input
          maxLength={2}
          value={subject.country}
          onChange={(e) =>
            setSubject({ ...subject, country: e.target.value.toUpperCase() })
          }
        />
      </label>
      <label>
        Renew before expiry (hours)
        <input
          type="number"
          min={1}
          max={336}
          value={hours}
          onChange={(e) => setHours(Number(e.target.value))}
        />
      </label>
      <label>
        <input
          type="checkbox"
          checked={ack}
          onChange={(e) => setAck(e.target.checked)}
        />
        I authorize CA requests, automatic approval of tested replacements, and
        withdrawal of preceding choices.
      </label>
      <button
        disabled={busy || !ack || !provider || !selected || !subject.commonName}
        onClick={() =>
          run(() =>
            request("/admin/private-ca/renewals", "POST", {
              providerVersion: provider,
              choiceId,
              choiceRevision: selected?.revision,
              subject,
              renewBeforeHours: hours,
              acknowledgeAutomaticApproval: ack,
            }),
          )
        }
      >
        Create renewal plan
      </button>
      <button disabled={busy} onClick={() => run(load)}>
        Refresh
      </button>
      {plans.map((p) => (
        <article key={p.id}>
          <h3>{p.label}</h3>
          <p>Issuer version: {p.providerVersion}</p>
          <p>
            Subject: {p.subject.commonName} · {p.subject.organization} ·{" "}
            {p.subject.country}
          </p>
          <p>
            {p.state} · renew {p.renewBeforeHours} hours before expiry
          </p>
          <p>Certificate: {p.fingerprint}</p>
          {p.lastRenewedAt && (
            <p>Last renewal: {new Date(p.lastRenewedAt).toLocaleString()}</p>
          )}
          {p.error && <p role="status">{p.error}</p>}
          <button
            disabled={busy || (!p.enabled && !ack)}
            onClick={() =>
              run(async () => {
                let c: SigningChoice | undefined;
                for (let n = 0; n <= 10000; n++) {
                  const rows: SigningChoice[] = await request(
                    "/admin/signing-options?page=" + n,
                  );
                  c = rows.find((v) => v.id === p.currentChoiceId);
                  if (c || rows.length < 50) break;
                }
                if (!c) throw new Error("Refresh the reviewed signing choice.");
                return request("/admin/private-ca/renewals/" + p.id, "PUT", {
                  revision: p.revision,
                  enabled: !p.enabled,
                  choiceRevision: c.revision,
                  acknowledgeAutomaticApproval: ack,
                });
              })
            }
          >
            {p.enabled ? "Pause" : "Review and resume"}
          </button>
          <button
            disabled={busy || !ack || !p.enabled || p.state === "RUNNING"}
            onClick={() =>
              run(() =>
                request("/admin/private-ca/renewals/" + p.id + "/run", "POST", {
                  revision: p.revision,
                  acknowledgeAutomaticApproval: ack,
                }),
              )
            }
          >
            Renew now
          </button>
        </article>
      ))}
      <button disabled={busy || page === 0} onClick={() => setPage(page - 1)}>
        Previous plans
      </button>
      <button
        disabled={busy || plans.length < 50}
        onClick={() => setPage(page + 1)}
      >
        Next plans
      </button>
      <p>
        Pausing prevents an in-progress request from publishing a replacement.
        Issued drafts remain available for review.
      </p>
      {message && <p role="status">{message}</p>}
    </section>
  );
}
