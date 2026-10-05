import { authenticationRetry } from "./authenticationRetry";
import { useEffect, useState } from "react";
import { portalFetch } from "./portalFetch";
export default function OidcSignIn({
  onSignedIn,
}: {
  onSignedIn: (token: string) => Promise<void>;
}) {
  const [enabled, setEnabled] = useState(false),
    [code, setCode] = useState(""),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  const [callback] = useState(() => {
    const p = new URLSearchParams(window.location.search);
    const result = {
      state: p.get("state"),
      code: p.get("code"),
      error: p.get("error"),
    };
    if (window.location.pathname === "/oidc/callback")
      window.history.replaceState(null, "", "/oidc/callback");
    return result;
  });
  useEffect(() => {
    let active = true;
    portalFetch("/api/v1/auth/oidc/status")
      .then((r) => (r.ok ? r.json() : null))
      .then((s) => {
        if (active) setEnabled(s?.enabled ?? false);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);
  async function start() {
    setBusy(true);
    setMessage("");
    try {
      const response = await portalFetch("/api/v1/auth/oidc/start", {
        method: "POST",
      });
      if (!response.ok)
        throw new Error(
          authenticationRetry(response) ??
            "Private login is unavailable. Check the provider configuration or use password login.",
        );
      const result = await response.json();
      window.location.assign(result.authorizationUrl);
    } catch (e) {
      setMessage(e instanceof Error ? e.message : "Private login failed");
      setBusy(false);
    }
  }
  async function complete() {
    setBusy(true);
    setMessage("");
    try {
      const response = await portalFetch("/api/v1/auth/oidc/complete", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          state: callback.state,
          code: callback.code,
          secondFactor: code,
        }),
      });
      if (!response.ok)
        throw new Error(
          authenticationRetry(response) ??
            "Private login failed. Check account linking and your second factor, then start a fresh provider login.",
        );
      const session = await response.json();
      await onSignedIn(session.token);
    } catch (e) {
      setMessage(e instanceof Error ? e.message : "Private login failed");
    } finally {
      setCode("");
      setBusy(false);
    }
  }
  if (!enabled && !callback.code && !callback.error) return null;
  return (
    <div>
      <h3>Private organization login</h3>
      {callback.error && (
        <p>
          The provider did not complete login. Start again or use password
          login.
        </p>
      )}
      {callback.code && callback.state && (
        <>
          <p>
            Your provider returned a login response. Enter your portal
            authenticator or backup code if MFA is enabled.
          </p>
          <label>
            Portal second-factor code
            <input
              autoComplete="one-time-code"
              maxLength={32}
              value={code}
              onChange={(e) => setCode(e.target.value.trim())}
            />
          </label>
          <button disabled={busy} onClick={complete}>
            Finish private login
          </button>
        </>
      )}
      <button disabled={busy || !enabled} onClick={start}>
        Sign in with private provider
      </button>
      <p role="status">{busy ? "Connecting to private login…" : message}</p>
    </div>
  );
}
