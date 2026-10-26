import { test } from "node:test";
import assert from "node:assert/strict";
import { parseRoute, projectBoardPath, routePath, SCREEN_TASKS, SCREEN_TASK, SCREEN_SESSIONS } from "../../webui/src/lib/router.ts";

test("project backlog links round-trip without becoming task or session identifiers", () => {
  const id = "33333333-3333-4333-8333-333333333333";
  const path = projectBoardPath(id);
  const url = new URL(path, "http://localhost");
  const route = parseRoute(url.pathname, url.search);
  assert.deepEqual(route, { screen: SCREEN_TASKS, id: null, project: id });
  assert.equal(routePath(route), path);
  assert.deepEqual(parseRoute("/tasks"), { screen: SCREEN_TASKS, id: null });
  assert.deepEqual(parseRoute("/tasks/local%3A1", url.search), { screen: SCREEN_TASK, id: "local:1" });
  assert.deepEqual(parseRoute("/s/session", url.search), { screen: SCREEN_SESSIONS, id: "session" });
});
