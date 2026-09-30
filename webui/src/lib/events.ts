import { createRefreshCoordinator } from "./resync.ts";

import type { RefreshTimers } from "./resync.ts";
import type { Session, SessionUpdate } from "./sessions.ts";
import type { Task } from "./tasks.ts";
import type { UsageWindow } from "./usage.ts";
import type { ServerPreferences } from "./prefs.ts";
import type { MutexListing } from "./mutexes.ts";

export type EventsFrame =
  | { type: "sessions_snapshot"; sessions: Session[] }
  | { type: "session_row"; session: Session }
  | ({ type: "session_update" } & SessionUpdate)
  | { type: "tasks_snapshot"; tasks: Task[] }
  | { type: "task_row" | "task_update"; task: Task }
  | { type: "task_removed"; ref: string }
  | { type: "usage_snapshot"; windows: UsageWindow[]; serverNow: number }
  | { type: "usage_update"; window: UsageWindow; serverNow: number }
  | ({ type: "mutexes_snapshot" | "mutex_update" } & MutexListing)
  | ({ type: "preferences_update" } & ServerPreferences);

export interface EventsConnectionOptions extends RefreshTimers {
  url: () => string;
  onFrame: (frame: EventsFrame) => void;
  onReady: (status: { recovered: boolean }) => void;
  onFailure: (error: unknown) => void;
  createSocket?: (url: string) => Pick<WebSocket, "onopen" | "onmessage" | "onclose" | "onerror" | "close">;
}

export function createEventsConnection({
  url, onFrame, onReady, onFailure, createSocket = (url) => new WebSocket(url),
  schedule = setTimeout, cancel = (timer) => clearTimeout(timer ?? undefined),
}: EventsConnectionOptions) {
  let opened = false;
  return createRefreshCoordinator({
    schedule, cancel, onFailure,
    run({ isCurrent, complete, fail }) {
      const socket = createSocket(url());
      const awaiting = new Set<unknown>([
        "sessions_snapshot", "tasks_snapshot", "usage_snapshot", "mutexes_snapshot",
      ]);
      let recovered = false;
      socket.onopen = () => {
        if (!isCurrent()) return;
        recovered = opened;
        opened = true;
      };
      socket.onmessage = (event: MessageEvent<unknown>) => {
        if (!isCurrent()) return;
        let msg: EventsFrame | string | number | boolean | null;
        try { msg = JSON.parse(String(event.data)); } catch (_) { return; }
        if (!msg || typeof msg !== "object") return;
        try {
          onFrame(msg);
          if (awaiting.delete("type" in msg ? msg.type : undefined) && awaiting.size === 0) {
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
