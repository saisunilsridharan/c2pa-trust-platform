import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Revocation = { enabled: boolean; requireCoveredSigning: boolean; issuerCertificatesPem: string; crlsPem: string; onlineOcsp: { endpoint: string; tlsCaPem: string; allowLoopbackHttp: boolean } };
type Config = { revocation?: Revocation | null; enabled: boolean; maxAgeHours: number; proxyEndpoint: string; tlsCaPem: string; acknowledgeTrustedProxy: boolean };
type Version = { id: string; configuration: Config; fetchedAt: string | null; testedAt: string | null; summary: { enabled: boolean; current: boolean; signerAnchors: number; tsaAnchors: number; expiresAt: string | null; signerSha256: string | null; tsaSha256: string | null } };
type State = { revision: number; draft: Version | null; active: Version | null };
const emptyRevocation: Revocation = { enabled: true, requireCoveredSigning: true, issuerCertificatesPem: "", crlsPem: "", onlineOcsp: { endpoint: "", tlsCaPem: "", allowLoopbackHttp: false } };
const initial: Config = { enabled: true, maxAgeHours: 24, proxyEndpoint: "", tlsCaPem: "", acknowledgeTrustedProxy: false };
export default function PublicTrustPanel({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [state, setState] = useState<State | null>(null), [form, setForm] = useState(initial), [versions, setVersions] = useState<Version[]>([]), [selected, setSelected] = useState("");
  const [sample, setSample] = useState<File | null>(null), [expected, setExpected] = useState(false), [ack, setAck] = useState(false), [busy, setBusy] = useState(false), [message, setMessage] = useState(""), [page, setPage] = useState(0);
  function updateRevocation(change: Partial<Revocation>) { setForm({ ...form, revocation: { ...(form.revocation || emptyRevocation), ...change } }); }
  function updateOcsp(change: Partial<Revocation["onlineOcsp"]>) { updateRevocation({ onlineOcsp: { ...(form.revocation?.onlineOcsp || emptyRevocation.onlineOcsp), ...change } }); }
  async function request(path = "", body?: unknown) {
    const r = await fetch("/api/v1/admin/public-trust" + path, { method: body === undefined ? "GET" : "POST", headers: { "X-Admin-Token": token, "Content-Type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body) });
    if (!r.ok) throw new Error(`Public trust request failed (${r.status}). Review official-source access, TLS, freshness, test outcome and revisions.`); return r.json();
  }
  async function load() { const [s, v] = await Promise.all([request(), request("/history?page=" + page)]); setState(s); setVersions(v); }
  useEffect(() => { let live = true; Promise.all([request(), request("/history?page=" + page)]).then(([s, v]) => { if (live) { setState(s); setVersions(v); } }).catch(e => { if (live) setMessage(e.message); }); return () => { live = false; }; }, [page]);
  async function run(action: () => Promise<void>) { setBusy(true); setMessage(""); try { await action(); await load(); } catch (e) { setMessage(e instanceof Error ? e.message : "Public trust operation failed"); } finally { setBusy(false); } }
  return <section className="card"><h2>Official C2PA public trust</h2>
    <p>The portal downloads the signing and TSA trust lists only from the official c2pa-org/conformance-public repository over verified HTTPS. Uploaded private roots cannot become official trust anchors. Public validation is reported separately from workspace private trust and does not certify the portal as a conforming C2PA product.</p>
    <p>Active version: {state?.active?.id || "none"} · {state?.active?.summary.current ? "current" : "refresh required"} · Signing anchors {state?.active?.summary.signerAnchors || 0} · TSA anchors {state?.active?.summary.tsaAnchors || 0}</p>
    <label><input type="checkbox" checked={form.enabled} onChange={e => setForm({ ...form, enabled: e.target.checked })} />Enable official public trust checks during content inspection</label>
    <label>Maximum cache age (hours, 1–168)<input type="number" min={1} max={168} value={form.maxAgeHours} onChange={e => setForm({ ...form, maxAgeHours: Number(e.target.value) })} /></label>
    <label>HTTP CONNECT proxy endpoint (optional)<input type="url" value={form.proxyEndpoint} placeholder="http://proxy.internal:3128" onChange={e => setForm({ ...form, proxyEndpoint: e.target.value })} /></label>
    <label>Trusted TLS CA certificates for source access (optional)<textarea rows={4} value={form.tlsCaPem} onChange={e => setForm({ ...form, tlsCaPem: e.target.value })} /></label>
    <label><input type="checkbox" checked={form.acknowledgeTrustedProxy} onChange={e => setForm({ ...form, acknowledgeTrustedProxy: e.target.checked })} />I trust this proxy and its configured TLS authority for official-source access.</label>
    <h3>Public signer revocation</h3>
    <label><input type="checkbox" checked={form.revocation?.enabled === true} onChange={e => setForm({ ...form, revocation: e.target.checked ? { ...emptyRevocation } : null })} />Require current complete CRLs and online OCSP for every certificate below the official signing anchor</label>
    {form.revocation?.enabled && <>
      <p>Configure a trusted OCSP service that covers these issuers. Unknown or unavailable status blocks public signer trust. These public issuer certificates verify status responses; they do not add official trust anchors. Activation requires a positive sample with official signer trust and good online status. Negative tests clear activation readiness.</p>
      <label>Issuer CA certificates (public PEM)<textarea rows={4} value={form.revocation.issuerCertificatesPem} onChange={e => updateRevocation({ issuerCertificatesPem: e.target.value })} /></label>
      <label>Fresh complete signed CRLs (PEM)<textarea rows={4} value={form.revocation.crlsPem} onChange={e => updateRevocation({ crlsPem: e.target.value })} /></label>
      <label>OCSP responder URL<input type="url" value={form.revocation.onlineOcsp.endpoint} onChange={e => updateOcsp({ endpoint: e.target.value })} /></label>
      <label>OCSP TLS CA certificates (optional public PEM)<textarea rows={3} value={form.revocation.onlineOcsp.tlsCaPem} onChange={e => updateOcsp({ tlsCaPem: e.target.value })} /></label>
      <label><input type="checkbox" checked={form.revocation.onlineOcsp.allowLoopbackHttp} onChange={e => updateOcsp({ allowLoopbackHttp: e.target.checked })} />Allow HTTP only to a loopback responder for development</label>
      <p>The source-access proxy also carries OCSP requests. Responses must match the request nonce and certificate, have authorized strong signatures and fresh status. Delegated responders require current issuer CRL coverage. Refresh CRLs before expiry.</p>
    </>}
    <button disabled={busy || !state} onClick={() => run(async () => { const s = await request("/fetch", { revision: state?.revision, configuration: form }); setSelected(s.draft.id); })}>{form.enabled ? "Download official lists as new draft" : "Save disabled draft"}</button>
    <label>Version<select value={selected} onChange={e => { setSelected(e.target.value); const v = versions.find(v => v.id === e.target.value); if (v) setForm(v.configuration); }}><option value="">Select a version</option>{versions.map(v => <option key={v.id} value={v.id}>{v.id} · {v.summary.current ? "current" : "stale/invalid"} · {v.testedAt ? "tested" : "untested"}</option>)}</select></label>
    {versions.find(v => v.id === selected) && <p>Source SHA-256: signing {versions.find(v => v.id === selected)?.summary.signerSha256 || "disabled"}; TSA {versions.find(v => v.id === selected)?.summary.tsaSha256 || "disabled"}. Cache expiry {versions.find(v => v.id === selected)?.summary.expiresAt || "not applicable"}.</p>}
    <label>Signed sample for the expected-outcome test<input type="file" onChange={e => setSample(e.target.files?.[0] || null)} /></label>
    <label><input type="checkbox" checked={expected} onChange={e => setExpected(e.target.checked)} />This sample should chain to official C2PA signing anchors with the C2PA signing EKU.</label>
    <button disabled={busy || !state || !selected} onClick={() => run(async () => { const data = new FormData(); data.set("revision", String(state?.revision)); data.set("versionId", selected); data.set("expectPublicTrust", String(expected)); if (sample) data.set("file", sample); const r = await fetch("/api/v1/admin/public-trust/test", { method: "POST", headers: { "X-Admin-Token": token }, body: data }); if (!r.ok) throw new Error(`Public trust sample test failed (${r.status}). Check the expected outcome and signed content.`); })}>Test version</button>
    <label><input type="checkbox" checked={ack} onChange={e => setAck(e.target.checked)} />I acknowledge official-source inspection, manual refresh before cache expiry, and the configured signer-chain revocation policy. Timestamp and ingredient revocation are not checked.</label>
    <button disabled={busy || !state || !selected || !ack} onClick={() => run(async () => { await request("/activate", { revision: state?.revision, versionId: selected, acknowledgeOfficialSource: ack }); })}>Activate tested version</button>
    <p>Stale, unavailable or invalid lists never report public trust. Trust-start times and anchor validity are evaluated against a trusted official timestamp when available, otherwise the current time. Historical trusted periods end at the next recorded service status. Withdrawn periods cannot establish trust at the reference time; malformed or overlapping history fails closed. Refresh lists manually before the configured cache expiry.</p>
    <button disabled={busy || page === 0} onClick={() => setPage(p => p - 1)}>Previous versions</button><button disabled={busy || versions.length < 50} onClick={() => setPage(p => p + 1)}>More versions</button><button disabled={busy} onClick={() => run(load)}>Refresh status</button><p role="status">{message}</p>
  </section>;
}
