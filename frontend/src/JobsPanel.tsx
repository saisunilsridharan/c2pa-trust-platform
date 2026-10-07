import FileDropZone from "./FileDropZone";
import { RevocationStatus } from "./RevocationPanel";
import { TimestampStatus } from "./TimestampsPanel";
import { TrustPolicyStatus } from "./TrustPolicyPanel";
import {
  SigningChoiceSelector,
  type SigningChoice,
} from "./SigningChoicesPanel";
import { workspaceFetch } from "./portalFetch";
import { useEffect, useState } from "react";
type Job = {
  id: string;
  state: string;
  title: string;
  format: string;
  owner: string;
  attempts: number;
  error: string | null;
  createdAt: string;
  sandboxMode: string;
  maxMemoryMb: number;
  maxCpuSeconds: number;
};
export default function JobsPanel({
  mode = "all",
  token,
  canSign,
  admin,
  capabilities,
  activeProfile: defaultProfile,
  profileRevision: defaultRevision,
  signingFingerprint: defaultFingerprint,
}: {
  mode?: "all" | "jobs" | "batch";
  token: string;
  capabilities: { mime: string; extension: string }[];
  canSign: boolean;
  admin: boolean;
  activeProfile: {
    organizationName: string;
    profileName: string;
    formats: string[];
  } | null;
  profileRevision: number | null;
  signingFingerprint: string | null;
}) {
  const fetch = workspaceFetch();
  const [choice, setChoice] = useState<SigningChoice | null>(null);
  const activeProfile = choice?.settings ?? defaultProfile,
    profileRevision = choice?.profileRevision ?? defaultRevision,
    signingFingerprint = choice?.fingerprint ?? defaultFingerprint;
  const [operations, setOperations] = useState<Record<string, unknown> | null>(
    null,
  );
  const [jobs, setJobs] = useState<Job[]>([]),
    [page, setPage] = useState(0),
    [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false);
  const [files, setFiles] = useState<File[]>([]),
    [creator, setCreator] = useState(""),
    [title, setTitle] = useState(""),
    [ai, setAi] = useState("none"),
    [reviewed, setReviewed] = useState(false);
  const [retention, setRetention] = useState<{
      revision: number;
      retentionDays: number;
      provider: string;
    } | null>(null),
    [ack, setAck] = useState(false);
  async function request(path: string, method = "GET", body?: unknown) {
    const response = await fetch("/api/v1/" + path, {
      method,
      headers: {
        "X-Admin-Token": token,
        ...(body instanceof FormData
          ? {}
          : { "Content-Type": "application/json" }),
      },
      body:
        body === undefined
          ? undefined
          : body instanceof FormData
            ? body
            : JSON.stringify(body),
    });
    if (!response.ok) throw new Error(`Request failed (${response.status}).`);
    return response.json();
  }
  async function load() {
    setJobs(await request(`jobs?page=${page}`));
    if (admin) setOperations(await request("admin/operations"));
  }
  useEffect(
    () => setReviewed(false),
    [activeProfile, profileRevision, signingFingerprint, choice],
  );
  useEffect(() => {
    let active = true;
    const refresh = () =>
      request(`jobs?page=${page}`)
        .then((j) => {
          if (active) setJobs(j);
        })
        .catch((e) => {
          if (active) setMessage(e.message);
        });
    refresh();
    const timer = setInterval(refresh, 3000);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [token, page]);
  useEffect(() => {
    if (admin)
      request("admin/operations")
        .then(setOperations)
        .catch((e) => setMessage(e.message));
    if (admin)
      request("admin/storage")
        .then(setRetention)
        .catch((e) => setMessage(e.message));
  }, [admin, token]);
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
      <h2>Files & jobs</h2>
      {mode !== "jobs" && (
        <>
          <details className="help-details">
            <summary>Signing checks</summary>
            <TrustPolicyStatus token={token} />
            <RevocationStatus token={token} />
            <TimestampStatus token={token} />
          </details>
        </>
      )}
      {admin && operations && mode !== "batch" && (
        <details className="help-details">
          <summary>Runtime details</summary>
          <p>
            Queue: {String(operations.queued)} waiting ·{" "}
            {String(operations.running)} running ·{" "}
            {String(operations.completed)} completed ·{" "}
            {String(operations.failed)} failed. Database:{" "}
            {String(operations.database)}. Worker installed:{" "}
            {String(operations.workerBinaryInstalled)}.
          </p>
        </details>
      )}
      <p>Your originals are kept.</p>
      {canSign && mode !== "jobs" && (
        <>
          <SigningChoiceSelector
            token={token}
            value={choice}
            onChange={setChoice}
          />
          <FileDropZone
            multiple
            files={files}
            label="Batch files"
            accept={activeProfile?.formats.join(",")}
            disabled={busy}
            onChange={(next) => {
              setFiles(next);
              setReviewed(false);
            }}
          />
          <label>
            Creator
            <input
              value={creator}
              maxLength={120}
              onChange={(e) => {
                setCreator(e.target.value);
                setReviewed(false);
              }}
            />
          </label>
          <label>
            Title for this batch
            <input
              value={title}
              maxLength={200}
              onChange={(e) => {
                setTitle(e.target.value);
                setReviewed(false);
              }}
            />
          </label>
          <label>
            Was AI used?
            <select
              value={ai}
              onChange={(e) => {
                setAi(e.target.value);
                setReviewed(false);
              }}
            >
              <option value="none">No</option>
              <option value="generated">Made with AI</option>
              <option value="edited">Edited with AI</option>
            </select>
          </label>
          <p>
            Public user declarations: creator {creator || "required"}, title{" "}
            {title || "required"}, AI disclosure {ai}. Organization:{" "}
            {activeProfile?.organizationName || "not configured"}; profile:{" "}
            {activeProfile?.profileName || "not configured"}. Profile revision:{" "}
            {profileRevision}. Signing certificate SHA-256:{" "}
            <code>{signingFingerprint || "not configured"}</code>.
          </p>
          <label className="check">
            <input
              type="checkbox"
              checked={reviewed}
              onChange={(e) => setReviewed(e.target.checked)}
            />
            I reviewed the public declarations and authorize signing for the
            selected files.
          </label>
          <button
            disabled={
              busy ||
              !activeProfile ||
              (choice !== null && !choice.available) ||
              !signingFingerprint ||
              !files.length ||
              !creator.trim() ||
              !title.trim() ||
              !reviewed
            }
            onClick={() =>
              run(async () => {
                let submitted = 0;
                for (const file of files) {
                  const body = new FormData();
                  body.append("file", file);
                  body.append("creator", creator);
                  body.append("title", title);
                  body.append("aiDisclosure", ai);
                  body.append("acknowledgePublicClaims", "true");
                  if (choice) {
                    body.append("signingOptionId", choice.id);
                    body.append(
                      "signingOptionRevision",
                      String(choice.revision),
                    );
                  }
                  body.append(
                    "expectedProfileRevision",
                    String(profileRevision),
                  );
                  body.append(
                    "expectedIdentityFingerprint",
                    signingFingerprint!,
                  );
                  const response = await fetch("/api/v1/jobs", {
                    method: "POST",
                    headers: {
                      "X-Admin-Token": token,
                      "Idempotency-Key": crypto.randomUUID(),
                    },
                    body,
                  });
                  if (!response.ok) {
                    await load();
                    throw new Error(
                      `${submitted} files queued; ${file.name} failed (${response.status}). Remaining files were not queued.`,
                    );
                  }
                  submitted++;
                }
                await load();
                setFiles([]);
                setReviewed(false);
                setMessage(`${submitted} files queued.`);
              })
            }
          >
            Sign batch
          </button>
        </>
      )}
      {mode !== "batch" && (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Title / owner</th>
                  <th>Status</th>
                  <th>Downloads</th>
                </tr>
              </thead>
              <tbody>
                {jobs.map((job) => (
                  <tr key={job.id}>
                    <td>
                      {job.title}
                      <br />
                      {job.owner}
                    </td>
                    <td>
                      {job.state} · attempt {job.attempts}
                      {admin && (
                        <p>
                          Execution: {job.sandboxMode} · {job.maxMemoryMb} MiB ·{" "}
                          {job.maxCpuSeconds} CPU seconds.
                        </p>
                      )}
                      {job.error && <p>{job.error}</p>}
                      {job.state === "FAILED" && canSign && (
                        <button
                          disabled={busy}
                          onClick={() =>
                            run(async () => {
                              await request(`jobs/${job.id}/retry`, "POST");
                              await load();
                            })
                          }
                        >
                          Retry
                        </button>
                      )}
                    </td>
                    <td>
                      {["original", "signed", "report"].map((version) => (
                        <button
                          key={version}
                          className="secondary"
                          disabled={
                            busy ||
                            (version !== "original" &&
                              job.state !== "COMPLETED")
                          }
                          onClick={() =>
                            run(async () => {
                              const response = await fetch(
                                version === "report"
                                  ? `/api/v1/jobs/${job.id}/report`
                                  : `/api/v1/jobs/${job.id}/download?version=${version}`,
                                { headers: { "X-Admin-Token": token } },
                              );
                              if (!response.ok)
                                throw new Error(
                                  `Download failed (${response.status}).`,
                                );
                              const url = URL.createObjectURL(
                                await response.blob(),
                              );
                              const link = document.createElement("a");
                              link.href = url;
                              link.download =
                                version +
                                (version === "report"
                                  ? ".json"
                                  : capabilities.find(
                                      (c) => c.mime === job.format,
                                    )?.extension || ".bin");
                              link.click();
                              setTimeout(() => URL.revokeObjectURL(url), 1000);
                            })
                          }
                        >
                          {version}
                        </button>
                      ))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="actions">
            <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
              Previous
            </button>
            <button
              disabled={jobs.length < 50}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </button>
          </div>
          {admin && retention && (
            <>
              <h3>Storage retention</h3>
              <p>
                {retention.provider}. Completed/failed job assets are deleted
                automatically after the configured retention period;
                queued/running jobs are retained.
              </p>
              <label>
                Retention days
                <input
                  type="number"
                  min={1}
                  max={3650}
                  value={retention.retentionDays}
                  onChange={(e) => {
                    setRetention({
                      ...retention,
                      retentionDays: Number(e.target.value),
                    });
                    setAck(false);
                  }}
                />
              </label>
              <label className="check">
                <input
                  type="checkbox"
                  checked={ack}
                  onChange={(e) => setAck(e.target.checked)}
                />
                I acknowledge permanent deletion of expired original and signed
                assets.
              </label>
              <button
                disabled={busy || !ack}
                onClick={() =>
                  run(async () => {
                    setRetention(
                      await request("admin/storage", "PUT", {
                        revision: retention.revision,
                        retentionDays: retention.retentionDays,
                        acknowledgeDeletion: true,
                      }),
                    );
                    setAck(false);
                    setMessage("Retention settings saved.");
                  })
                }
              >
                Save retention
              </button>
            </>
          )}
        </>
      )}
      <p role="status">{busy ? "Working…" : message}</p>
    </section>
  );
}
