const nativeFetch = globalThis.fetch.bind(globalThis);
let activeWorkspace: number | null = null;
export function setActiveWorkspace(id: number | null) {
  activeWorkspace = id;
}
export function portalFetch(
  input: RequestInfo | URL,
  options?: RequestInit,
): Promise<Response> {
  const origin = globalThis.location?.origin ?? "http://localhost";
  const url = new URL(
    typeof input === "string"
      ? input
      : input instanceof URL
        ? input.href
        : input.url,
    origin,
  );
  if (
    url.origin !== origin ||
    !url.pathname.startsWith("/api/") ||
    activeWorkspace === null
  )
    return nativeFetch(input, options);
  const headers = new Headers(
    input instanceof Request ? input.headers : undefined,
  );
  new Headers(options?.headers).forEach((value, key) =>
    headers.set(key, value),
  );
  if (!headers.has("X-Workspace-Id"))
    headers.set("X-Workspace-Id", String(activeWorkspace));
  return nativeFetch(input, { ...options, headers });
}

/** A pending upload keeps the workspace chosen when its component rendered. */
export function workspaceFetch(
  workspace: number | null = activeWorkspace,
): typeof portalFetch {
  const snapshot = workspace !== null && workspace > 0 ? workspace : null;
  return (input, options) => {
    if (snapshot === null) return portalFetch(input, options);
    const origin = globalThis.location?.origin ?? "http://localhost";
    const url = new URL(
      typeof input === "string"
        ? input
        : input instanceof URL
          ? input.href
          : input.url,
      origin,
    );
    if (url.origin !== origin || !url.pathname.startsWith("/api/"))
      return portalFetch(input, options);
    const headers = new Headers(
      input instanceof Request ? input.headers : undefined,
    );
    new Headers(options?.headers).forEach((value, key) =>
      headers.set(key, value),
    );
    if (!headers.has("X-Workspace-Id"))
      headers.set("X-Workspace-Id", String(snapshot));
    return portalFetch(input, { ...options, headers });
  };
}
