import { useState, type ReactNode } from "react";
export default function CertificateWorkspace({
  software,
  hardware,
}: {
  software: ReactNode;
  hardware: ReactNode;
}) {
  const [tab, setTab] = useState("software");
  return (
    <div className="certificate-workspace">
      <div
        className="console-tabs"
        onKeyDown={(e) => {
          if (["ArrowRight", "ArrowLeft", "Home", "End"].includes(e.key)) {
            e.preventDefault();
            const next =
              e.key === "Home"
                ? "software"
                : e.key === "End"
                  ? "hardware"
                  : tab === "software"
                    ? "hardware"
                    : "software";
            setTab(next);
            document
              .getElementById(next === "software" ? "pkcs12-tab" : "hsm-tab")
              ?.focus();
          }
        }}
        role="tablist"
        aria-label="Certificate type"
      >
        <button
          id="pkcs12-tab"
          role="tab"
          aria-selected={tab === "software"}
          aria-controls="certificate-content"
          onClick={() => setTab("software")}
        >
          PKCS#12
        </button>
        <button
          id="hsm-tab"
          role="tab"
          aria-selected={tab === "hardware"}
          aria-controls="certificate-content"
          onClick={() => setTab("hardware")}
        >
          HSM
        </button>
      </div>
      <p className="console-caption">
        {tab === "software"
          ? "Import your certificate and private key from a .p12 or .pfx file."
          : "Connect a hardware token. Your private key stays inside the device."}
      </p>
      <div
        id="certificate-content"
        role="tabpanel"
        aria-labelledby={tab === "software" ? "pkcs12-tab" : "hsm-tab"}
      >
        {tab === "software" ? software : hardware}
      </div>
    </div>
  );
}
