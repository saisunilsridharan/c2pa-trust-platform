import { useState } from "react";
import { portalFetch } from "./portalFetch";
export default function AccountRecoveryPanel() {
  const [username, setUsername] = useState(""),
    [key, setKey] = useState(""),
    [password, setPassword] = useState(""),
    [code, setCode] = useState(""),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function recover() {
    setBusy(true);
    setMessage("");
    try {
      const response = await portalFetch("/api/v1/auth/recovery", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          username,
          recoveryKey: key,
          newPassword: password,
          code,
        }),
      });
      if (!response.ok)
        throw new Error(
          "Recovery failed. Check the key and second factor, or contact your administrator.",
        );
      setMessage(
        "Password replaced and existing sessions/API keys revoked. Sign in with the new password and a fresh second-factor code.",
      );
    } catch (e) {
      setMessage(e instanceof Error ? e.message : "Recovery failed");
    } finally {
      setKey("");
      setPassword("");
      setCode("");
      setBusy(false);
    }
  }
  return (
    <details>
      <summary>Recover a forgotten password</summary>
      <p>
        Use your previously saved account recovery key. MFA-enabled accounts
        also require an authenticator or unused MFA backup code.
      </p>
      <label>
        Username
        <input
          autoComplete="username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />
      </label>
      <label>
        Account recovery key
        <input
          type="password"
          autoComplete="off"
          maxLength={32}
          value={key}
          onChange={(e) => setKey(e.target.value.trim())}
        />
      </label>
      <label>
        New password
        <input
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
      </label>
      <label>
        Authenticator or backup code (if MFA enabled)
        <input
          autoComplete="one-time-code"
          maxLength={32}
          value={code}
          onChange={(e) => setCode(e.target.value.trim())}
        />
      </label>
      <button
        disabled={
          busy || !username || key.length !== 32 || password.length < 12
        }
        onClick={recover}
      >
        Recover account & revoke old access
      </button>
      <p role="status">{busy ? "Recovering account…" : message}</p>
    </details>
  );
}
