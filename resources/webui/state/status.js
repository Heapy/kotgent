// The single aria-live announcement state.

import { signal } from "../vendor/signals-core.module.js";

export const EMPTY_STATUS = Object.freeze({ text: "", error: false });

export const status = signal(EMPTY_STATUS);

// Tokens prevent a late result from overwriting any newer announcement, including repeated text.
let announcements = 0;

export function say(text, error) {
  announcements += 1;
  status.value = { text: text, error: !!error };
  return announcements;
}

export function announcementHolds(token) {
  return announcements === token;
}
