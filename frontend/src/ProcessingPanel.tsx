import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Settings = {
  revision: number;
  workerTimeoutSeconds: number;
  maxAttempts: number;
};
export default function ProcessingPanel({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [settings, setSettings] = useState<Settings | null>(null),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  async function request(method = "GET", body?: Settings) {
    const response = await fetch("/api/v1/admin/processing", {
      method,
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok)
      throw new Error(
        response.status === 409
          ? "Processing settings changed; refresh and retry."
          : `Processing request failed (${response.status}).`,
      );
    return response.json();
  }
  useEffect(() => {
    let active = true;
    request()
      .then((s) => {
        if (active) setSettings(s);
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
      setBusy(false);
    }
  }
  return (
    <section>
      <h2>Processing limits</h2>
      <p>
        Future jobs retain these limits at submission. Database leases prevent
        two application instances from claiming the same job. Multiple instances
        require shared asset and identity directories and the same encryption
        key. Each attempt writes separate output files.
      </p>
      <button
        disabled={busy}
        onClick={() => run(async () => setSettings(await request()))}
      >
        Refresh processing limits
      </button>
      {settings && (
        <>
          <label>
            Worker timeout in seconds (10–120)
            <input
              type="number"
              min={10}
              max={120}
              value={settings.workerTimeoutSeconds}
              onChange={(e) =>
                setSettings({
                  ...settings,
                  workerTimeoutSeconds: Number(e.target.value),
                })
              }
            />
          </label>
          <label>
            Maximum attempts, including retries (1–100)
            <input
              type="number"
              min={1}
              max={100}
              value={settings.maxAttempts}
              onChange={(e) =>
                setSettings({
                  ...settings,
                  maxAttempts: Number(e.target.value),
                })
              }
            />
          </label>
          <button
            disabled={
              busy ||
              settings.workerTimeoutSeconds < 10 ||
              settings.workerTimeoutSeconds > 120 ||
              settings.maxAttempts < 1 ||
              settings.maxAttempts > 100
            }
            onClick={() =>
              run(async () => {
                setSettings(await request("PUT", settings));
                setMessage("Processing limits saved for future jobs.");
              })
            }
          >
            Save future job limits
          </button>
        </>
      )}
      <p role="status">{busy ? "Updating processing limits…" : message}</p>
    </section>
  );
}
