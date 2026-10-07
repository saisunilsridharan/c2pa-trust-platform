import { Icon } from "./WorkspaceTools";
function object(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}
export default function InspectionTrustSummary({
  report,
}: {
  report: unknown;
}) {
  const result = object(report),
    publicTrust = object(result.portal_public_trust),
    privateTrust = object(result.portal_trust_policy);
  const publicStatus =
    publicTrust.publicTrustVerified === true
      ? "Verified"
      : publicTrust.configured === false
        ? "Not set up"
        : publicTrust.enabled === false
          ? "Off"
          : publicTrust.current === false ||
              typeof publicTrust.status === "string"
            ? "Unavailable or out of date"
            : "Not verified";
  const privateStatus =
    result.validation_state === "Trusted" &&
    typeof privateTrust.source === "string" &&
    privateTrust.source.startsWith("PRIVATE_")
      ? "Verified"
      : "Not verified";
  const rows = [
    [
      "inspect",
      "File signature",
      typeof result.validation_state === "string"
        ? result.validation_state
        : "Unknown",
    ],
    ["private-trust", "Private trust", privateStatus],
    ["public-trust", "Public trust", publicStatus],
  ];
  if (publicTrust.configured === true)
    rows.push(
      [
        "timestamps",
        "Timestamp trust",
        publicTrust.publicTimestampTrusted === true
          ? "Verified"
          : "Not verified",
      ],
      [
        "revocation",
        "Signer certificate",
        publicTrust.onlineRevocationChecked === true
          ? "Checked: good"
          : typeof publicTrust.revocationStatus === "string"
            ? publicTrust.revocationStatus.toLowerCase().replaceAll("_", " ")
            : "Not checked",
      ],
    );
  return (
    <div role="status">
      <div className="trust-result-grid">
        {rows.map(([icon, label, status]) => (
          <div className="trust-result" key={label}>
            <strong>
              <Icon name={icon} size={16} /> {label}
            </strong>
            <p>{status}</p>
          </div>
        ))}
      </div>
      {publicTrust.configured === true && (
        <p className="result-note">
          Timestamp and earlier-file certificate status: not checked.
        </p>
      )}
    </div>
  );
}
