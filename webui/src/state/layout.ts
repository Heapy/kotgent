// Sole owner of the device-local workspace layouts; lib/workspace.ts owns the rules.

import { signal } from "@preact/signals-core";
import type { ReadonlySignal } from "@preact/signals-core";
import * as model from "../lib/workspace.ts";
import type { ColumnType, SessionWorkspace } from "../lib/workspace.ts";

export const WORKSPACES_KEY = "kotgent.workspaces.v1";

function load(): ReadonlyMap<string, SessionWorkspace> {
  try {
    return model.parseWorkspaces(window.localStorage.getItem(WORKSPACES_KEY));
  } catch (_) {
    return new Map();
  }
}

const workspacesState = signal<ReadonlyMap<string, SessionWorkspace>>(load());
export const workspaces: ReadonlySignal<ReadonlyMap<string, SessionWorkspace>> = workspacesState;

function commit(next: ReadonlyMap<string, SessionWorkspace>) {
  if (next === workspaces.value) return;
  workspacesState.value = next;
  try {
    window.localStorage.setItem(WORKSPACES_KEY, model.serializeWorkspaces(next));
  } catch (_) { /* best effort */ }
}

export function workspaceOf(sessionId: string): SessionWorkspace {
  return workspaces.value.get(sessionId) || model.defaultWorkspace(0);
}

function update(sessionId: string, change: (ws: SessionWorkspace, now: number) => SessionWorkspace) {
  const current = workspaceOf(sessionId);
  const next = change(current, Date.now());
  if (next === current) return;
  const map = new Map(workspaces.value);
  map.set(sessionId, next);
  commit(model.capWorkspaces(map, model.MAX_WORKSPACES));
}

export function addTab(sessionId: string) {
  update(sessionId, (ws, now) => model.addTab(ws, model.AVAILABLE_COLUMN_TYPES, now));
}

export function closeTab(sessionId: string, tabId: string) {
  update(sessionId, (ws, now) => model.closeTab(ws, tabId, now));
}

export function selectTab(sessionId: string, tabId: string) {
  update(sessionId, (ws, now) => model.selectTab(ws, tabId, now));
}

export function addColumn(sessionId: string, tabId: string, after: number) {
  update(sessionId, (ws, now) => model.addColumn(ws, tabId, after, model.AVAILABLE_COLUMN_TYPES, now));
}

export function closeColumn(sessionId: string, tabId: string, index: number) {
  update(sessionId, (ws, now) => model.closeColumn(ws, tabId, index, now));
}

export function setColumnType(sessionId: string, tabId: string, index: number, type: ColumnType) {
  update(sessionId, (ws, now) => model.setColumnType(ws, tabId, index, type, now));
}

export function focusColumn(sessionId: string, tabId: string, index: number) {
  update(sessionId, (ws, now) => model.focusColumn(ws, tabId, index, now));
}

export function moveDivider(
  sessionId: string,
  tabId: string,
  left: number,
  right: number,
  delta: number,
  minFraction: number,
) {
  update(sessionId, (ws, now) => model.moveDivider(ws, tabId, left, right, delta, minFraction, now));
}

// Only a full session snapshot proves a session is gone.
export function pruneWorkspaces(liveIds: ReadonlySet<string>) {
  commit(model.pruneWorkspaces(workspaces.value, liveIds));
}
