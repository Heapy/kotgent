import { test } from "node:test";
import assert from "node:assert/strict";
import { watchForeground } from "../../webui/src/lib/foreground.ts";

test("a return refreshes once in either event order, and cleanup removes listeners", () => {
  const originalWindow = Object.getOwnPropertyDescriptor(globalThis, "window");
  const originalDocument = Object.getOwnPropertyDescriptor(globalThis, "document");
  const windowEvents = new EventTarget();
  const documentEvents = Object.assign(new EventTarget(), { visibilityState: "visible" });
  Object.defineProperty(globalThis, "window", { configurable: true, value: windowEvents });
  Object.defineProperty(globalThis, "document", { configurable: true, value: documentEvents });
  let refreshes = 0;
  const stop = watchForeground(() => { refreshes++; });
  try {
    windowEvents.dispatchEvent(new Event("focus"));
    assert.equal(refreshes, 0);
    for (const order of [["focus", "visibilitychange"], ["visibilitychange", "focus"]]) {
      windowEvents.dispatchEvent(new Event("blur"));
      documentEvents.visibilityState = "hidden";
      documentEvents.dispatchEvent(new Event("visibilitychange"));
      documentEvents.visibilityState = "visible";
      for (const event of order) {
        (event === "focus" ? windowEvents : documentEvents).dispatchEvent(new Event(event));
      }
    }
    assert.equal(refreshes, 2);
    stop();
    windowEvents.dispatchEvent(new Event("blur"));
    windowEvents.dispatchEvent(new Event("focus"));
    assert.equal(refreshes, 2);
  } finally {
    stop();
    for (const [key, descriptor] of [["window", originalWindow], ["document", originalDocument]] as const) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else Reflect.deleteProperty(globalThis, key);
    }
  }
});
