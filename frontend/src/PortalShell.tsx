import { Icon, WorkspaceTools } from "./WorkspaceTools";
import React, { createContext, useContext, useEffect, useState } from "react";
import {
  allowedPages,
  sidebarPages,
  pageFromPath,
  type PortalAccount,
} from "./navigation";
const RouteContext = createContext("dashboard");
export function Page({
  id,
  children,
}: {
  id: string;
  children: React.ReactNode;
}) {
  return useContext(RouteContext) === id ? <>{children}</> : null;
}
export function PortalShell({
  user,
  ready,
  active,
  available,
  onSignOut,
  children,
}: {
  user: PortalAccount | null;
  ready: boolean;
  active: {
    organizationName: string;
    profileName: string;
    formats: string[];
    maxUploadMb: number;
  } | null;
  available: boolean;
  onSignOut: () => void;
  children: React.ReactNode;
}) {
  const [route, setRoute] = useState(() => pageFromPath(location.pathname)),
    [menu, setMenu] = useState(false),
    [collapsed, setCollapsed] = useState(false);
  const enabled =
    ready && !!user && !user.passwordChangeRequired && user.workspaceId > 0;
  const visible = allowedPages(user),
    current = visible.find((p) => p[0] === route);
  const navigate = (id: string) => {
    history.pushState(null, "", "/portal/" + id);
    setRoute(id);
    setMenu(false);
    window.scrollTo(0, 0);
  };
  useEffect(() => {
    const pop = () => {
      setRoute(pageFromPath(location.pathname));
      setMenu(false);
    };
    window.addEventListener("popstate", pop);
    return () => window.removeEventListener("popstate", pop);
  }, []);
  useEffect(() => {
    if (
      enabled &&
      (location.pathname === "/login" || location.pathname === "/")
    ) {
      history.replaceState(null, "", "/portal/dashboard");
      setRoute("dashboard");
    }
  }, [enabled]);
  useEffect(() => {
    document.title = enabled
      ? `${current?.[2] ?? "Page unavailable"} · C2PA Console`
      : "Sign in · C2PA Console";
  }, [enabled, current]);
  function link(id: string, label: string, icon = false) {
    return (
      <a
        aria-current={route === id ? "page" : undefined}
        title={collapsed && icon ? label : undefined}
        href={"/portal/" + id}
        onClick={(e) => {
          if (
            e.button === 0 &&
            !e.ctrlKey &&
            !e.metaKey &&
            !e.shiftKey &&
            !e.altKey
          ) {
            e.preventDefault();
            navigate(id);
          }
        }}
      >
        {icon && <Icon name={id} />}
        <span className={icon ? "nav-text" : undefined}>{label}</span>
      </a>
    );
  }
  return (
    <RouteContext.Provider value={enabled && current ? route : ""}>
      <div
        className={
          "shell " +
          (!enabled ? "auth-shell" : collapsed ? "sidebar-collapsed" : "")
        }
      >
        {enabled && (
          <>
            <button
              className="mobile-toggle"
              aria-controls="portal-sidebar"
              aria-expanded={menu}
              onClick={() => setMenu(!menu)}
            >
              ☰ Menu
            </button>
            {menu && (
              <button
                className="nav-backdrop"
                aria-label="Close navigation"
                onClick={() => setMenu(false)}
              />
            )}
            <aside
              id="portal-sidebar"
              className={menu ? "sidebar open" : "sidebar"}
            >
              <div className="brand">
                <span className="brand-mark">
                  <Icon name="shield" size={22} />
                </span>
                <span>
                  C2PA Console<small>Content Credentials</small>
                </span>
              </div>
              <div className="workspace-label">
                WORKSPACE <strong>#{user.workspaceId}</strong>
              </div>
              <nav aria-label="Main navigation">
                {["Work", "Manage", "Monitor"].map((group) => (
                  <div className="nav-group" key={group}>
                    <h2>{group}</h2>
                    {sidebarPages(user)
                      .filter((p) =>
                        p[0] === "settings" ||
                        p[0] === "account" ||
                        p[0] === "private-trust"
                          ? group === "Manage"
                          : p[0] === "audit"
                            ? group === "Monitor"
                            : p[1] === group,
                      )
                      .map((p) => (
                        <div
                          className={
                            route === p[0] ? "nav-item active" : "nav-item"
                          }
                          key={p[0]}
                        >
                          {link(
                            p[0],
                            p[0] === "private-trust" ? "Trust store" : p[2],
                            true,
                          )}
                        </div>
                      ))}
                  </div>
                ))}
              </nav>
              <a
                className="api-link"
                href="/swagger-ui/index.html"
                target="_blank"
                rel="noreferrer"
              >
                API documentation ↗
              </a>
            </aside>
          </>
        )}
        <div className="main-column">
          {enabled && (
            <header className="topbar">
              <div>
                <strong>{active?.organizationName || "Workspace setup"}</strong>
                <span className="environment-tag">C2PA PORTAL</span>
              </div>
              <WorkspaceTools
                pages={visible}
                navigate={navigate}
                collapsed={collapsed}
                onCollapse={() => setCollapsed(!collapsed)}
              />
              <div className="account-menu">
                <span className="avatar">
                  {user.username.slice(0, 1).toUpperCase()}
                </span>
                <span>
                  {user.username}
                  <small>{user.role}</small>
                </span>
                <button className="secondary compact" onClick={onSignOut}>
                  Sign out
                </button>
              </div>
            </header>
          )}
          <main id="main-content">
            <a className="skip-link" href="#page-content">
              Skip to page content
            </a>
            {enabled ? (
              <>
                <nav className="breadcrumbs" aria-label="Breadcrumb">
                  {link("dashboard", "Home")}
                  <span>/</span>
                  <span>{current?.[1] ?? "Navigation"}</span>
                  <span>/</span>
                  <strong>{current?.[2] ?? "Page unavailable"}</strong>
                </nav>
                <header className="page-heading">
                  <div>
                    <h1>{current?.[2] ?? "Page unavailable"}</h1>
                    <p>
                      {current
                        ? route === "dashboard"
                          ? "Validate a file or sign it with your profile."
                          : ""
                        : "This page is unavailable for your role or does not exist."}
                    </p>
                  </div>
                  <span className="workspace-chip">
                    Workspace {user.workspaceId}
                  </span>
                </header>
              </>
            ) : (
              <header className="login-heading">
                <div className="brand-mark">
                  <Icon name="shield" size={26} />
                </div>
                <h1>C2PA Console</h1>
                <p>Sign in to C2PA Console.</p>
              </header>
            )}
            <div id="page-content">
              {enabled && !current && (
                <section>
                  <h2>Choose an available page</h2>
                  {link("dashboard", "Return to dashboard")}
                </section>
              )}
              {enabled && route === "dashboard" && (
                <>
                  <div className="console-shortcuts">
                    {visible
                      .filter((p) =>
                        ["inspect", "sign", "batch"].includes(p[0]),
                      )
                      .map((p) => (
                        <article key={p[0]}>
                          <Icon name={p[0]} size={25} />
                          {link(p[0], p[2])}
                        </article>
                      ))}
                  </div>{" "}
                  <div className="metric-grid">
                    <article className="metric">
                      <span>Signing readiness</span>
                      <strong className={available ? "positive" : ""}>
                        {available ? "Ready to sign" : "Setup required"}
                      </strong>
                      <small>Current workspace configuration</small>
                    </article>
                    <article className="metric">
                      <span>Active profile</span>
                      <strong>{active?.profileName || "Not activated"}</strong>
                      <small>
                        {active?.organizationName ||
                          "Configure your organization"}
                      </small>
                    </article>
                    <article className="metric">
                      <span>Enabled formats</span>
                      <strong>{active?.formats.length ?? 0}</strong>
                      <small>
                        {active
                          ? `Upload limit: ${active.maxUploadMb} MB`
                          : "Activate a profile first"}
                      </small>
                    </article>
                  </div>
                </>
              )}
              {enabled && ["settings", "account"].includes(route) && (
                <div className="settings-directory">
                  {[
                    ...new Set(
                      visible
                        .filter(
                          (p) =>
                            !["settings", "account"].includes(p[0]) &&
                            (route === "account"
                              ? p[1] === "My account" || p[0] === "workspaces"
                              : !sidebarPages(user).some(
                                  (item) => item[0] === p[0],
                                ) &&
                                ![
                                  "workspaces",
                                  "identities",
                                  "hardware",
                                  "choices",
                                ].includes(p[0])),
                        )
                        .map((p) => p[1]),
                    ),
                  ].map((group) => (
                    <section key={group}>
                      <details
                        className="settings-group"
                        data-default-open={
                          group === "Configuration" || route === "account"
                            ? "true"
                            : "false"
                        }
                        open={group === "Configuration" || route === "account"}
                      >
                        <summary>
                          {group === "Configuration"
                            ? "App settings"
                            : group === "Trust & compliance"
                              ? "Trust & safety"
                              : group === "Administration"
                                ? "Team access"
                                : group === "My account"
                                  ? "Your account"
                                  : group}
                        </summary>
                        <div className="setting-grid">
                          {visible
                            .filter(
                              (p) =>
                                p[1] === group &&
                                !["settings", "account"].includes(p[0]) &&
                                (route === "account"
                                  ? p[1] === "My account" ||
                                    p[0] === "workspaces"
                                  : !sidebarPages(user).some(
                                      (item) => item[0] === p[0],
                                    ) &&
                                    ![
                                      "workspaces",
                                      "identities",
                                      "hardware",
                                      "choices",
                                    ].includes(p[0])),
                            )
                            .map((p) => (
                              <div className="setting-tile" key={p[0]}>
                                <span className="setting-icon">
                                  <Icon name={p[0]} size={22} />
                                </span>
                                {link(p[0], p[2])}
                                <Icon name="arrow" size={16} />
                              </div>
                            ))}
                        </div>
                      </details>
                    </section>
                  ))}
                </div>
              )}
              {children}
            </div>
            <footer className="page-footer">
              C2PA Console <span>Content Credentials</span>
            </footer>
          </main>
        </div>
      </div>
    </RouteContext.Provider>
  );
}
