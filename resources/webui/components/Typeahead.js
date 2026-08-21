/* The one typeahead listbox. Four sites — the command palette, the two directory-path pickers and the
 * session/task link picker — each carried their own copy of "which row is active, what does Enter mean,
 * what does hover mean, when does the list scroll". lib/typeahead.js holds the rules as pure functions;
 * this hook is the only thing that binds them to a component, and it renders no markup, so each site
 * keeps its own listbox and its own option ids. The two path pickers keep one between them, in
 * PathSuggestions.js: theirs were identical down to the class names.
 *
 * Three properties are the point of the hook, and each one was a defect at one of the sites:
 *
 *   * The active row is derived during render and again inside the event handler, from the same rule
 *     and the same inputs. It is never reconciled by an effect, so a query typed and an Enter pressed
 *     in one frame agree about what is selected; and `navigate` re-derives from `chosen.peek()` rather
 *     than from the render closure, so two keystrokes in one task compose instead of overwriting.
 *   * The choice lives in a `useSignal`, created per hook call. A `computed()` in a render body would
 *     be one node shared by every mounted instance — two path pickers can be open at once — which is
 *     the very defect class this primitive exists to remove.
 *   * Scrolling happens at the navigation, on the element the option ref already holds, so there is no
 *     armed flag to latch until an unrelated render fires it under the pointer. Pointer activation does
 *     not scroll at all: the row is already under the pointer, and moving it is what the operator would
 *     see as the list fighting back.
 *
 * Activation by pointer or focus is passive on purpose. It moves the highlight and does nothing else —
 * in particular it clears no error message, because reaching for a failed row's retry is exactly the
 * gesture that used to erase the sentence explaining the failure. Keyboard navigation reaches the
 * caller through `onNavigate`, which is where clearing an error belongs.
 */

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
 * @param keys the navigable option keys, in display order. A caller that must not answer the keyboard
 *   right now — an unfocused path field, a picker whose session changed — passes an empty list, and
 *   every key then falls through to the field and the dialog behind it.
 * @param token the query the options were produced for. A new token discards the previous choice.
 * @param autoFirst whether an untouched list opens on its first row. The path pickers say no, so that
 *   an Enter aimed at the form is not intercepted by a suggestion the operator never selected.
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
  // `.value`, not `.peek()`: this read is what subscribes the component, so a choice made by a
  // keystroke or a pointer repaints the highlight without any state mirrored beside the signal.
  const activeKey = resolveActiveKey(list, chosen.value, rule);

  // A ref callback per option key, cached so re-rendering does not remount every row. Keys the list has
  // dropped are released here: `nodes` prunes itself when an element unmounts, `refs` has no such moment,
  // and the two path pickers key on whatever the operator typed — a long editing session would otherwise
  // hold a closure for every suggestion it ever showed.
  if (store.current.refs.size > list.length) {
    const live = new Set(list);
    for (const key of Array.from(store.current.refs.keys())) {
      if (!live.has(key)) store.current.refs.delete(key);
    }
  }

  // The element for every option, not only the active one: navigation scrolls the row it is moving to,
  // which does not become the active one until the next paint.
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
    // A fresh `chooseKey` object every time, and signal writes compare by identity, so an unconditional
    // write notifies on every call. CommandPalette binds this to `onMouseMove`, which fires at pointer
    // sample rate: re-rendering the whole option list while the pointer merely travels across the row it
    // already activated is work nobody asked for.
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
    // Re-derived here rather than read from the render closure: an arrow key in this same task has
    // already moved the choice, and the frame that would have shown it has not run yet.
    const key = resolveActiveKey(list, chosen.peek(), rule);
    if (key === null || !onCommit) return;
    event.preventDefault();
    onCommit(key);
  };

  return { activeKey: activeKey, activate: activate, keyDown: keyDown, optionRef: optionRef };
}
