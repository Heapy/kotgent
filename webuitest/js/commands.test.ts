import { describe, test } from "node:test";
import assert from "node:assert/strict";

import type { CommandActions } from "../../webui/src/lib/commands.ts";

import { buildCommands } from "../../webui/src/lib/commands.ts";
import { sessionRow } from "./fixtures.ts";

function resumeCommand(state: string) {
  const session = sessionRow({ id: "s0", name: "kt-s0", agent: "claude", cwd: "/tmp", tags: [], state: state });
  const items = buildCommands({ activeSession: session, actions: {} as CommandActions });
  const command = items.find((item) => item.id === "session.resume");
  assert.ok(command);
  return command;
}

describe("the resume command", () => {
  test("is offered for a dead session whose transcript survives", () => {
    assert.equal(resumeCommand("resumable").disabled, null);
    assert.equal(resumeCommand("stopped").disabled, null);
    assert.equal(resumeCommand("crashed").disabled, null);
  });

  test("is refused for a live session", () => {
    assert.equal(resumeCommand("running").disabled, "the selected session is already running");
  });

  test("is refused for a lost session, and says why", () => {
    assert.equal(
      resumeCommand("lost").disabled,
      "the agent deleted this conversation, so there is nothing left to resume",
    );
  });
});
