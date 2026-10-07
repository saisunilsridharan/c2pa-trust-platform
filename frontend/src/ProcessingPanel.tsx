import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Settings = {
  revision: number;
  workerTimeoutSeconds: number;
  maxAttempts: number;
  maxMemoryMb: number;
  maxCpuSeconds: number;
  sandboxMode: "LIMITED" | "NAMESPACE";
};
export default function ProcessingPanel({ token }: { token: string }) {
  const fetch = workspaceFetch();
  const [settings, setSettings] = useState<Settings | null>(null),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  const [capabilities, setCapabilities] = useState<{
    resourceLimitsAvailable: boolean;
    namespaceIsolationAvailable: boolean;
    message: string;
  } | null>(null);
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
      <details className="help-details">
        <summary>More info</summary>
        <p>
          Future jobs retain these limits at submission. Database leases prevent
          two application instances from claiming the same job. Multiple
          instances require shared asset and identity directories and the same
          encryption key. Each attempt writes separate output files.
        </p>
      </details>
      <button
        disabled={busy}
        onClick={() => run(async () => setSettings(await request()))}
      >
        Refresh processing limits
      </button>
      <button
        disabled={busy}
        onClick={() =>
          run(async () => {
            const response = await fetch(
              "/api/v1/admin/processing/test-sandbox",
              { method: "POST", headers: { "X-Admin-Token": token } },
            );
            if (!response.ok)
              throw new Error(`Sandbox probe failed (${response.status}).`);
            setCapabilities(await response.json());
          })
        }
      >
        Test worker host sandbox
      </button>
      {capabilities && (
        <p>
          {capabilities.message} Resource limits:{" "}
          {capabilities.resourceLimitsAvailable ? "available" : "unavailable"}.
          Namespace isolation:{" "}
          {capabilities.namespaceIsolationAvailable
            ? "available"
            : "unavailable"}
          .
        </p>
      )}
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
          <label>
            Worker memory limit in MiB (256–4096)
            <input
              type="number"
              min={256}
              max={4096}
              value={settings.maxMemoryMb}
              onChange={(e) =>
                setSettings({
                  ...settings,
                  maxMemoryMb: Number(e.target.value),
                })
              }
            />
          </label>
          <label>
            Worker CPU time limit in seconds (10–120)
            <input
              type="number"
              min={10}
              max={120}
              value={settings.maxCpuSeconds}
              onChange={(e) =>
                setSettings({
                  ...settings,
                  maxCpuSeconds: Number(e.target.value),
                })
              }
            />
          </label>
          <label>
            Worker execution mode
            <select
              value={settings.sandboxMode}
              onChange={(e) =>
                setSettings({
                  ...settings,
                  sandboxMode: e.target.value as Settings["sandboxMode"],
                })
              }
            >
              <option value="LIMITED">Limited: CPU/memory/file limits</option>
              <option value="NAMESPACE">
                Namespace: file/network isolation and resource limits
              </option>
            </select>
          </label>
          <details className="help-details">
            <summary>More info</summary>
            <p>
              Limited mode does not isolate the filesystem or operating-system
              network. Namespace mode requires a successful host capability test
              and fails closed if unavailable. Vendor PKCS#11 and TSA access
              runs in the backend through the job's private socket. These
              controls cover local workers; a distributed pool requires separate
              deployment acceptance.
            </p>
          </details>
          <button
            disabled={
              busy ||
              settings.workerTimeoutSeconds < 10 ||
              settings.workerTimeoutSeconds > 120 ||
              settings.maxAttempts < 1 ||
              settings.maxAttempts > 100 ||
              settings.maxMemoryMb < 256 ||
              settings.maxMemoryMb > 4096 ||
              settings.maxCpuSeconds < 10 ||
              settings.maxCpuSeconds > 120
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
