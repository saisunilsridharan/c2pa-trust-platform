import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
import type { SigningChoice } from "./SigningChoicesPanel";
type Worker = { id: string; revision: number; label: string; enabled: boolean; expiresAt: string; lastSeenAt: string | null; testedAt: string | null; probeJobId: string | null; namespaceAvailable: boolean };
type Agent = { hubEndpoint: string; tokenCredential: string; tlsCaPem: string; allowLoopbackHttp: boolean };
type State = { revision: number; remoteQueuedSigning: boolean; agentEnabled: boolean; agentConfiguration: Agent | null; agentTestedAt: string | null; agentStatus: { state: string; updatedAt: string | null } };
const empty: Agent = { hubEndpoint: "", tokenCredential: "", tlsCaPem: "", allowLoopbackHttp: false };
export default function RemoteWorkersPanel({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [state, setState] = useState<State | null>(null), [workers, setWorkers] = useState<Worker[]>([]), [form, setForm] = useState(empty);
  const [choices, setChoices] = useState<SigningChoice[]>([]), [choice, setChoice] = useState(""), [credentials, setCredentials] = useState<{ id: string; label: string }[]>([]);
  const [label, setLabel] = useState(""), [days, setDays] = useState(30), [ack, setAck] = useState(false), [routingAck, setRoutingAck] = useState(false);
  const [pairingToken, setPairingToken] = useState(""), [agentToken, setAgentToken] = useState(""), [page, setPage] = useState(0), [choicePage, setChoicePage] = useState(0);
  const [busy, setBusy] = useState(false), [message, setMessage] = useState("");
  async function request(path: string, method = "GET", body?: unknown) {
    const r = await fetch(path, { method, headers: { "X-Admin-Token": token, "Content-Type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body) });
    if (!r.ok) throw new Error(`Remote worker request failed (${r.status}). Refresh and review pairing, revisions, TLS, runtime and test completion.`);
    return r.json();
  }
  async function values() { return Promise.all([request("/api/v1/admin/remote-workers/settings"), request("/api/v1/admin/remote-workers?page=" + page), request("/api/v1/admin/signing-options?page=" + choicePage), request("/api/v1/admin/credentials")]); }
  async function load() { const [s, w, c, creds] = await values(); setState(s); setWorkers(w); setChoices(c); setCredentials(creds); setForm(s.agentConfiguration || empty); }
  useEffect(() => { let live = true; values().then(([s, w, c, creds]) => { if (live) { setState(s); setWorkers(w); setChoices(c); setCredentials(creds); setForm(s.agentConfiguration || empty); } }).catch(e => { if (live) setMessage(e.message); }); return () => { live = false; }; }, [page, choicePage]);
  async function run(action: () => Promise<void>) { setBusy(true); setMessage(""); try { await action(); await load(); } catch (e) { setMessage(e instanceof Error ? e.message : "Remote worker operation failed"); } finally { setBusy(false); } }
  async function workerAction(w: Worker, action: string, enabled = false) {
    await run(async () => { const r = await request("/api/v1/admin/remote-workers/" + w.id + action, action ? "POST" : "PUT", { revision: w.revision, enabled, acknowledgeTrustedExecutor: ack }); if (r.pairingToken) setPairingToken(r.pairingToken); });
  }
  return <section className="card">
    <h2>Remote signing workers</h2>
    <p>Use trusted private worker hosts for queued signing. Public content, claims and certificates are sent to the worker; HSM keys and provider credentials stay on the hub. The hub verifies signatures, captured claims, signer identity and trust before publishing. Trusted workers can request signing operations during their lease; this is not an untrusted-computation service.</p>
    <label>Worker label<input value={label} maxLength={120} onChange={e => setLabel(e.target.value)} /></label>
    <label>Pairing credential validity (days, 1–90)<input type="number" min={1} max={90} value={days} onChange={e => setDays(Number(e.target.value))} /></label>
    <button disabled={busy || !label.trim()} onClick={() => run(async () => { const r = await request("/api/v1/admin/remote-workers", "POST", { label, validityDays: days }); setPairingToken(r.pairingToken); setLabel(""); })}>Register paused worker</button>
    {pairingToken && <div><p>One-time display. Enter this credential in the worker host's agent connection UI. Keep it private; it is never shown by worker history.</p><textarea readOnly rows={3} value={pairingToken} aria-label="New worker pairing credential" /><button onClick={() => setPairingToken("")}>Clear displayed credential</button></div>}
    <label><input type="checkbox" checked={ack} onChange={e => setAck(e.target.checked)} />I trust this executor and authorize generated public readiness content and leased signing operations.</label>
    <label>Hardware choice for readiness probe<select value={choice} onChange={e => setChoice(e.target.value)}><option value="">Select a hardware signing choice</option>{choices.filter(c => c.enabled && c.provider === "PKCS11" && c.settings.formats.includes("image/png")).map(c => <option key={c.id} value={c.id}>{c.label}</option>)}</select></label>
    <button disabled={busy || choicePage === 0} onClick={() => setChoicePage(p => p - 1)}>Previous choices</button><button disabled={busy || choices.length < 50} onClick={() => setChoicePage(p => p + 1)}>More choices</button>
    {workers.map(w => <article key={w.id}><h3>{w.label}</h3><p>{w.enabled ? "Active" : "Paused"} · Credential expires {w.expiresAt} · Last connection {w.lastSeenAt || "never"} · Namespaces {w.namespaceAvailable ? "available" : "unavailable"}</p><p>Remote signing test: {w.testedAt || "pending"}{w.probeJobId && ` · Job ${w.probeJobId} (see batch jobs)`}</p>
      <button disabled={busy || w.enabled || !choice || !ack} onClick={() => run(async () => { const c = choices.find(c => c.id === choice)!; await request("/api/v1/admin/remote-workers/" + w.id + "/probe", "POST", { revision: w.revision, signingChoiceId: c.id, signingChoiceRevision: c.revision, acknowledgePublicProbe: ack }); setMessage("Probe queued. Refresh after the worker completes signing and central verification."); })}>Run signing probe</button>
      <button disabled={busy || !w.enabled && (!ack || !w.testedAt)} onClick={() => workerAction(w, "", !w.enabled)}>{w.enabled ? "Withdraw worker" : "Activate tested worker"}</button>
      <button disabled={busy} onClick={() => workerAction(w, "/rotate")}>Rotate credential and pause</button><button disabled={busy} onClick={() => workerAction(w, "/revoke")}>Revoke credential</button>
    </article>)}
    <button disabled={busy || page === 0} onClick={() => setPage(p => p - 1)}>Previous workers</button><button disabled={busy || workers.length < 50} onClick={() => setPage(p => p + 1)}>More workers</button>
    <p>Queued signing: {state?.remoteQueuedSigning ? "remote workers" : "local worker"}. Existing jobs retain their routing. Immediate signing and inspection use the local worker. Remote queues require hardware choices and wait if no approved worker is available.</p>
    <label><input type="checkbox" checked={routingAck} onChange={e => setRoutingAck(e.target.checked)} />I acknowledge hardware-only remote queues with no automatic local fallback.</label>
    <button disabled={busy || !state || !state.remoteQueuedSigning && !routingAck} onClick={() => run(async () => { await request("/api/v1/admin/remote-workers/routing", "PUT", { revision: state?.revision, enabled: !state?.remoteQueuedSigning, acknowledgeHardwareOnly: routingAck }); })}>{state?.remoteQueuedSigning ? "Route new jobs locally" : "Route new jobs remotely"}</button>
    <h3>This host's worker-agent connection</h3><p>Install the same application on a separate host with its own database and protected local state. Configure the connection here. No hub URL or worker credential is supplied through environment variables. Each connection processes one job at a time; the host allows two concurrent connections.</p>
    <label>Hub base URL<input type="url" value={form.hubEndpoint} onChange={e => setForm({ ...form, hubEndpoint: e.target.value })} /></label>
    <label>New pairing credential (write-only, optional)<input type="password" autoComplete="new-password" value={agentToken} onChange={e => setAgentToken(e.target.value)} /></label>
    <label>Existing encrypted pairing credential<select value={form.tokenCredential} onChange={e => setForm({ ...form, tokenCredential: e.target.value })}><option value="">Choose credential or enter a new one above</option>{credentials.map(c => <option key={c.id} value={c.id}>{c.label}</option>)}</select></label>
    <label>Private hub TLS CA certificates (optional)<textarea rows={4} value={form.tlsCaPem} onChange={e => setForm({ ...form, tlsCaPem: e.target.value })} /></label>
    <label><input type="checkbox" checked={form.allowLoopbackHttp} onChange={e => setForm({ ...form, allowLoopbackHttp: e.target.checked })} />Allow loopback HTTP for development</label>
    <button disabled={busy || !state} onClick={() => run(async () => { let credential = form.tokenCredential; if (agentToken) { const saved = await request("/api/v1/admin/credentials", "POST", { label: "Remote worker pairing", value: agentToken }); credential = saved.id; setAgentToken(""); setForm({ ...form, tokenCredential: credential }); } await request("/api/v1/admin/remote-workers/agent", "PUT", { revision: state?.revision, configuration: { ...form, tokenCredential: credential } }); })}>Save connection and pause agent</button>
    <button disabled={busy || !state?.agentConfiguration} onClick={() => run(async () => { await request("/api/v1/admin/remote-workers/agent/test", "POST", { revision: state?.revision }); })}>Test connection and runtime</button>
    <button disabled={busy || !state || !state.agentEnabled && (!state.agentTestedAt || !ack)} onClick={() => run(async () => { await request("/api/v1/admin/remote-workers/agent/activate", "POST", { revision: state?.revision, enabled: !state?.agentEnabled, acknowledgeTrustedExecutor: ack }); })}>{state?.agentEnabled ? "Pause agent" : "Start tested agent"}</button>
    <p>Agent: {state?.agentEnabled ? "enabled" : "paused"} · {state?.agentStatus.state || "IDLE"} · Connection tested {state?.agentTestedAt || "never"}</p>
    <button disabled={busy} onClick={() => run(load)}>Refresh worker and agent status</button><p role="status">{message}</p>
  </section>;
}
