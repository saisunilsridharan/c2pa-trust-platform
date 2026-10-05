import { useState } from "react";
export default function PasswordPanel({
  token,
  onChanged,
}: {
  token: string;
  onChanged: () => void;
}) {
  const [current, setCurrent] = useState(""),
    [password, setPassword] = useState(""),
    [confirmation, setConfirmation] = useState(""),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  return (
    <section>
      <h2>Change your password</h2>
      <p>
        Changing your password signs out all your sessions. Use at least 12
        characters and at most 72 UTF-8 bytes.
      </p>
      <label>
        Current password
        <input
          type="password"
          autoComplete="current-password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
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
        Confirm new password
        <input
          type="password"
          autoComplete="new-password"
          value={confirmation}
          onChange={(e) => setConfirmation(e.target.value)}
        />
      </label>
      <button
        disabled={
          busy || !current || password.length < 12 || password !== confirmation
        }
        onClick={async () => {
          setBusy(true);
          setMessage("");
          try {
            const response = await fetch("/api/v1/auth/password", {
              method: "POST",
              headers: {
                "Content-Type": "application/json",
                "X-Admin-Token": token,
              },
              body: JSON.stringify({
                currentPassword: current,
                newPassword: password,
              }),
            });
            if (!response.ok)
              throw new Error(
                "Password change failed. Check your current password and the new password requirements.",
              );
            setCurrent("");
            setPassword("");
            setConfirmation("");
            onChanged();
          } catch (e) {
            setMessage(e instanceof Error ? e.message : "Request failed");
          } finally {
            setBusy(false);
          }
        }}
      >
        Change password and sign out
      </button>
      <p role="status">{message}</p>
    </section>
  );
}
