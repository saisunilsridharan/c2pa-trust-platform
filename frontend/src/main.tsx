import RevocationPanel from "./RevocationPanel";
import RemoteWorkersPanel from "./RemoteWorkersPanel";
import PublicTrustPanel from "./PublicTrustPanel";
import InspectionTrustSummary from "./InspectionTrustSummary";
import CertificateRenewalPanel from "./CertificateRenewalPanel";
import PrivateCaPanel from "./PrivateCaPanel";
import { authenticationRetry } from "./authenticationRetry";
import AuthenticationLimitsPanel from "./AuthenticationLimitsPanel";
import AuditStoragePanel from "./AuditStoragePanel";
import TimestampsPanel from "./TimestampsPanel";
import TrustPolicyPanel from "./TrustPolicyPanel";
import HardwareIdentitiesPanel from "./HardwareIdentitiesPanel";
import SigningChoicesPanel from "./SigningChoicesPanel";
import { portalFetch as connectionFetch, workspaceFetch } from "./portalFetch";
import React, { useState } from "react";
import { createRoot } from "react-dom/client";
import "./style.css";
import SigningPanel from "./SigningPanel";
import AdministrationPanel from "./AdministrationPanel";
import UsersPanel from "./UsersPanel";
import PasswordPanel from "./PasswordPanel";
import JobsPanel from "./JobsPanel";
import WorkspacesPanel from "./WorkspacesPanel";
import CredentialsPanel from "./CredentialsPanel";
import StorageProvidersPanel from "./StorageProvidersPanel";
import ApiKeysPanel from "./ApiKeysPanel";
import NotificationsPanel from "./NotificationsPanel";
import WebhooksPanel from "./WebhooksPanel";
import ProcessingPanel from "./ProcessingPanel";
import AuditIntegrityPanel from "./AuditIntegrityPanel";
import SecondFactorPanel from "./SecondFactorPanel";
import AccountRecoveryPanel from "./AccountRecoveryPanel";
import OidcSignIn from "./OidcSignIn";
import OidcAdministrationPanel from "./OidcAdministrationPanel";
import { setActiveWorkspace } from "./portalFetch";
type Capability = {
  mime: string;
  label: string;
  extension: string;
  signing: boolean;
  inspection: boolean;
};
type Settings = {
  organizationName: string;
  profileName: string;
  formats: string[];
  maxUploadMb: number;
  requireAiDisclosure: boolean;
};
type State = {
  draft: Settings;
  active: Settings | null;
  revision: number;
  signingAvailable: boolean;
  activeRevision: number | null;
  signingFingerprint: string | null;
};
function App() {
  const [capabilities, setCapabilities] = useState<Capability[]>([]);
  const [user, setUser] = useState<{
    id: number | null;
    username: string;
    role: string;
    passwordChangeRequired: boolean;
    workspaceId: number;
    platformAdministrator: boolean;
  } | null>(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [token, setToken] = useState("");
  const [state, setState] = useState<State | null>(null);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [report, setReport] = useState<unknown>(null);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [switching, setSwitching] = useState(false);
  const [tested, setTested] = useState(false);
  const [saved, setSaved] = useState(true);
  const fetch = workspaceFetch(user?.workspaceId ?? null);
  async function connect(sessionToken: string, workspace?: number) {
    setActiveWorkspace(workspace ?? null);
    const response = await connectionFetch("/api/v1/auth/me", {
      headers: { "X-Admin-Token": sessionToken },
    });
    if (!response.ok) throw new Error("Authentication failed.");
    const account = await response.json();
    setActiveWorkspace(account.workspaceId > 0 ? account.workspaceId : null);
    if (account.passwordChangeRequired || account.workspaceId === 0) {
      setToken(sessionToken);
      setUser(account);
      setState(null);
      setSettings(null);
      setPassword("");
      return;
    }
    const config = await connectionFetch(
      account.role === "ADMIN"
        ? "/api/v1/admin/configuration"
        : "/api/v1/portal/configuration",
      { headers: { "X-Admin-Token": sessionToken } },
    );
    if (!config.ok) throw new Error("Could not load portal settings.");
    const next = await config.json();
    const caps = await connectionFetch("/api/v1/portal/capabilities", {
      headers: { "X-Admin-Token": sessionToken },
    });
    if (!caps.ok) throw new Error("Could not load supported formats.");
    setCapabilities((await caps.json()).formats);
    setToken(sessionToken);
    setUser(account);
    setState(next);
    setSettings(
      next.draft ??
        next.active ?? {
          organizationName: "",
          profileName: "",
          formats: [],
          maxUploadMb: 25,
          requireAiDisclosure: true,
        },
    );
    setPassword("");
  }
  async function switchWorkspace(id: number) {
    const previous = user?.workspaceId ?? null;
    setSwitching(true);
    try {
      await connect(token, id);
    } catch (e) {
      setActiveWorkspace(previous);
      throw e;
    } finally {
      setSwitching(false);
    }
  }
  function disconnect() {
    setActiveWorkspace(null);
    setState(null);
    setUser(null);
    setSettings(null);
    setToken("");
    setPassword("");
    setReport(null);
    setFile(null);
    setTested(false);
    setSaved(true);
  }
  async function request(path: string, method = "GET", body?: unknown) {
    const response = await fetch("/api/v1/admin/configuration" + path, {
      method,
      headers: { "Content-Type": "application/json", "X-Admin-Token": token },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok)
      throw new Error(
        response.status === 401
          ? "Invalid administrator token."
          : response.status === 409
            ? "Settings changed. Reconnect to load the current revision."
            : `Request failed (${response.status}). Check the entered settings.`,
      );
    return response.json();
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
  function change(next: Settings) {
    setSettings(next);
    setTested(false);
    setSaved(false);
  }
  return (
    <div className="shell">
      <aside>
        <div className="brand">◈ TRUST PORTAL</div>
        <p>Content provenance</p>
        <nav>
          <strong>Organization settings</strong>
          <span>Signing profiles</span>
          <span>Development signing identity</span>
          <span>Sign content</span>
        </nav>
        <a href="/swagger-ui/index.html" target="_blank" rel="noreferrer">
          API documentation ↗
        </a>
      </aside>
      <main>
        <header>
          <span>ADMINISTRATION / FIRST-RUN SETUP</span>
          <h1>Your content. Your credentials.</h1>
          <p>
            Define the organization and claims your signing workflow will use.
          </p>
        </header>
        <div className="notice">
          Local/private release · Configure a development identity or import a
          private CA certificate through the UI. Public trust and hardware key
          providers are not connected.
        </div>
        {switching ? (
          <p role="status">Loading workspace…</p>
        ) : user && (user.passwordChangeRequired || user.workspaceId === 0) ? (
          <>
            <div className="notice">
              {user.passwordChangeRequired
                ? "Your administrator reset your password. Choose a new password before using the portal."
                : "No workspace access is assigned. A workspace administrator must add your account."}
            </div>
            <PasswordPanel token={token} onChanged={disconnect} />
            <button
              onClick={() =>
                run(async () => {
                  await fetch("/api/v1/auth/logout", {
                    method: "POST",
                    headers: { "X-Admin-Token": token },
                  });
                  disconnect();
                })
              }
            >
              Sign out
            </button>
          </>
        ) : !state ? (
          <section>
            <h2>Sign in</h2>
            <label>
              Username
              <input
                autoComplete="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              />
            </label>
            <label>
              Password
              <input
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </label>
            <button
              disabled={busy || !username || !password}
              onClick={() =>
                run(async () => {
                  const response = await fetch("/api/v1/auth/login", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ username, password, code }),
                  });
                  if (!response.ok)
                    throw new Error(
                      authenticationRetry(response) ??
                        "Login failed. Check credentials or try again after the lockout period.",
                    );
                  const session = await response.json();
                  await connect(session.token);
                  setCode("");
                })
              }
            >
              Sign in
            </button>
            <label>
              Authenticator or backup code (if enabled)
              <input
                autoComplete="one-time-code"
                maxLength={32}
                value={code}
                onChange={(e) => setCode(e.target.value.trim())}
              />
            </label>
            <AccountRecoveryPanel />
            <OidcSignIn onSignedIn={connect} />
            <h3>Initial setup only</h3>
            <p>
              Use the bootstrap token generated by the local backend. It stays
              in memory for this session.
            </p>
            <label>
              Administrator token
              <input
                type="password"
                autoComplete="off"
                value={token}
                onChange={(e) => setToken(e.target.value)}
              />
            </label>
            <button
              disabled={busy || !token}
              onClick={() =>
                run(async () => {
                  await connect(token);
                })
              }
            >
              Connect
            </button>
          </section>
        ) : (
          settings && (
            <>
              {user && user.id !== null && (
                <WorkspacesPanel
                  token={token}
                  current={user?.workspaceId ?? 1}
                  platformAdmin={user?.platformAdministrator ?? false}
                  onSelect={switchWorkspace}
                  onAccessChanged={() => connect(token, user?.workspaceId)}
                />
              )}
              <p>
                Signed in as {user?.username} · {user?.role}
              </p>
              <NotificationsPanel token={token} />
              {user?.id != null && (
                <SecondFactorPanel token={token} onSignedOut={disconnect} />
              )}
              {user?.id != null && (
                <ApiKeysPanel token={token} canSign={user.role !== "VIEWER"} />
              )}
              {user?.role === "ADMIN" && (
                <>
                  {user?.platformAdministrator && (
                    <UsersPanel
                      token={token}
                      bootstrap={user.id === null}
                      onEnrolled={() => {
                        disconnect();
                        setMessage(
                          "Administrator enrolled. Sign in with your new credentials.",
                        );
                      }}
                    />
                  )}
                  <section>
                    <div className="section-title">
                      <h2>Organization & signing profile</h2>
                      <span className="badge">Revision {state.revision}</span>
                    </div>
                    <label>
                      Organization name
                      <input
                        maxLength={120}
                        value={settings.organizationName}
                        onChange={(e) =>
                          change({
                            ...settings,
                            organizationName: e.target.value,
                          })
                        }
                      />
                    </label>
                    <label>
                      Profile name
                      <input
                        maxLength={120}
                        value={settings.profileName}
                        onChange={(e) =>
                          change({ ...settings, profileName: e.target.value })
                        }
                      />
                    </label>
                    <fieldset>
                      <legend>Supported content formats</legend>
                      {capabilities
                        .filter((c) => c.signing)
                        .map(({ mime: format, label }) => (
                          <label className="check" key={format}>
                            <input
                              type="checkbox"
                              checked={settings.formats.includes(format)}
                              onChange={(e) =>
                                change({
                                  ...settings,
                                  formats: e.target.checked
                                    ? [...settings.formats, format]
                                    : settings.formats.filter(
                                        (f) => f !== format,
                                      ),
                                })
                              }
                            />
                            {label}
                          </label>
                        ))}
                    </fieldset>
                    <label>
                      Maximum upload size (MB)
                      <input
                        type="number"
                        min={1}
                        max={100}
                        value={settings.maxUploadMb}
                        onChange={(e) =>
                          change({
                            ...settings,
                            maxUploadMb: Number(e.target.value),
                          })
                        }
                      />
                    </label>
                    <label className="check">
                      <input
                        type="checkbox"
                        checked={settings.requireAiDisclosure}
                        onChange={(e) =>
                          change({
                            ...settings,
                            requireAiDisclosure: e.target.checked,
                          })
                        }
                      />
                      Require an AI disclosure declaration
                    </label>
                    <div className="actions">
                      <button
                        disabled={busy}
                        onClick={() =>
                          run(async () => {
                            const s = await request("/draft", "PUT", {
                              settings,
                              revision: state.revision,
                            });
                            setState(s);
                            setSaved(true);
                            setTested(false);
                            setMessage("Draft saved.");
                          })
                        }
                      >
                        Save draft
                      </button>
                      <button
                        className="secondary"
                        disabled={busy || !saved}
                        onClick={() =>
                          run(async () => {
                            const result = await request(
                              "/draft/test",
                              "POST",
                              settings,
                            );
                            setTested(result.valid);
                            setMessage(result.message);
                          })
                        }
                      >
                        Validate profile
                      </button>
                      <button
                        className="secondary"
                        disabled={busy || !tested || !saved}
                        onClick={() =>
                          run(async () => {
                            const s = await request("/draft/activate", "POST", {
                              revision: state.revision,
                            });
                            setState(s);
                            setMessage(
                              "Profile activated. Configure a development identity to sign.",
                            );
                          })
                        }
                      >
                        Activate profile
                      </button>
                    </div>
                  </section>
                  <CredentialsPanel
                    token={token}
                    platformAdmin={user?.platformAdministrator ?? false}
                  />
                  <StorageProvidersPanel token={token} />
                  <WebhooksPanel token={token} />
                  <ProcessingPanel token={token} />
                  <RemoteWorkersPanel token={token} />
                  <TimestampsPanel token={token} />
                  <PrivateCaPanel token={token} />
                  <CertificateRenewalPanel token={token} />
                  <AuditStoragePanel token={token} />
                  <TrustPolicyPanel token={token} />
                  <RevocationPanel token={token} />
                  <PublicTrustPanel token={token} />
                  <HardwareIdentitiesPanel
                    token={token}
                    profileRevision={state.activeRevision}
                  />
                  <SigningChoicesPanel
                    token={token}
                    profileRevision={state.activeRevision}
                    fingerprint={state.signingFingerprint}
                  />
                  <AuditIntegrityPanel token={token} />
                  {user?.platformAdministrator && (
                    <>
                      <OidcAdministrationPanel token={token} />
                      <AuthenticationLimitsPanel token={token} />
                    </>
                  )}
                  <AdministrationPanel
                    token={token}
                    revision={state.revision}
                    onChange={async () => {
                      const next = await request("");
                      setState(next);
                      setSettings(next.draft);
                      setTested(false);
                      setSaved(true);
                    }}
                  />
                </>
              )}
              {user?.role !== "VIEWER" && (
                <SigningPanel
                  token={token}
                  capabilities={capabilities}
                  active={state.active}
                  canConfigure={user?.role === "ADMIN"}
                  available={state.signingAvailable}
                  onConfigured={async () => setState(await request(""))}
                />
              )}
              <JobsPanel
                token={token}
                canSign={user?.role !== "VIEWER"}
                admin={user?.role === "ADMIN"}
                activeProfile={state.active}
                capabilities={capabilities}
                profileRevision={state.activeRevision}
                signingFingerprint={state.signingFingerprint}
              />
              <section>
                <h2>Inspect content credentials</h2>
                <p>
                  Upload supported content with an embedded C2PA manifest.
                  Cryptographic integrity and signer trust are separate results;
                  inspect the reported validation statuses.
                </p>
                <label>
                  Content file
                  <input
                    type="file"
                    accept={capabilities
                      .filter((c) => c.inspection)
                      .map((c) => c.mime)
                      .join(",")}
                    onChange={(e) => {
                      setFile(e.target.files?.[0] ?? null);
                      setReport(null);
                    }}
                  />
                </label>
                <button
                  disabled={busy || !file || !state.active}
                  onClick={() =>
                    run(async () => {
                      if (!file) return;
                      const body = new FormData();
                      body.append("file", file);
                      const response = await fetch("/api/v1/verification", {
                        method: "POST",
                        headers: { "X-Admin-Token": token },
                        body,
                      });
                      if (!response.ok)
                        throw new Error(
                          response.status === 422
                            ? "No readable C2PA manifest, or malformed content."
                            : `Inspection failed (${response.status}).`,
                        );
                      setReport(await response.json());
                      setMessage(
                        "Inspection complete. Review validation details below.",
                      );
                    })
                  }
                >
                  Inspect manifest
                </button>
                {report !== null && (
                  <><InspectionTrustSummary report={report} /><pre>{JSON.stringify(report, null, 2)}</pre></>
                )}
              </section>
              {user?.id != null && (
                <PasswordPanel
                  token={token}
                  onChanged={() => {
                    disconnect();
                    setMessage("Password changed. Sign in again.");
                  }}
                />
              )}
              <section>
                <h2>Active configuration</h2>
                {state.active ? (
                  <p>
                    <strong>{state.active.organizationName}</strong> ·{" "}
                    {state.active.profileName}
                    <br />
                    {state.active.formats.join(", ")} · up to{" "}
                    {state.active.maxUploadMb} MB
                  </p>
                ) : (
                  <p>
                    No active profile. Save, validate, and activate your first
                    profile.
                  </p>
                )}
                <button
                  className="secondary"
                  onClick={() =>
                    run(async () => {
                      await fetch("/api/v1/auth/logout", {
                        method: "POST",
                        headers: { "X-Admin-Token": token },
                      });
                      disconnect();
                      setMessage("Signed out.");
                    })
                  }
                >
                  Sign out
                </button>
              </section>
            </>
          )
        )}
        <p role="status" aria-live="polite">
          {busy ? "Working…" : message}
        </p>
      </main>
    </div>
  );
}
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
