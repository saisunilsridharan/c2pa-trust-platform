import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Notification = {
  id: string;
  jobId: string;
  outcome: string;
  createdAt: string;
  readAt: string | null;
};
export default function NotificationsPanel({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [items, setItems] = useState<Notification[]>([]),
    [unread, setUnread] = useState(0),
    [page, setPage] = useState(0),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function request(path = "", method = "GET") {
    const response = await fetch("/api/v1/notifications" + path, {
      method,
      headers: { "X-Admin-Token": token },
    });
    if (!response.ok)
      throw new Error(`Notification request failed (${response.status}).`);
    return response.json();
  }
  async function load() {
    const result = await request(`?page=${page}`);
    setItems(result.items);
    setUnread(result.unread);
  }
  useEffect(() => {
    let active = true;
    async function poll() {
      try {
        const result = await request(`?page=${page}`);
        if (active) {
          setItems(result.items);
          setUnread(result.unread);
        }
      } catch (e) {
        if (active)
          setMessage(e instanceof Error ? e.message : "Request failed");
      }
    }
    void poll();
    const timer = setInterval(poll, 10000);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [token, page]);
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
      <h2>Job notifications · {unread} unread</h2>
      <p>
        Signing outcomes remain here after a restart, including failed attempts.
        Use Saved assets & signing jobs to download successful outputs or retry
        failures.
      </p>
      <button disabled={busy} onClick={() => run(load)}>
        Refresh notifications
      </button>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Outcome</th>
              <th>Job</th>
              <th>Date</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            {items.map((n) => (
              <tr key={n.id}>
                <td>
                  {n.outcome}
                  {!n.readAt && " · unread"}
                </td>
                <td>
                  <code>{n.jobId}</code>
                </td>
                <td>{new Date(n.createdAt).toLocaleString()}</td>
                <td>
                  <button
                    disabled={busy || !!n.readAt}
                    onClick={() =>
                      run(async () => {
                        await request("/" + n.id + "/read", "POST");
                        await load();
                      })
                    }
                  >
                    Mark read
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!items.length && <p>No notifications on this page.</p>}
      <div className="actions">
        <button disabled={busy || !page} onClick={() => setPage(page - 1)}>
          Previous notifications
        </button>
        <button
          disabled={busy || items.length < 50}
          onClick={() => setPage(page + 1)}
        >
          Next notifications
        </button>
      </div>
      <p role="status">{busy ? "Updating notifications…" : message}</p>
    </section>
  );
}
