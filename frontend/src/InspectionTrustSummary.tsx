function object(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
export default function InspectionTrustSummary({ report }: { report: unknown }) {
  const result = object(report), publicTrust = object(result.portal_public_trust), privateTrust = object(result.portal_trust_policy);
  const publicStatus = publicTrust.publicTrustVerified === true ? "verified" : publicTrust.configured === false ? "not configured" : publicTrust.enabled === false ? "disabled" : publicTrust.current === false || typeof publicTrust.status === "string" ? "unavailable or stale" : "not verified";
  const privateStatus = result.validation_state === "Trusted" && typeof privateTrust.source === "string" && privateTrust.source.startsWith("PRIVATE_") ? "trusted by the configured private policy" : "not verified";
  return <div role="status"><p>Content validation: {typeof result.validation_state === "string" ? result.validation_state : "unknown"}</p><p>Private signer trust: {privateStatus}</p><p>Official C2PA signer trust: {publicStatus}</p>{publicTrust.configured === true && <><p>Official timestamp trust: {publicTrust.publicTimestampTrusted === true ? "verified" : "not verified"}</p><p>Public signer-chain revocation: {publicTrust.onlineRevocationChecked === true ? "checked, good" : typeof publicTrust.revocationStatus === "string" ? publicTrust.revocationStatus.toLowerCase().replaceAll("_", " ") : "not checked"}</p><p>Timestamp and ingredient revocation: not checked</p></>}</div>;
}
