import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Workspace = { id: number; name: string; role: string; revision: number };
type Member = {
  userId: number;
  username: string;
  role: string;
  enabled: boolean;
  revision: number;
};
export default function WorkspacesPanel({
  token,
  current,
  platformAdmin,
  onSelect,
  onAccessChanged,
}: {
  token: string;
  current: number;
  platformAdmin: boolean;
  onSelect: (id: number) => Promise<void>;
  onAccessChanged: () => Promise<void>;
}) {
  const fetch = workspaceFetch();
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]),
    [members, setMembers] = useState<Member[]>([]),
    [name, setName] = useState(""),
    [username, setUsername] = useState(""),
    [role, setRole] = useState("VIEWER"),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  const [renameName, setRenameName] = useState("");
  const canManage = workspaces.find((w) => w.id === current)?.role === "ADMIN";
  async function request(path: string, method = "GET", body?: unknown) {
    const response = await fetch("/api/v1/workspaces" + path, {
      method,
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok)
      throw new Error(
        `Workspace request failed (${response.status}). Check permissions, account name or changed membership.`,
      );
    return response.json();
  }
  async function load() {
    const list: Workspace[] = await request("");
    setWorkspaces(list);
    setRenameName(list.find((w) => w.id === current)?.name ?? "");
    if (list.find((w) => w.id === current)?.role === "ADMIN")
      setMembers(await request(`/${current}/members`));
    else setMembers([]);
  }
  useEffect(() => {
    let active = true;
    request("")
      .then(async (list: Workspace[]) => {
        if (!active) return;
        setWorkspaces(list);
        setRenameName(list.find((w) => w.id === current)?.name ?? "");
        if (list.find((w) => w.id === current)?.role === "ADMIN") {
          const m = await request(`/${current}/members`);
          if (active) setMembers(m);
        } else setMembers([]);
      })
      .catch((e) => {
        if (active) setMessage(e.message);
      });
    return () => {
      active = false;
    };
  }, [token, current]);
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
      <h2>Workspaces</h2>
      <label>
        Active workspace
        <select
          value={current}
          disabled={busy}
          onChange={(e) => run(() => onSelect(Number(e.target.value)))}
        >
          {workspaces.map((w) => (
            <option key={w.id} value={w.id}>
              {w.name} · {w.role}
            </option>
          ))}
        </select>
      </label>
      <p>
        Profiles, identities, jobs, retention and audits belong to the selected
        workspace. Platform administrators retain access to every workspace.
      </p>
      {platformAdmin && (
        <>
          <label>
            New workspace name
            <input
              maxLength={120}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </label>
          <button
            disabled={busy || !name.trim()}
            onClick={() =>
              run(async () => {
                const workspace = await request("", "POST", { name });
                setName("");
                await onSelect(workspace.id);
              })
            }
          >
            Create workspace
          </button>
        </>
      )}
      {canManage && (
        <>
          <label>
            Workspace name
            <input
              maxLength={120}
              value={renameName}
              onChange={(e) => setRenameName(e.target.value)}
            />
          </label>
          <button
            disabled={busy || !renameName.trim()}
            onClick={() =>
              run(async () => {
                await request(`/${current}`, "PUT", {
                  name: renameName,
                  revision: workspaces.find((w) => w.id === current)?.revision,
                });
                await load();
              })
            }
          >
            Rename workspace
          </button>
          <h3>Workspace members</h3>
          <p>
            Add existing accounts by username. Platform administrators create
            accounts in Users and permissions.
          </p>
          <label>
            Account username
            <input
              maxLength={40}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </label>
          <label>
            Workspace role
            <select value={role} onChange={(e) => setRole(e.target.value)}>
              <option>VIEWER</option>
              <option>SIGNER</option>
              <option>ADMIN</option>
            </select>
          </label>
          <button
            disabled={busy || !username.trim()}
            onClick={() =>
              run(async () => {
                const old = members.find((m) => m.username === username);
                await request(`/${current}/members`, "PUT", {
                  username,
                  role,
                  revision: old?.revision ?? null,
                });
                setUsername("");
                await onAccessChanged();
                await load();
              })
            }
          >
            Save membership
          </button>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Account</th>
                  <th>Role</th>
                  <th>Access</th>
                </tr>
              </thead>
              <tbody>
                {members.map((m) => (
                  <tr key={m.userId}>
                    <td>{m.username}</td>
                    <td>
                      <select
                        value={m.role}
                        disabled={busy}
                        aria-label={`Workspace role for ${m.username}`}
                        onChange={(e) =>
                          run(async () => {
                            await request(`/${current}/members`, "PUT", {
                              username: m.username,
                              role: e.target.value,
                              revision: m.revision,
                            });
                            await onAccessChanged();
                            await load();
                          })
                        }
                      >
                        <option>VIEWER</option>
                        <option>SIGNER</option>
                        <option>ADMIN</option>
                      </select>
                    </td>
                    <td>
                      {m.enabled ? "Account enabled" : "Account disabled"}
                      <button
                        className="secondary"
                        disabled={busy}
                        onClick={() =>
                          run(async () => {
                            await request(
                              `/${current}/members/${m.userId}?revision=${m.revision}`,
                              "DELETE",
                            );
                            await onAccessChanged();
                            await load();
                          })
                        }
                      >
                        Remove workspace access
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
      <p role="status">{busy ? "Updating workspace…" : message}</p>
    </section>
  );
}
