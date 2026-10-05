import { workspaceFetch } from "./portalFetch";
import { useEffect, useState } from "react";
type User = {
  id: number | null;
  username: string;
  role: string;
  enabled: boolean;
  passwordChangeRequired: boolean;
};
export default function UsersPanel({
  token,
  bootstrap,
  onEnrolled,
}: {
  token: string;
  bootstrap: boolean;
  onEnrolled: () => void;
}) {
  const fetch = workspaceFetch();
  const [counts, setCounts] = useState<Record<number, number>>({}),
    [resetUser, setResetUser] = useState(""),
    [temporary, setTemporary] = useState(""),
    [resetConfirmed, setResetConfirmed] = useState(false);
  const [users, setUsers] = useState<User[]>([]),
    [username, setUsername] = useState(""),
    [password, setPassword] = useState(""),
    [role, setRole] = useState("VIEWER"),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function request(path: string, method = "GET", body?: unknown) {
    const response = await fetch("/api/v1/" + path, {
      method,
      headers: { "Content-Type": "application/json", "X-Admin-Token": token },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok)
      throw new Error(
        response.status === 409
          ? "Username exists, access changed, or the last administrator cannot be disabled."
          : `Request failed (${response.status}).`,
      );
    return response.json();
  }
  async function load() {
    const [list, sessions] = await Promise.all([
      request("admin/users"),
      request("admin/sessions"),
    ]);
    setUsers(list);
    setCounts(
      Object.fromEntries(
        sessions.map((s: { userId: number; activeSessions: number }) => [
          s.userId,
          s.activeSessions,
        ]),
      ),
    );
  }
  useEffect(() => {
    load().catch((e) => setMessage(e.message));
  }, [token]);
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
  return (
    <section>
      <h2>
        {bootstrap
          ? "Enroll your first administrator"
          : "Users and permissions"}
      </h2>
      <p>
        {bootstrap
          ? "Enrollment permanently disables the shared bootstrap token. After enrollment, sign in with your new account."
          : "Administrators manage settings and users; signers sign and verify; viewers verify only. Disabling a user blocks their existing sessions immediately."}
      </p>
      <label>
        Username
        <input
          autoComplete="username"
          value={username}
          maxLength={40}
          onChange={(e) => setUsername(e.target.value)}
          placeholder="Lowercase, 3–40 characters"
        />
      </label>
      <label>
        Password
        <input
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
      </label>
      <p>
        Use at least 12 characters and at most 72 UTF-8 bytes. Passwords are
        stored as BCrypt hashes.
      </p>
      {!bootstrap && (
        <label>
          Role
          <select value={role} onChange={(e) => setRole(e.target.value)}>
            <option>VIEWER</option>
            <option>SIGNER</option>
            <option>ADMIN</option>
          </select>
        </label>
      )}
      <button
        disabled={busy || !username || password.length < 12}
        onClick={() =>
          run(async () => {
            await request(bootstrap ? "auth/enroll" : "admin/users", "POST", {
              username,
              password,
              role: bootstrap ? "ADMIN" : role,
            });
            setPassword("");
            setUsername("");
            if (bootstrap) {
              onEnrolled();
              return;
            }
            await load();
            setMessage("User created.");
          })
        }
      >
        {bootstrap ? "Enroll administrator" : "Create user"}
      </button>
      {!bootstrap && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Username</th>
                <th>Role</th>
                <th>Access</th>
              </tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <tr key={user.id}>
                  <td>
                    {user.username}
                    {user.passwordChangeRequired && (
                      <p>Password change required</p>
                    )}
                  </td>
                  <td>
                    <select
                      disabled={busy}
                      value={user.role}
                      aria-label={`Role for ${user.username}`}
                      onChange={(e) =>
                        run(async () => {
                          await request(`admin/users/${user.id}`, "PUT", {
                            role: e.target.value,
                            enabled: user.enabled,
                          });
                          await load();
                        })
                      }
                    >
                      <option>ADMIN</option>
                      <option>SIGNER</option>
                      <option>VIEWER</option>
                    </select>
                  </td>
                  <td>
                    <button
                      className="secondary"
                      disabled={busy}
                      onClick={() =>
                        run(async () => {
                          await request(`admin/users/${user.id}`, "PUT", {
                            role: user.role,
                            enabled: !user.enabled,
                          });
                          await load();
                        })
                      }
                    >
                      {user.enabled ? "Disable" : "Enable"}
                    </button>
                    <p>{counts[user.id!] ?? 0} active sessions</p>
                    <button
                      className="secondary"
                      disabled={busy}
                      onClick={() =>
                        run(async () => {
                          await request(
                            `admin/users/${user.id}/revoke-sessions`,
                            "POST",
                            {},
                          );
                          await load();
                          setMessage(
                            "Sessions revoked. Revoking your own sessions requires signing in again.",
                          );
                        })
                      }
                    >
                      Sign out all sessions
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {!bootstrap && (
        <>
          <h3>Recover a local account</h3>
          <p>
            Set a temporary password and deliver it to the user through your
            secure channel. All sessions are revoked and the user must choose a
            new password before accessing content. Use Change your password for
            your own account.
          </p>
          <label>
            User
            <select
              value={resetUser}
              onChange={(e) => {
                setResetUser(e.target.value);
                setResetConfirmed(false);
              }}
            >
              <option value="">Select user</option>
              {users.map((u) => (
                <option key={u.id} value={String(u.id)}>
                  {u.username}
                </option>
              ))}
            </select>
          </label>
          <label>
            Temporary password
            <input
              type="password"
              autoComplete="new-password"
              value={temporary}
              onChange={(e) => {
                setTemporary(e.target.value);
                setResetConfirmed(false);
              }}
            />
          </label>
          <label className="check">
            <input
              type="checkbox"
              checked={resetConfirmed}
              onChange={(e) => setResetConfirmed(e.target.checked)}
            />
            Reset this password and revoke all sessions.
          </label>
          <button
            disabled={
              busy || !resetUser || temporary.length < 12 || !resetConfirmed
            }
            onClick={() =>
              run(async () => {
                try {
                  await request(
                    `admin/users/${resetUser}/password-reset`,
                    "POST",
                    {
                      temporaryPassword: temporary,
                      acknowledgeSessionRevocation: true,
                    },
                  );
                  setResetConfirmed(false);
                  await load();
                  setMessage(
                    "Password reset. The user must replace the temporary password at sign-in.",
                  );
                } finally {
                  setTemporary("");
                }
              })
            }
          >
            Reset account password
          </button>
        </>
      )}
      <p role="status">{busy ? "Working…" : message}</p>
    </section>
  );
}
