import { useEffect, useState } from "react";
import { workspaceFetch } from "./portalFetch";
type Config = {
  module: string;
  slotListIndex: number;
  keyAlias: string;
  pinCredential: string;
};
type Identity = {
  id: string;
  configuration: Config;
  fingerprint: string;
  createdAt: string;
  testedAt: string | null;
};
export default function HardwareIdentitiesPanel({
  token,
  profileRevision,
}: {
  token: string;
  profileRevision: number | null;
}) {
  const fetch = workspaceFetch();
  const [config, setConfig] = useState<Config>({
      module: "",
      slotListIndex: 0,
      keyAlias: "",
      pinCredential: "",
    }),
    [chain, setChain] = useState(""),
    [rows, setRows] = useState<Identity[]>([]),
    [selected, setSelected] = useState(""),
    [label, setLabel] = useState(""),
    [page, setPage] = useState(0),
    [ack, setAck] = useState(false),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  const current = rows.find((r) => r.id === selected);
  async function request(path = "", method = "GET", body?: unknown) {
    const r = await fetch("/api/v1/admin/hardware-identities" + path, {
      method,
      headers: { "X-Admin-Token": token, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!r.ok)
      throw new Error(
        r.status === 409
          ? "Test the identity and review the active profile before approval."
          : `Hardware identity request failed (${r.status}). Check module, slot, PIN credential, key alias, certificate and worker readiness.`,
      );
    return r.json();
  }
  async function load() {
    setRows(await request("?page=" + page));
  }
  useEffect(() => {
    let live = true;
    request("?page=" + page)
      .then((rows) => {
        if (live) setRows(rows);
      })
      .catch((e) => {
        if (live) setMessage(e.message);
      });
    return () => {
      live = false;
    };
  }, [token, page]);
  useEffect(() => setAck(false), [selected, profileRevision, label]);
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
      <h2>PKCS#11 signing identities</h2>
      <p>
        Use an existing token key and its matching EC P-256 C2PA certificate
        chain. Install the vendor module and provision the token on the server
        first. Configure the module, slot, alias, encrypted PIN credential and
        certificate here. The key stays in the token. Public certificate trust
        and timestamps require separate verification.
      </p>
      <label>
        Installed PKCS#11 module (absolute path)
        <input
          maxLength={2000}
          value={config.module}
          onChange={(e) => setConfig({ ...config, module: e.target.value })}
        />
      </label>
      <p>
        System modules must be root-owned libraries under /usr/lib,
        /usr/local/lib or /opt. The bundled development SoftHSM module is also
        accepted for testing.
      </p>
      <label>
        Slot list index
        <input
          type="number"
          min={0}
          max={128}
          value={config.slotListIndex}
          onChange={(e) =>
            setConfig({ ...config, slotListIndex: Number(e.target.value) })
          }
        />
      </label>
      <label>
        Token key alias
        <input
          maxLength={200}
          value={config.keyAlias}
          onChange={(e) => setConfig({ ...config, keyAlias: e.target.value })}
        />
      </label>
      <label>
        PIN credential ID (save through encrypted credentials in this workspace)
        <input
          maxLength={255}
          value={config.pinCredential}
          onChange={(e) =>
            setConfig({ ...config, pinCredential: e.target.value })
          }
        />
      </label>
      <label>
        Public signing certificate and issuer chain (PEM, leaf first)
        <textarea
          maxLength={64000}
          value={chain}
          onChange={(e) => setChain(e.target.value)}
        />
      </label>
      <button
        disabled={
          busy ||
          !config.module ||
          !config.keyAlias ||
          !config.pinCredential ||
          !chain
        }
        onClick={() =>
          run(async () => {
            const row = await request("", "POST", {
              configuration: config,
              certificateChainPem: chain,
            });
            setSelected(row.id);
            setPage(0);
            setChain("");
            await load();
            setMessage(
              "Immutable identity draft saved. Run its signing test before approval.",
            );
          })
        }
      >
        Save identity draft
      </button>
      <button disabled={busy} onClick={() => run(load)}>
        Refresh identities
      </button>
      <label>
        Identity version
        <select value={selected} onChange={(e) => setSelected(e.target.value)}>
          <option value="">Select identity version</option>
          {rows.map((r) => (
            <option key={r.id} value={r.id}>
              {r.configuration.keyAlias} · {r.fingerprint.slice(0, 12)} ·{" "}
              {r.testedAt ? "tested" : "untested"}
            </option>
          ))}
        </select>
      </label>
      {current && (
        <p>
          Certificate SHA-256: <code>{current.fingerprint}</code>. Last signing
          test:{" "}
          {current.testedAt
            ? new Date(current.testedAt).toLocaleString()
            : "not tested"}
          .
        </p>
      )}
      <button
        disabled={busy || !selected}
        onClick={() =>
          run(async () => {
            await request("/" + selected + "/test", "POST", {});
            await load();
            setMessage(
              "Real Rust C2PA signing and certificate/key matching passed. Hardware deployment compatibility still depends on your vendor module and token.",
            );
          })
        }
      >
        Test token & C2PA signing
      </button>
      <label>
        Approved choice name
        <input
          value={label}
          maxLength={120}
          onChange={(e) => setLabel(e.target.value)}
        />
      </label>
      <p>
        Publish with current active profile revision{" "}
        {profileRevision ?? "unconfigured"}. Later token/certificate changes
        require a new identity draft and signing choice. Withdraw old choices
        through approved signing choices.
      </p>
      <label className="check">
        <input
          type="checkbox"
          checked={ack}
          onChange={(e) => setAck(e.target.checked)}
        />
        I approve this tested identity with the active profile and acknowledge
        that public trust remains unverified.
      </label>
      <button
        disabled={
          busy ||
          !current?.testedAt ||
          !ack ||
          !label.trim() ||
          profileRevision == null
        }
        onClick={() =>
          run(async () => {
            await request("/" + selected + "/approve", "POST", {
              label,
              expectedProfileRevision: profileRevision,
              expectedIdentityFingerprint: current!.fingerprint,
              acknowledgePrivateTrust: true,
            });
            setAck(false);
            setLabel("");
            setMessage(
              "Hardware signing choice approved. Refresh signing choices to select it.",
            );
          })
        }
      >
        Approve hardware signing choice
      </button>
      <div className="actions">
        <button disabled={busy || !page} onClick={() => setPage(page - 1)}>
          Previous identities
        </button>
        <button
          disabled={busy || rows.length < 50}
          onClick={() => setPage(page + 1)}
        >
          Next identities
        </button>
      </div>
      <p role="status">{busy ? "Working with signing token…" : message}</p>
    </section>
  );
}
