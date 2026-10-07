import FileDropZone from "./FileDropZone";
import CertificateWorkspace from "./CertificateWorkspace";
import { PortalShell, Page } from "./PortalShell";
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
    setReport(null);
    setFile(null);
    setTested(false);
    setSaved(true);
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
    <PortalShell
      user={user}
      ready={!!state}
      active={state?.active ?? null}
      available={state?.signingAvailable ?? false}
      onSignOut={() =>
        run(async () => {
          await fetch("/api/v1/auth/logout", {
            method: "POST",
            headers: { "X-Admin-Token": token },
          });
          disconnect();
        })
      }
    >
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
            Security code (if enabled)
            <input
              autoComplete="one-time-code"
              maxLength={32}
              value={code}
              onChange={(e) => setCode(e.target.value.trim())}
            />
          </label>
          <AccountRecoveryPanel />
          <OidcSignIn onSignedIn={connect} />
          <details className="help-details">
            <summary>First-time setup</summary>
            <p>Enter the setup token from your server.</p>
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
          </details>
        </section>
      ) : (
        settings && (
          <React.Fragment key={user?.workspaceId}>
            {user && user.id !== null && (
              <Page id="workspaces">
                <WorkspacesPanel
                  token={token}
                  current={user?.workspaceId ?? 1}
                  platformAdmin={user?.platformAdministrator ?? false}
                  onSelect={switchWorkspace}
                  onAccessChanged={() => connect(token, user?.workspaceId)}
                />
              </Page>
            )}

            <Page id="notifications">
              <NotificationsPanel token={token} />
            </Page>
            {user?.id != null && (
              <Page id="security">
                <SecondFactorPanel token={token} onSignedOut={disconnect} />
              </Page>
            )}
            {user?.id != null && (
              <Page id="api-keys">
                <ApiKeysPanel token={token} canSign={user.role !== "VIEWER"} />
              </Page>
            )}
            {user?.role === "ADMIN" && (
              <>
                {user?.platformAdministrator && (
                  <Page id="users">
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
                  </Page>
                )}
                <Page id="profile">
                  <section>
                    <div className="section-title">
                      <h2>Signer profile</h2>
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
                        Save
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
                        Check
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
                              "Profile ready. Choose a certificate to sign.",
                            );
                          })
                        }
                      >
                        Use profile
                      </button>
                    </div>
                  </section>
                  <details className="help-details">
                    <summary>Signing certificates</summary>
                    <SigningChoicesPanel
                      token={token}
                      profileRevision={state.activeRevision}
                      fingerprint={state.signingFingerprint}
                    />
                  </details>
                </Page>
                <Page id="credentials">
                  <CredentialsPanel
                    token={token}
                    platformAdmin={user?.platformAdministrator ?? false}
                  />
                </Page>
                <Page id="storage">
                  <StorageProvidersPanel token={token} />
                </Page>
                <Page id="webhooks">
                  <WebhooksPanel token={token} />
                </Page>
                <Page id="processing">
                  <ProcessingPanel token={token} />
                </Page>
                <Page id="workers">
                  <RemoteWorkersPanel token={token} />
                </Page>
                <Page id="timestamps">
                  <TimestampsPanel token={token} />
                </Page>
                <Page id="private-ca">
                  <PrivateCaPanel token={token} />
                </Page>
                <Page id="renewal">
                  <CertificateRenewalPanel token={token} />
                </Page>
                <Page id="audit-storage">
                  <AuditStoragePanel token={token} />
                </Page>
                <Page id="private-trust">
                  <TrustPolicyPanel token={token} />
                </Page>
                <Page id="revocation">
                  <RevocationPanel token={token} />
                </Page>
                <Page id="public-trust">
                  <PublicTrustPanel token={token} />
                </Page>
                <Page id="hardware">
                  <HardwareIdentitiesPanel
                    token={token}
                    profileRevision={state.activeRevision}
                  />
                </Page>
                <Page id="choices">
                  <SigningChoicesPanel
                    token={token}
                    profileRevision={state.activeRevision}
                    fingerprint={state.signingFingerprint}
                  />
                </Page>
                <Page id="audit">
                  <AuditIntegrityPanel token={token} />
                </Page>
                {user?.platformAdministrator && (
                  <>
                    <Page id="oidc">
                      <OidcAdministrationPanel token={token} />
                    </Page>
                    <Page id="login-policy">
                      <AuthenticationLimitsPanel token={token} />
                    </Page>
                  </>
                )}
                <Page id="certificates">
                  <CertificateWorkspace
                    software={
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
                    }
                    hardware={
                      <HardwareIdentitiesPanel
                        token={token}
                        profileRevision={state.activeRevision}
                      />
                    }
                  />
                </Page>
                <Page id="identities">
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
                </Page>
              </>
            )}
            {user?.role !== "VIEWER" && (
              <Page id="sign">
                <SigningPanel
                  token={token}
                  capabilities={capabilities}
                  active={state.active}
                  canConfigure={user?.role === "ADMIN"}
                  available={state.signingAvailable}
                  onConfigured={async () => setState(await request(""))}
                />
              </Page>
            )}
            <Page id="jobs">
              <JobsPanel
                mode="jobs"
                token={token}
                canSign={user?.role !== "VIEWER"}
                admin={user?.role === "ADMIN"}
                activeProfile={state.active}
                capabilities={capabilities}
                profileRevision={state.activeRevision}
                signingFingerprint={state.signingFingerprint}
              />
            </Page>
            <Page id="batch">
              <JobsPanel
                mode="batch"
                token={token}
                canSign={user?.role !== "VIEWER"}
                admin={user?.role === "ADMIN"}
                activeProfile={state.active}
                capabilities={capabilities}
                profileRevision={state.activeRevision}
                signingFingerprint={state.signingFingerprint}
              />
            </Page>
            <Page id="inspect">
              <section className="validate-workspace">
                <div className="console-two-column">
                  <div className="console-form">
                    <h2>Check your file</h2>
                    <p>Check a file’s signature and signer trust.</p>
                    <FileDropZone
                      files={file ? [file] : []}
                      accept={capabilities
                        .filter((c) => c.inspection)
                        .map((c) => c.mime)
                        .join(",")}
                      disabled={busy}
                      onChange={(files) => {
                        setFile(files[0] ?? null);
                        setReport(null);
                      }}
                    />
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
                          setMessage("Check complete. See the results below.");
                        })
                      }
                    >
                      Validate
                    </button>
                  </div>
                  <div
                    className="console-result"
                    aria-label="Validation result"
                  >
                    {report === null && (
                      <p className="empty-result">
                        The file’s validation will appear here.
                      </p>
                    )}
                    {report !== null && (
                      <>
                        <InspectionTrustSummary report={report} />
                        <details className="help-details">
                          <summary>Technical report</summary>
                          <pre>{JSON.stringify(report, null, 2)}</pre>
                        </details>
                      </>
                    )}
                  </div>
                </div>
              </section>
            </Page>
            {user?.id != null && (
              <Page id="password">
                <PasswordPanel
                  token={token}
                  onChanged={() => {
                    disconnect();
                    setMessage("Password changed. Sign in again.");
                  }}
                />
              </Page>
            )}
          </React.Fragment>
        )
      )}
      <p role="status" aria-live="polite">
        {busy ? "Working…" : message}
      </p>
    </PortalShell>
  );
}
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
