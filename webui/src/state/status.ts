// The single aria-live announcement state.

import { signal } from "@preact/signals-core";
import type { ReadonlySignal } from "@preact/signals-core";

export interface StatusAnnouncement {
  text: string;
  error: boolean;
}

export const EMPTY_STATUS = Object.freeze({ text: "", error: false });

const statusState = signal<StatusAnnouncement>(EMPTY_STATUS);
export const status: ReadonlySignal<StatusAnnouncement> = statusState;

// Tokens prevent a late result from overwriting any newer announcement, including repeated text.
let announcements = 0;

export function say(text: string, error?: unknown) {
  announcements += 1;
  statusState.value = { text: text, error: !!error };
  return announcements;
}

export function announcementHolds(token: number | null | undefined) {
  return announcements === token;
}
