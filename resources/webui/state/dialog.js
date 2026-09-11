// A dialog descriptor is its identity, allowing async submissions to target their originating instance.

import { signal } from "../vendor/signals-core.module.js";

export const dialog = signal(null);

export function openDialog(next) {
  dialog.value = next || null;
}

export function closeDialog() {
  dialog.value = null;
}

// Never let a late submission close a replacement dialog.
export function closeDialogFrom(submitted) {
  if (dialog.value !== submitted) return false;
  dialog.value = null;
  return true;
}
