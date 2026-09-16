import { createRefreshCoordinator } from "./resync.js";

export function createEventsConnection({
  url, onFrame, onReady, onFailure, createSocket = (url) => new WebSocket(url),
  schedule = setTimeout, cancel = clearTimeout,
}) {
  let opened = false;
  return createRefreshCoordinator({
    schedule, cancel, onFailure,
    run({ isCurrent, complete, fail }) {
      const socket = createSocket(url());
      const awaiting = new Set(["sessions_snapshot", "tasks_snapshot", "usage_snapshot"]);
      let recovered = false;
      socket.onopen = () => {
        if (!isCurrent()) return;
        recovered = opened;
        opened = true;
      };
      socket.onmessage = (event) => {
        if (!isCurrent()) return;
        let msg;
        try { msg = JSON.parse(event.data); } catch (_) { return; }
        if (!msg || typeof msg !== "object") return;
        try {
          onFrame(msg);
          if (awaiting.delete(msg.type) && awaiting.size === 0) {
            complete();
            onReady({ recovered });
          }
        } catch (error) {
          fail(error);
        }
      };
      socket.onclose = () => fail(new Error("Events connection closed"));
      socket.onerror = () => fail(new Error("Events connection failed"));
      return () => {
        socket.onopen = socket.onmessage = socket.onclose = socket.onerror = null;
        try { socket.close(); } catch (_) {}
      };
    },
  });
}
