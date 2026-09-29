import { test } from "node:test";
import assert from "node:assert/strict";
import { realpathSync } from "node:fs";
import { createRequire } from "node:module";

test("state and Preact signals resolve the same signals-core file", () => {
  const fromState = createRequire(new URL("../../webui/src/state/sessions.js", import.meta.url));
  const fromSignals = createRequire(fromState.resolve("@preact/signals"));

  assert.equal(
    realpathSync(fromState.resolve("@preact/signals-core")),
    realpathSync(fromSignals.resolve("@preact/signals-core")),
  );
});
