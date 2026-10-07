export type PortalAccount = {
  role: string;
  platformAdministrator: boolean;
  username: string;
  workspaceId: number;
  id: number | null;
  passwordChangeRequired: boolean;
};
export const pages: [string, string, string, string?][] = [
  ["certificates", "Manage", "Certificates", "admin"],
  ["batch", "Work", "Batch sign", "signer"],
  ["dashboard", "Work", "Dashboard"],
  ["settings", "Configuration", "Settings", "admin"],
  ["account", "My account", "Account", "account"],
  ["sign", "Work", "Sign", "signer"],
  ["jobs", "Monitor", "Jobs"],
  ["inspect", "Work", "Validate"],
  ["notifications", "Manage", "Notifications"],
  ["profile", "Manage", "Signer profiles", "admin"],
  ["identities", "Certificates", "Software identities", "admin"],
  ["hardware", "Certificates", "Hardware identities", "admin"],
  ["choices", "Certificates", "Signing choices", "admin"],
  ["private-ca", "Certificates", "Certificate issuance", "admin"],
  ["renewal", "Certificates", "Certificate renewal", "admin"],
  ["private-trust", "Trust & compliance", "Private trust", "admin"],
  ["public-trust", "Trust & compliance", "Public trust", "admin"],
  ["revocation", "Trust & compliance", "Revocation policy", "admin"],
  ["timestamps", "Manage", "Timestamping", "admin"],
  ["audit", "Trust & compliance", "Audit integrity", "admin"],
  ["audit-storage", "Trust & compliance", "Audit retention", "admin"],
  ["credentials", "Configuration", "Credentials", "admin"],
  ["storage", "Configuration", "Content storage", "admin"],
  ["webhooks", "Configuration", "Webhooks", "admin"],
  ["processing", "Configuration", "Processing budgets", "admin"],
  ["workers", "Configuration", "Remote workers", "admin"],
  ["workspaces", "Administration", "Workspaces", "account"],
  ["users", "Administration", "Users & access", "platform"],
  ["oidc", "Administration", "Enterprise login", "platform"],
  ["login-policy", "Administration", "Login protection", "platform"],
  ["security", "My account", "Two-factor authentication", "account"],
  ["api-keys", "My account", "API keys", "account"],
  ["password", "My account", "Password", "account"],
];
export function allowedPages(user: PortalAccount | null) {
  return pages.filter(
    (p) =>
      !!user &&
      (!p[3] ||
        (p[3] === "admin" && user.role === "ADMIN") ||
        (p[3] === "platform" &&
          user.role === "ADMIN" &&
          user.platformAdministrator) ||
        (p[3] === "account" && user.id !== null) ||
        (p[3] === "signer" && user.role !== "VIEWER")),
  );
}
export function pageFromPath(path: string) {
  return path === "/"
    ? "dashboard"
    : path.replace(/^\/portal\//, "").replace(/\/$/, "");
}
export function sidebarPages(user: PortalAccount | null) {
  const order = [
    "dashboard",
    "inspect",
    "sign",
    "batch",
    "certificates",
    "profile",
    "private-trust",
    "timestamps",
    "notifications",
    "settings",
    "account",
    "audit",
    "jobs",
  ];
  const permitted = allowedPages(user);
  return order.flatMap((id) => {
    const page = permitted.find((p) => p[0] === id);
    return page ? [page] : [];
  });
}
