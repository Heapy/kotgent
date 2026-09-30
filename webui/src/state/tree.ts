// This module is the sole owner of which session trees this device shows expanded; every tree starts collapsed.

import { computed, signal } from "@preact/signals-core";
import type { ReadonlySignal } from "@preact/signals-core";
import { sanitizeExpandedIds, toggleExpandedId } from "../lib/tree.ts";

export const EXPANDED_TREES_KEY = "kotgent.expandedTrees.v1";

function loadExpanded(): readonly string[] {
  try {
    const raw = window.localStorage.getItem(EXPANDED_TREES_KEY);
    return sanitizeExpandedIds(raw ? JSON.parse(raw) : []);
  } catch (_) {
    return [];
  }
}

const expandedIds = signal<readonly string[]>(loadExpanded());

export const expandedTrees: ReadonlySignal<ReadonlySet<string>> = computed(() => new Set(expandedIds.value));

export function toggleTree(id: string) {
  const next = toggleExpandedId(expandedIds.value, id);
  expandedIds.value = next;
  try {
    window.localStorage.setItem(EXPANDED_TREES_KEY, JSON.stringify(next));
  } catch (_) { /* best effort */ }
}
