/* Binds the rules in lib/typeahead.js to one component instance. Handlers re-derive from the latest
 * choice, navigation scrolls immediately, and pointer or focus activation stays passive. */

import { useRef } from "preact/hooks";
import { useSignal } from "@preact/signals";
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
} from "../lib/typeahead.js";

/**
 * @param keys navigable option keys in display order; an empty list yields keyboard control.
 * @param token the query the options were produced for. A new token discards the previous choice.
 * @param autoFirst whether an untouched list opens on its first row.
 */
export function useTypeahead({
  keys,
  token = null,
  autoFirst = true,
  onCommit = null,
  onNavigate = null,
  onDismiss = null,
}) {
  const chosen = useSignal(null);
  const store = useRef(null);
  if (store.current === null) store.current = { nodes: new Map(), refs: new Map() };

  const list = keys || [];
  const rule = { autoFirst: autoFirst, token: token };
  // The subscribed read repaints choices without mirroring signal state.
  const activeKey = resolveActiveKey(list, chosen.value, rule);

  // Cache callbacks by key, but release closures for options no longer in the list.
  if (store.current.refs.size > list.length) {
    const live = new Set(list);
    for (const key of Array.from(store.current.refs.keys())) {
      if (!live.has(key)) store.current.refs.delete(key);
    }
  }

  // Navigation needs the destination element before the next paint makes it active.
  const optionRef = (key) => {
    const cache = store.current.refs;
    let attach = cache.get(key);
    if (!attach) {
      attach = (element) => {
        if (element) store.current.nodes.set(key, element);
        else store.current.nodes.delete(key);
      };
      cache.set(key, attach);
    }
    return attach;
  };

  const reveal = (key) => {
    const element = store.current.nodes.get(key);
    if (element && typeof element.scrollIntoView === "function") {
      element.scrollIntoView({ block: "nearest" });
    }
  };

  const activate = (key) => {
    // Avoid notifying repeatedly while the pointer remains on the active row.
    const current = chosen.peek();
    if (current && current.key === key && current.token === token) return;
    chosen.value = chooseKey(key, token);
  };

  const navigate = (delta) => {
    const from = resolveActiveKey(list, chosen.peek(), rule);
    const next = stepActiveKey(list, from, delta);
    if (next === null) return;
    chosen.value = chooseKey(next, token);
    reveal(next);
    if (onNavigate) onNavigate(next);
  };

  const keyDown = (event) => {
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
