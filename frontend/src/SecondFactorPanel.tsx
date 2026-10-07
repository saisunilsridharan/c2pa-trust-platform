import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Status = {
  enabled: boolean;
  pending: boolean;
  recoveryCodesRemaining: number;
  accountRecoveryConfigured: boolean;
};
type Enrollment = { secret: string; uri: string; expiresAt: string };
export default function SecondFactorPanel({
  token,
  onSignedOut,
}: {
  token: string;
  onSignedOut: () => void;
}) {
  const fetch = workspaceFetch();
  const [status, setStatus] = useState<Status | null>(null),
    [enrollment, setEnrollment] = useState<Enrollment | null>(null),
    [password, setPassword] = useState(""),
    [code, setCode] = useState(""),
    [codes, setCodes] = useState<string[]>([]),
    [recoveryKey, setRecoveryKey] = useState(""),
    [needsLogin, setNeedsLogin] = useState(false),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function request(path: string, body?: unknown) {
    const response = await fetch("/api/v1/auth/" + path, {
      method: body === undefined ? "GET" : "POST",
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok)
      throw new Error(
        `Account security request failed (${response.status}). Check password/code, wait for a fresh code or use a backup code.`,
      );
    return response.json();
  }
  useEffect(() => {
    let active = true;
    request("mfa")
      .then((s) => {
        if (active) setStatus(s);
      })
      .catch((e) => {
        if (active) setMessage(e.message);
      });
    return () => {
      active = false;
    };
  }, [token]);
  async function run(action: () => Promise<void>) {
    setBusy(true);
    setMessage("");
    try {
      await action();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : "Request failed");
    } finally {
      setPassword("");
      setCode("");
      setBusy(false);
    }
  }
  return (
    <section>
      <h2>Account security & recovery</h2>
      <p>
        Authenticator MFA: {status?.enabled ? "enabled" : "disabled"} · unused
        backup codes: {status?.recoveryCodesRemaining ?? 0} · account recovery
        key:{" "}
        {status?.accountRecoveryConfigured ? "configured" : "not configured"}.
      </p>
      <details className="help-details">
        <summary>More info</summary>
        <p>
          Keep recovery credentials offline. Enabling or disabling MFA revokes
          existing sessions and API keys. A recovery key replaces a forgotten
          password; when MFA is enabled, an authenticator or unused backup code
          is also required.
        </p>
      </details>
      {!needsLogin && (
        <>
          <label>
            Current password
            <input
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>
          <label>
            Authenticator or backup code
            <input
              autoComplete="one-time-code"
              maxLength={32}
              value={code}
              onChange={(e) => setCode(e.target.value.trim())}
            />
          </label>
          {!status?.enabled && (
            <button
              disabled={busy || !password}
              onClick={() =>
                run(async () => {
                  setEnrollment(await request("mfa/enroll", { password }));
                  setMessage(
                    "Add this secret to an authenticator, then confirm with your password and a fresh code.",
                  );
                })
              }
            >
              Start authenticator enrollment
            </button>
          )}
          {enrollment && (
            <>
              <p>
                Enter this one-time setup secret in your authenticator (SHA-1, 6
                digits, 30 seconds):
              </p>
              <code>{enrollment.secret}</code>
              <p>
                Enrollment expires:{" "}
                {new Date(enrollment.expiresAt).toLocaleString()}.
              </p>
              <button
                disabled={busy || !password || !code}
                onClick={() =>
                  run(async () => {
                    const result = await request("mfa/confirm", {
                      password,
                      code,
                    });
                    setCodes(result.recoveryCodes);
                    setEnrollment(null);
                    setNeedsLogin(true);
                    setMessage(
                      "MFA enabled. Save all backup codes before signing in again. Use a fresh authenticator code.",
                    );
                  })
                }
              >
                Confirm & enable MFA
              </button>
            </>
          )}
          {status?.enabled && (
            <button
              disabled={busy || !password || !code}
              onClick={() =>
                run(async () => {
                  await request("mfa/disable", { password, code });
                  setNeedsLogin(true);
                  setMessage("MFA disabled; sign in again.");
                })
              }
            >
              Verify & disable MFA
            </button>
          )}
          <button
            disabled={
              busy || !password || (status?.enabled && !code) || !!recoveryKey
            }
            onClick={() =>
              run(async () => {
                setRecoveryKey(
                  (await request("recovery-key", { password, code }))
                    .recoveryKey,
                );
                setMessage(
                  "Recovery key created. Save it securely; this replaces any earlier account recovery key.",
                );
              })
            }
          >
            Create one-time account recovery key
          </button>
        </>
      )}
      {codes.length > 0 && (
        <>
          <h3>One-time MFA backup codes</h3>
          <pre>{codes.join("\n")}</pre>
          <p>Each code can be used once in place of an authenticator code.</p>
        </>
      )}
      {recoveryKey && (
        <>
          <h3>Account recovery key (shown once)</h3>
          <code>{recoveryKey}</code>
          <button
            onClick={() => {
              setRecoveryKey("");
              setMessage("Recovery key hidden.");
            }}
          >
            I saved the recovery key — hide it
          </button>
        </>
      )}
      {needsLogin && (
        <button
          disabled={busy}
          onClick={() => {
            setCodes([]);
            setRecoveryKey("");
            onSignedOut();
          }}
        >
          I saved my backup codes — sign in again
        </button>
      )}
      <p role="status">{busy ? "Updating account security…" : message}</p>
    </section>
  );
}
