/* Shared debounced directory picker. Rows compare by key; a positional index exists only to mint the
 * option id required by aria-activedescendant. */

import { html } from "htm/preact";
import { useEffect, useMemo, useState } from "preact/hooks";
import { apiRequest } from "../lib/api.js";
import { normalizePath } from "../lib/paths.js";
import { useTypeahead } from "./Typeahead.js";

/** Long enough that walking a path with the arrow keys does not read a directory per keystroke. */
const DIRECTORY_COMPLETION_DELAY_MS = 150;

/** Owns suggestions and keyboard handling while the caller owns the field value and validation. */
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
    reset: dismiss,
    onType: setQuery,
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
