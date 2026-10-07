import assert from "node:assert/strict";
import { test, after } from "node:test";
import { readFile, writeFile, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import ts from "typescript";
const directory = await mkdtemp(join(tmpdir(), "c2pa-navigation-"));
const target = join(directory, "navigation.mjs");
await writeFile(
  target,
  ts.transpileModule(
    await readFile(new URL("../src/navigation.ts", import.meta.url), "utf8"),
    {
      compilerOptions: {
        target: ts.ScriptTarget.ES2022,
        module: ts.ModuleKind.ESNext,
      },
    },
  ).outputText,
);
const { allowedPages, pageFromPath } = await import(pathToFileURL(target));
after(() => rm(directory, { recursive: true, force: true }));
const account = {
  id: 1,
  username: "fixture",
  role: "VIEWER",
  workspaceId: 1,
  passwordChangeRequired: false,
  platformAdministrator: false,
};
const ids = (user) => allowedPages(user).map((p) => p[0]);
test("viewer navigation keeps verification/jobs but excludes signing and administration", () => {
  const visible = ids(account);
  assert(visible.includes("inspect"));
  assert(visible.includes("jobs"));
  for (const id of ["sign", "profile", "users", "oidc", "workers"])
    assert(!visible.includes(id));
});
test("workspace administrators have configuration without platform administration", () => {
  const visible = ids({ ...account, role: "ADMIN" });
  assert(visible.includes("profile"));
  assert(visible.includes("workers"));
  assert(!visible.includes("users"));
  assert(!visible.includes("login-policy"));
});
test("platform bootstrap exposes enrollment without personal account pages", () => {
  const visible = ids({
    ...account,
    role: "ADMIN",
    platformAdministrator: true,
    id: null,
  });
  assert(visible.includes("users"));
  for (const id of ["password", "security", "api-keys", "workspaces"])
    assert(!visible.includes(id));
});
test("signers have content workflows and anonymous users have no navigation", () => {
  assert(ids({ ...account, role: "SIGNER" }).includes("sign"));
  assert.deepEqual(ids(null), []);
});
test("deep links and trailing slashes preserve page selection; unknown paths remain unavailable", () => {
  assert.equal(pageFromPath("/"), "dashboard");
  assert.equal(pageFromPath("/portal/public-trust/"), "public-trust");
  assert.equal(pageFromPath("/portal/unknown"), "unknown");
  assert.notEqual(pageFromPath("/not-a-page"), "dashboard");
});
