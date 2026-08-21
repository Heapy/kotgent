/* The one directory-path picker. Two dialogs offer one — the New Project directory and the New Session
 * working directory — and they carried the same thing twice: the same debounced POST to
 * /directories/complete with the same abort handling, the same dismiss/choose pair, the same
 * `useTypeahead` call, and the same listbox markup down to the class names.
 *
 * The rows compare by key. `lib/typeahead.js` exists to end the index-plus-`-1`-sentinel representation,
 * and both copies had reintroduced it (`suggestions.indexOf(activeKey)` and then `index === active` in
 * the markup) while the link picker, the third site, already compared refs directly. An index survives
 * here for exactly one reason: `aria-activedescendant` names an element by id, and the option ids are
 * positional, so the index is minted where the id is and nowhere else.
 *
 * What stays with the caller is what actually differs: the text value and its validation, `required`,
 * `disabled`, and the placeholder. The hook owns the suggestion list and answers the keyboard for it.
 */

import { html } from "htm/preact";
import { useEffect, useMemo, useState } from "preact/hooks";
import { apiRequest } from "../lib/api.js";
import { normalizePath } from "../lib/paths.js";
import { useTypeahead } from "./Typeahead.js";

/** Long enough that walking a path with the arrow keys does not read a directory per keystroke. */
const DIRECTORY_COMPLETION_DELAY_MS = 150;

/**
 * @param id the field's own element id. The listbox is `<id>-options` and its rows `<id>-option-<n>`,
 *   which is what `aria-controls` and `aria-activedescendant` are built from.
 * @param basePath the Preferences base a relative entry resolves against; a caller that froze it passes
 *   the frozen value and a caller reading a live prop passes that. Normalizing is idempotent.
 * @param inputRef the combobox input, so committing a row returns focus to it.
 * @param onChoose receives the committed path. The caller owns the field's value.
 */
export function usePathSuggestions({ id, basePath, inputRef, onChoose }) {
  const [query, setQuery] = useState(null);
  const [suggestions, setSuggestions] = useState([]);
  const [focused, setFocused] = useState(false);

  useEffect(() => {
    if (query === null) return undefined;
    const typed = query.trim();
    const base = normalizePath(basePath);
    setSuggestions([]);
    if (!typed || (typed.charAt(0) !== "/" && base.charAt(0) !== "/")) return undefined;

    const controller = new AbortController();
    const timer = setTimeout(() => {
      apiRequest("/directories/complete", {
        method: "POST",
        signal: controller.signal,
        body: JSON.stringify({ basePath: base || null, input: typed }),
      })
        .then((response) => {
          if (controller.signal.aborted) return;
          setSuggestions(response && Array.isArray(response.paths)
            ? response.paths.filter((candidate) => typeof candidate === "string")
            : []);
        })
        .catch((e) => {
          if (!controller.signal.aborted && (!e || e.name !== "AbortError")) setSuggestions([]);
        });
    }, DIRECTORY_COMPLETION_DELAY_MS);

    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [query, basePath]);

  const dismiss = () => {
    setQuery(null);
    setSuggestions([]);
  };

  const choose = (candidate) => {
    onChoose(candidate);
    dismiss();
    if (inputRef && inputRef.current) inputRef.current.focus();
  };

  // Nothing is navigable while the field is unfocused, and no row opens active: Enter belongs to the
  // form until an arrow key claims it.
  const options = useMemo(() => (focused ? suggestions : []), [focused, suggestions]);
  const typeahead = useTypeahead({
    keys: options,
    token: query,
    autoFirst: false,
    onCommit: choose,
    onDismiss: dismiss,
  });

  const listId = id + "-options";
  const open = focused && suggestions.length > 0;
  const activeIndex = suggestions.indexOf(typeahead.activeKey);

  return {
    listId: listId,
    suggestions: suggestions,
    open: open,
    activeKey: typeahead.activeKey,
    optionId: (index) => id + "-option-" + index,
    optionRef: typeahead.optionRef,
    activate: typeahead.activate,
    choose: choose,
    // What `switchMode` needs: the field's value changed underneath a list produced for the old one.
    reset: dismiss,
    // The caller owns the field's value, so it forwards what was typed rather than the hook reading it.
    onType: setQuery,
    // Every attribute the combobox contributes to the listbox, so no caller can wire half of them.
    fieldProps: {
      role: "combobox",
      "aria-autocomplete": "list",
      "aria-expanded": open ? "true" : "false",
      "aria-controls": listId,
      "aria-activedescendant": activeIndex >= 0 ? id + "-option-" + activeIndex : null,
      onKeyDown: typeahead.keyDown,
      onFocus: () => setFocused(true),
      onBlur: () => setFocused(false),
    },
  };
}

/** The listbox for a `usePathSuggestions` picker. Renders nothing until the field has rows to offer. */
export function PathSuggestions({ picker }) {
  if (!picker.open) return null;
  return html`
    <ul id=${picker.listId} class="path-suggestions" role="listbox">
      ${picker.suggestions.map((candidate, index) => html`
        <li id=${picker.optionId(index)} key=${candidate} role="option"
            class=${"path-suggestion" + (candidate === picker.activeKey ? " active" : "")}
            aria-selected=${candidate === picker.activeKey ? "true" : "false"}
            title=${candidate}
            ref=${picker.optionRef(candidate)}
            onMouseDown=${(event) => event.preventDefault()}
            onMouseEnter=${() => picker.activate(candidate)}
            onClick=${() => picker.choose(candidate)}>${candidate}</li>`)}
    </ul>`;
}
