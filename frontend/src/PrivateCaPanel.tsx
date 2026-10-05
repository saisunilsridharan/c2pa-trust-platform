import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Config = {
  enabled: boolean;
  endpoint: string;
  provisioner: string;
  jwkCredential: string;
  tlsCaPem: string;
  issuanceAnchorsPem: string;
  validityHours: number;
  allowLoopbackHttp: boolean;
};
type Version = { id: string; configuration: Config; testedAt: string | null };
type State = {
  revision: number;
  draft: Version | null;
  active: Version | null;
};
type Identity = {
  id: string;
  fingerprint: string;
  configuration: { keyAlias: string };
  expiresAt: string;
};
const initial: Config = {
  enabled: false,
  endpoint: "",
  provisioner: "",
  jwkCredential: "",
  tlsCaPem: "",
  issuanceAnchorsPem: "",
  validityHours: 24,
  allowLoopbackHttp: false,
};
export default function PrivateCaPanel({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [state, setState] = useState<State | null>(null),
    [form, setForm] = useState(initial),
    [history, setHistory] = useState<Version[]>([]),
    [identities, setIdentities] = useState<Identity[]>([]),
    [selected, setSelected] = useState(""),
    [identityId, setIdentityId] = useState(""),
    [subject, setSubject] = useState({
      commonName: "",
      organization: "",
      country: "",
    }),
    [page, setPage] = useState(0),
    [ack, setAck] = useState(false),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function request(path = "", method = "GET", body?: unknown) {
    const response = await fetch("/api/v1/admin/private-ca" + path, {
      method,
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok)
      throw new Error(
        `Private CA request failed (${response.status}). Refresh and check the reviewed identity, provider version, provisioner/template, TLS, anchors and subject.`,
      );
    return response.json();
  }
  async function values() {
    const hardware = await fetch("/api/v1/admin/hardware-identities", {
      headers: { "X-Admin-Token": token },
    });
    if (!hardware.ok) throw new Error("Hardware identity list unavailable");
    return Promise.all([
      request(),
      request("/history?page=" + page),
      hardware.json(),
    ]);
  }
  async function load() {
    const [s, h, i] = await values();
    setState(s);
    setHistory(h);
    setIdentities(i);
  }
  useEffect(() => {
    let live = true;
    values()
      .then(([s, h, i]) => {
        if (live) {
          setState(s);
          setHistory(h);
          setIdentities(i);
          setForm(
            Object.fromEntries(
              Object.entries(initial).map(([key, value]) => [
                key,
                s.draft?.configuration?.[key] ?? value,
              ]),
            ) as Config,
          );
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
  const identity = identities.find((i) => i.id === identityId);
  const selectedVersion = history.find((v) => v.id === selected);
  function configuration(key: keyof Config, value: string | number | boolean) {
    setForm({ ...form, [key]: value });
    setAck(false);
  }
  function issueBody(version: string) {
    return {
      revision: state!.revision,
      versionId: version,
      identityId,
      expectedIdentityFingerprint: identity?.fingerprint ?? "",
      subject,
      acknowledgePrivateIssuance: true,
    };
  }
  async function issue(test: boolean) {
    const version = test ? selected : state!.active!.id;
    const result = await request(
      test ? "/test" : "/issue",
      "POST",
      issueBody(version),
    );
    await load();
    setAck(false);
    setMessage(
      result.identity
        ? `Certificate ${result.identity.fingerprint.slice(0, 16)} issued and SDK-tested as identity ${result.identity.id}. Review it in hardware identities and approve with a profile. Existing signing choices stay unchanged.`
        : "Disabled issuer version tested.",
    );
  }
  return (
    <section>
      <h2>Private CA issuance</h2>
      <p>
        This connector uses Smallstep-compatible /sign requests with an
        encrypted ES256 JWK provisioner credential. It creates a token-signed
        CSR, requests a certificate and verifies its key, subject, DNS name,
        validity and configured CA chain before an actual C2PA signing test.
        Test requests also issue real certificate drafts.
      </p>
      <p>
        Your CA provisioner needs a C2PA signing template with
        digital-signature, C2PA and email-protection usage. The default
        web-server template does not meet this requirement. Requests use a
        DNS-style common name, such as signer.portal.internal. Issuance trust is
        separate from workspace signing trust and public trust.
      </p>
      <label>
        <input
          type="checkbox"
          checked={form.enabled}
          onChange={(e) => configuration("enabled", e.target.checked)}
        />
        Enable connected issuance
      </label>
      {form.enabled && (
        <>
          <label>
            Private CA base URL
            <input
              value={form.endpoint}
              onChange={(e) => configuration("endpoint", e.target.value)}
            />
          </label>
          <label>
            Provisioner name
            <input
              maxLength={120}
              value={form.provisioner}
              onChange={(e) => configuration("provisioner", e.target.value)}
            />
          </label>
          <label>
            Private ES256 JWK credential ID
            <input
              value={form.jwkCredential}
              onChange={(e) => configuration("jwkCredential", e.target.value)}
            />
          </label>
          <label>
            TLS CA PEM
            <textarea
              maxLength={12000}
              value={form.tlsCaPem ?? ""}
              onChange={(e) => configuration("tlsCaPem", e.target.value)}
            />
          </label>
          <label>
            Issuance CA anchors PEM
            <textarea
              maxLength={60000}
              value={form.issuanceAnchorsPem ?? ""}
              onChange={(e) =>
                configuration("issuanceAnchorsPem", e.target.value)
              }
            />
          </label>
          <label>
            Requested validity in hours
            <input
              type="number"
              min={1}
              max={720}
              value={form.validityHours}
              onChange={(e) =>
                configuration("validityHours", Number(e.target.value))
              }
            />
          </label>
          <label>
            <input
              type="checkbox"
              checked={form.allowLoopbackHttp}
              onChange={(e) =>
                configuration("allowLoopbackHttp", e.target.checked)
              }
            />
            Allow loopback HTTP for development
          </label>
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
          })
        }
      >
        Save immutable issuer draft
      </button>
      <p>
        Active version: {state?.active?.id ?? "none"}.{" "}
        {state?.active?.configuration.enabled
          ? "Connected issuance enabled."
          : "Connected issuance disabled."}
      </p>
      <label>
        Issuer version
        <select
          value={selected}
          onChange={(e) => {
            setSelected(e.target.value);
            setAck(false);
          }}
        >
          <option value="">Select version</option>
          {history.map((v) => (
            <option key={v.id} value={v.id}>
              {v.id} · {v.configuration.enabled ? "enabled" : "disabled"} ·{" "}
              {v.testedAt ? "tested" : "untested"}
            </option>
          ))}
        </select>
      </label>
      <label>
        Existing hardware identity
        <select
          value={identityId}
          onChange={(e) => {
            setIdentityId(e.target.value);
            setAck(false);
          }}
        >
          <option value="">Select token identity</option>
          {identities.map((i) => (
            <option key={i.id} value={i.id}>
              {i.configuration.keyAlias} · {i.fingerprint.slice(0, 12)}
            </option>
          ))}
        </select>
      </label>
      {identity && (
        <p>
          Reviewed certificate: {identity.fingerprint}. Expires{" "}
          {new Date(identity.expiresAt).toLocaleString()}.
        </p>
      )}
      <label>
        DNS-style common name
        <input
          maxLength={200}
          value={subject.commonName}
          onChange={(e) => {
            setSubject({ ...subject, commonName: e.target.value });
            setAck(false);
          }}
        />
      </label>
      <label>
        Organization
        <input
          maxLength={200}
          value={subject.organization}
          onChange={(e) => {
            setSubject({ ...subject, organization: e.target.value });
            setAck(false);
          }}
        />
      </label>
      <label>
        Country code
        <input
          maxLength={2}
          value={subject.country}
          onChange={(e) => {
            setSubject({ ...subject, country: e.target.value.toUpperCase() });
            setAck(false);
          }}
        />
      </label>
      <label>
        <input
          type="checkbox"
          checked={ack}
          onChange={(e) => setAck(e.target.checked)}
        />
        I reviewed the selected issuer, token certificate and requested
        identity. I authorize certificate issuance, including tests.
      </label>
      <button
        disabled={
          busy ||
          !ack ||
          !state ||
          !selected ||
          (selectedVersion?.configuration.enabled &&
            (!identity || !subject.commonName.trim()))
        }
        onClick={() => run(() => issue(true))}
      >
        Test issuer with real certificate request
      </button>
      <button
        disabled={busy || !ack || !state || !selectedVersion?.testedAt}
        onClick={() =>
          run(async () => {
            await request("/activate", "POST", {
              revision: state!.revision,
              versionId: selected,
              acknowledgePrivateIssuance: true,
            });
            await load();
            setAck(false);
          })
        }
      >
        Activate tested issuer
      </button>
      <button
        disabled={
          busy ||
          !ack ||
          !state?.active?.configuration.enabled ||
          !identity ||
          !subject.commonName.trim()
        }
        onClick={() => run(() => issue(false))}
      >
        Issue replacement through active CA
      </button>
      <button disabled={busy} onClick={() => run(load)}>
        Refresh
      </button>
      <button disabled={busy || page === 0} onClick={() => setPage(page - 1)}>
        Previous issuer versions
      </button>
      <button
        disabled={busy || history.length < 50}
        onClick={() => setPage(page + 1)}
      >
        Next issuer versions
      </button>
      <p>
        Manual issuance requires separate approval. Scheduled renewal plans
        below can authorize automatic publication of tested replacements.
      </p>
      {message && <p role="status">{message}</p>}
    </section>
  );
}
