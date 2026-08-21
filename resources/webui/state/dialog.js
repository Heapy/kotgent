// The one open dialog, described by a plain `{ kind, … }` value. See the header of state/sessions.js for
// why signals-core is imported by relative path rather than by bare specifier.
//
// A dialog descriptor is its own identity: app.js opens one by writing a fresh object, so object
// identity is what distinguishes "the dialog that submitted this request" from "a dialog that replaced
// it while the request was in flight". That comparison used to run against a `dialogRef` mirror that
// only caught up on the next render, which meant a flow entered and completed inside one turn compared
// against a value from before the last open. The signal is the value, so there is nothing to catch up.

import { signal } from "../vendor/signals-core.module.js";

export const dialog = signal(null);

export function openDialog(next) {
  dialog.value = next || null;
}

export function closeDialog() {
  dialog.value = null;
}

// Closes only the instance that submitted. A dismissed dialog has already been replaced or cleared, and
// closing "the dialog" then would shut a form the operator has since opened. The boolean says whether
// this call was the one that closed it.
export function closeDialogFrom(submitted) {
  if (dialog.value !== submitted) return false;
  dialog.value = null;
  return true;
}
