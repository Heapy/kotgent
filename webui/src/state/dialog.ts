// A dialog descriptor is its identity, allowing async submissions to target their originating instance.

import { signal } from "@preact/signals-core";
import type { ReadonlySignal } from "@preact/signals-core";
import type { Session } from "../lib/sessions.ts";
import type { Project } from "../lib/tasks.ts";

export type DialogDescriptor =
  | {
    kind: "new";
    cwd?: string;
    initialMode?: "start" | "import";
    initialAgent?: string;
    taskRef?: string | null;
  }
  | { kind: "upload" | "rename" | "link-task"; session: Session }
  | { kind: "delete-project"; project: Project }
  | { kind: "force-release-mutex"; key: string; holderSessionId: string; holder: string }
  | { kind: "prefs" | "restore-project" | "help" | "phone" };

const dialogState = signal<DialogDescriptor | null>(null);
export const dialog: ReadonlySignal<DialogDescriptor | null> = dialogState;

export function openDialog(next?: DialogDescriptor | null) {
  dialogState.value = next || null;
}

export function closeDialog() {
  dialogState.value = null;
}

// Never let a late submission close a replacement dialog.
export function closeDialogFrom(submitted: DialogDescriptor | null | undefined) {
  if (dialog.value !== submitted) return false;
  dialogState.value = null;
  return true;
}
