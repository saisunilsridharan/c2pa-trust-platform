import { useEffect, useRef, useState } from "react";
import type { allowedPages } from "./navigation";
export function Icon({
  name = "dashboard",
  size = 18,
}: {
  name?: string;
  size?: number;
}) {
  const paths: Record<string, string> = {
    dashboard: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
    sign: "m15 4 5 5 M4 20l4-1L20 7a2 2 0 0 0-5-5L3 14z",
    jobs: "M8 5h13 M8 12h13 M8 19h13 M3 5h.01 M3 12h.01 M3 19h.01",
    inspect: "M10 17a7 7 0 1 0 0-14 7 7 0 0 0 0 14z m5-2 6 6",
    notifications: "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4",
    search: "M10 17a7 7 0 1 0 0-14 7 7 0 0 0 0 14z m5-2 6 6",
    sun: "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M12 2v2 M12 20v2 M2 12h2 M20 12h2 M5 5l1 1 M18 18l1 1 M5 19l1-1 M18 6l1-1",
    moon: "M21 13A9 9 0 0 1 11 3a9 9 0 1 0 10 10z",
    collapse: "M3 4h18v16H3z M9 4v16 m7-11-3 3 3 3",
    arrow: "M5 12h14 m-5-5 5 5-5 5",
    shield: "M12 3 3 7v5c0 5 9 9 9 9s9-4 9-9V7z m-4 9 3 3 5-5",
    settings:
      "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M9 3h6l1 4 4 1v8l-4 1-1 4H9l-1-4-4-1V8l4-1z",
    users:
      "M9 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M2 21v-3a5 5 0 0 1 5-5h4a5 5 0 0 1 5 5v3 M17 4a4 4 0 0 1 0 8 M19 14a4 4 0 0 1 3 4v3",
    key: "M8 15a6 6 0 1 0 0-12 6 6 0 0 0 0 12z m4-3 9 9 m-3-3 3-3 m-6 0 3-3",
    storage: "M4 5h16v5H4z M4 14h16v5H4z M7 7h.01 M7 16h.01",
    workers: "M3 3h7v7H3z M14 14h7v7h-7z M14 3h7v7h-7z M6 14v4h5",
    certificate:
      "M8 17v5l4-2 4 2v-5 M12 18a8 8 0 1 0 0-16 8 8 0 0 0 0 16z m-3-9 2 2 4-4",
  };
  let key = name;
  if (name === "account") key = "users";
  if (name === "batch") key = "jobs";
  if (name === "certificates") key = "certificate";
  if (
    [
      "private-trust",
      "public-trust",
      "revocation",
      "audit",
      "audit-storage",
      "security",
    ].includes(name)
  )
    key = "shield";
  if (
    [
      "hardware",
      "identities",
      "private-ca",
      "renewal",
      "choices",
      "timestamps",
    ].includes(name)
  )
    key = "certificate";
  if (["credentials", "api-keys", "password"].includes(name)) key = "key";
  if (["workspaces", "users", "oidc"].includes(name)) key = "users";
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.65"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={paths[key] || paths.settings} />
    </svg>
  );
}
export function WorkspaceTools({
  pages,
  navigate,
  collapsed,
  onCollapse,
}: {
  pages: ReturnType<typeof allowedPages>;
  navigate: (id: string) => void;
  collapsed: boolean;
  onCollapse: () => void;
}) {
  const [dark, setDark] = useState(() => {
    try {
      return localStorage.getItem("portal-theme") === "dark";
    } catch {
      return false;
    }
  });
  const [query, setQuery] = useState(""),
    [selected, setSelected] = useState(0);
  const dialog = useRef<HTMLDialogElement>(null),
    input = useRef<HTMLInputElement>(null),
    trigger = useRef<HTMLButtonElement>(null);
  const results = pages.filter((p) =>
    (p[1] + " " + p[2]).toLowerCase().includes(query.toLowerCase().trim()),
  );
  useEffect(() => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    try {
      localStorage.setItem("portal-theme", dark ? "dark" : "light");
    } catch {}
  }, [dark]);
  function open() {
    setQuery("");
    setSelected(0);
    dialog.current?.showModal();
    input.current?.focus();
  }
  function close() {
    dialog.current?.close();
    trigger.current?.focus();
  }
  function choose(id: string) {
    close();
    navigate(id);
  }
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        dialog.current?.open ? close() : open();
      }
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, []);
  return (
    <div className="workspace-tools">
      <button
        className="icon-button collapse-control"
        aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
        aria-expanded={!collapsed}
        onClick={onCollapse}
      >
        <Icon name="collapse" />
      </button>
      <button
        ref={trigger}
        aria-label="Search pages"
        className="search-trigger"
        onClick={open}
      >
        <Icon name="search" />
        <span>Search pages…</span>
        <kbd>⌘ / Ctrl K</kbd>
      </button>
      <button
        className="icon-button"
        aria-label={dark ? "Switch to light theme" : "Switch to dark theme"}
        onClick={() => setDark(!dark)}
      >
        <Icon name={dark ? "sun" : "moon"} />
      </button>
      <dialog
        ref={dialog}
        className="command-dialog"
        aria-labelledby="command-title"
        onClose={() => trigger.current?.focus()}
        onClick={(e) => {
          if (e.target === dialog.current) {
            const r = dialog.current.getBoundingClientRect();
            if (
              e.clientX < r.left ||
              e.clientX > r.right ||
              e.clientY < r.top ||
              e.clientY > r.bottom
            )
              close();
          }
        }}
      >
        <h2 id="command-title" className="sr-only">
          Navigate your workspace
        </h2>
        <div className="command-input">
          <Icon name="search" />
          <input
            ref={input}
            aria-label="Search pages"
            placeholder="Where would you like to go?"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setSelected(0);
            }}
            onKeyDown={(e) => {
              if (e.key === "ArrowDown") {
                e.preventDefault();
                setSelected((i) => Math.min(i + 1, results.length - 1));
              }
              if (e.key === "ArrowUp") {
                e.preventDefault();
                setSelected((i) => Math.max(0, i - 1));
              }
              if (e.key === "Enter" && results[selected]) {
                e.preventDefault();
                choose(results[selected][0]);
              }
            }}
          />
          <button
            className="icon-button"
            aria-label="Close search"
            onClick={close}
          >
            Esc
          </button>
        </div>
        <div className="command-results">
          {results.length ? (
            results.map((p, i) => (
              <button
                key={p[0]}
                className={
                  selected === i ? "command-result selected" : "command-result"
                }
                onMouseEnter={() => setSelected(i)}
                onClick={() => choose(p[0])}
              >
                <Icon name={p[0]} />
                <span>
                  {p[2]}
                  <small>{p[1]}</small>
                </span>
                <Icon name="arrow" size={14} />
              </button>
            ))
          ) : (
            <p className="empty-search">
              No pages found. Try a different search.
            </p>
          )}
        </div>
        <div className="command-footer">
          ↑ ↓ to browse · Enter to open · Esc to close
        </div>
      </dialog>
    </div>
  );
}
