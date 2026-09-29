/* Binds the rules in lib/typeahead.ts to one component instance. Handlers re-derive from the latest
 * choice, navigation scrolls immediately, and pointer or focus activation stays passive. */

import { useRef } from "preact/hooks";
import type { RefCallback, TargetedKeyboardEvent } from "preact";
import { useSignal } from "@preact/signals";
import type { KeyChoice } from "../lib/typeahead.ts";
import {
  COMMIT,
  COMPOSING,
  DISMISS,
  NEXT,
  PREVIOUS,
  chooseKey,
  resolveActiveKey,
  stepActiveKey,
  typeaheadIntent,
} from "../lib/typeahead.ts";

export interface TypeaheadOptions<K> {
  keys: readonly K[] | null | undefined;
  token?: unknown;
  autoFirst?: boolean;
  onCommit?: ((key: K) => void) | null;
  onNavigate?: ((key: K) => void) | null;
  onDismiss?: (() => void) | null;
}

export interface TypeaheadResult<K> {
  activeKey: K | null;
  activate: (key: K) => void;
  keyDown: (event: TargetedKeyboardEvent<HTMLElement>) => void;
  optionRef: (key: K) => RefCallback<HTMLElement>;
}

interface TypeaheadStore<K> {
  nodes: Map<K, HTMLElement>;
  refs: Map<K, RefCallback<HTMLElement>>;
}

/**
 * @param keys navigable option keys in display order; an empty list yields keyboard control.
 * @param token the query the options were produced for. A new token discards the previous choice.
 * @param autoFirst whether an untouched list opens on its first row.
 */
export function useTypeahead<K>({
  keys,
  token = null,
  autoFirst = true,
  onCommit = null,
  onNavigate = null,
  onDismiss = null,
}: TypeaheadOptions<K>): TypeaheadResult<K> {
  const chosen = useSignal<KeyChoice<K> | null>(null);
  const store = useRef<TypeaheadStore<K>>(null);
  if (store.current === null) store.current = { nodes: new Map(), refs: new Map() };
  const currentStore = store.current;

  const list = keys || [];
  const rule = { autoFirst: autoFirst, token: token };
  // The subscribed read repaints choices without mirroring signal state.
  const activeKey = resolveActiveKey(list, chosen.value, rule);

  // Cache callbacks by key, but release closures for options no longer in the list.
  if (currentStore.refs.size > list.length) {
    const live = new Set(list);
    for (const key of Array.from(currentStore.refs.keys())) {
      if (!live.has(key)) currentStore.refs.delete(key);
    }
  }

  // Navigation needs the destination element before the next paint makes it active.
  const optionRef = (key: K) => {
    const cache = currentStore.refs;
    let attach = cache.get(key);
    if (!attach) {
      attach = (element) => {
        if (element) currentStore.nodes.set(key, element);
        else currentStore.nodes.delete(key);
      };
      cache.set(key, attach);
    }
    return attach;
  };

  const reveal = (key: K) => {
    const element = currentStore.nodes.get(key);
    if (element && typeof element.scrollIntoView === "function") {
      element.scrollIntoView({ block: "nearest" });
    }
  };

  const activate = (key: K) => {
    // Avoid notifying repeatedly while the pointer remains on the active row.
    const current = chosen.peek();
    if (current && current.key === key && current.token === token) return;
    chosen.value = chooseKey(key, token);
  };

  const navigate = (delta: 1 | -1) => {
    const from = resolveActiveKey(list, chosen.peek(), rule);
    const next = stepActiveKey(list, from, delta);
    if (next === null) return;
    chosen.value = chooseKey(next, token);
    reveal(next);
    if (onNavigate) onNavigate(next);
  };

  const keyDown = (event: TargetedKeyboardEvent<HTMLElement>) => {
    const intent = typeaheadIntent(event);
    if (intent === null || intent === COMPOSING) return;
    if (intent === DISMISS) {
      // With nothing listed there is nothing to dismiss, and Escape belongs to the dialog.
      if (!onDismiss || list.length === 0) return;
      event.preventDefault();
      onDismiss();
      return;
    }
    if (list.length === 0) return;
    if (intent === NEXT || intent === PREVIOUS) {
      event.preventDefault();
      navigate(intent === NEXT ? 1 : -1);
      return;
    }
    if (intent !== COMMIT) return;
    // Re-derive so navigation and commit compose within the same task.
    const key = resolveActiveKey(list, chosen.peek(), rule);
    if (key === null || !onCommit) return;
    event.preventDefault();
    onCommit(key);
  };

  return { activeKey: activeKey, activate: activate, keyDown: keyDown, optionRef: optionRef };
}
