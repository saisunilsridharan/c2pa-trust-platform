import { Icon, WorkspaceTools } from "./WorkspaceTools";
import React, { createContext, useContext, useEffect, useState } from "react";
import { allowedPages, pageFromPath, type PortalAccount } from "./navigation";
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
      ? `${current?.[2] ?? "Page unavailable"} · Trust Portal`
      : "Sign in · Trust Portal";
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
                  Trust Portal<small>CONTENT AUTHENTICITY</small>
                </span>
              </div>
              <div className="workspace-label">
                WORKSPACE <strong>#{user.workspaceId}</strong>
              </div>
              <nav aria-label="Main navigation">
                {[...new Set(visible.map((p) => p[1]))].map((group) => (
                  <div className="nav-group" key={group}>
                    <h2>{group}</h2>
                    {visible
                      .filter((p) => p[1] === group)
                      .map((p) => (
                        <div
                          key={p[0]}
                          className={
                            route === p[0] ? "nav-item active" : "nav-item"
                          }
                          aria-current={route === p[0] ? "page" : undefined}
                        >
                          {link(p[0], p[2], true)}
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
                          ? "Your workspace for trusted content."
                          : "Manage " +
                            current[2].toLowerCase() +
                            " for your workspace."
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
                <h1>Welcome to Trust Portal</h1>
                <p>Sign in to manage content authenticity and provenance.</p>
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
                  <div className="dashboard-hero">
                    <div className="hero-copy">
                      <span className="eyebrow">
                        <Icon name="shield" size={14} /> CONTENT AUTHENTICITY,
                        CONNECTED
                      </span>
                      <h2>
                        Create with confidence.
                        <br />
                        Publish with provenance.
                      </h2>
                      <p>
                        Bring your content, credentials and trust policies
                        together in one secure workspace.
                      </p>
                      <div className="hero-links">
                        {link(
                          user.role === "VIEWER" ? "inspect" : "sign",
                          user.role === "VIEWER"
                            ? "Verify content →"
                            : "Sign content →",
                        )}
                        {link("jobs", "View signing jobs")}
                      </div>
                    </div>
                    <div className="provenance-visual" aria-hidden="true">
                      <div className="orbit orbit-one" />
                      <div className="orbit orbit-two" />
                      <div className="provenance-core">
                        <Icon name="shield" size={52} />
                        <span>C2PA</span>
                      </div>
                      <span className="orbit-label label-one">Content</span>
                      <span className="orbit-label label-two">Identity</span>
                      <span className="orbit-label label-three">
                        Provenance
                      </span>
                    </div>
                  </div>
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
                  <section>
                    <h2>Your content workflow</h2>
                    <p>Create, track and inspect content credentials.</p>
                    <div className="action-grid">
                      {visible
                        .filter((p) =>
                          ["sign", "jobs", "inspect"].includes(p[0]),
                        )
                        .map((p) => (
                          <article key={p[0]}>
                            <span className="action-symbol">
                              <Icon name={p[0]} size={22} />
                            </span>
                            <h3>{p[2]}</h3>
                            {link(p[0], "Open workspace →")}
                          </article>
                        ))}
                    </div>
                  </section>
                  {user.role === "ADMIN" && (
                    <section>
                      <h2>Workspace configuration</h2>
                      <p>
                        Manage providers and policies on dedicated pages. Save,
                        test and activate configurations before use.
                      </p>
                      <div className="quick-links">
                        {[
                          "profile",
                          "identities",
                          "public-trust",
                          "storage",
                          "workers",
                        ].map((id) => {
                          const p = visible.find((p) => p[0] === id);
                          return p ? (
                            <React.Fragment key={id}>
                              {link(id, p[2])}
                            </React.Fragment>
                          ) : null;
                        })}
                      </div>
                    </section>
                  )}
                </>
              )}
              {children}
            </div>
            <footer className="page-footer">
              Trust Portal <span>Content credentials · C2PA</span>
            </footer>
          </main>
        </div>
      </div>
    </RouteContext.Provider>
  );
}
