/* Shared debounced directory picker. Rows compare by key; a positional index exists only to mint the
 * option id required by aria-activedescendant. */

import type { JSX, RefObject } from "preact";
import { useEffect, useMemo, useState } from "preact/hooks";
import type { Dispatch, StateUpdater } from "preact/hooks";
import { apiRequest } from "../lib/api.ts";
import { normalizePath } from "../lib/paths.ts";
import { useTypeahead } from "./Typeahead.tsx";
import type { TypeaheadResult } from "./Typeahead.tsx";

export interface PathSuggestionsOptions {
  id: string;
  basePath: string | null | undefined;
  inputRef?: RefObject<HTMLInputElement> | null;
  onChoose: (candidate: string) => void;
}

export interface PathPicker {
  listId: string;
  suggestions: string[];
  open: boolean;
  activeKey: string | null;
  optionId: (index: number) => string;
  optionRef: TypeaheadResult<string>["optionRef"];
  activate: TypeaheadResult<string>["activate"];
  choose: (candidate: string) => void;
  reset: () => void;
  onType: Dispatch<StateUpdater<string | null>>;
  fieldProps: Pick<JSX.InputHTMLAttributes<HTMLInputElement>,
    "role" | "aria-autocomplete" | "aria-expanded" | "aria-controls" | "aria-activedescendant"
    | "onKeyDown" | "onFocus" | "onBlur">;
}

export interface PathSuggestionsProps {
  picker: PathPicker;
}

/** Long enough that walking a path with the arrow keys does not read a directory per keystroke. */
const DIRECTORY_COMPLETION_DELAY_MS = 150;

/** Owns suggestions and keyboard handling while the caller owns the field value and validation. */
export function usePathSuggestions({ id, basePath, inputRef, onChoose }: PathSuggestionsOptions): PathPicker {
  const [query, setQuery] = useState<string | null>(null);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [focused, setFocused] = useState<boolean>(false);

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
          setSuggestions(response && typeof response === "object" && "paths" in response && Array.isArray(response.paths)
            ? response.paths.filter((candidate: unknown): candidate is string => typeof candidate === "string")
            : []);
        })
        .catch((e: unknown) => {
          if (!controller.signal.aborted && (!e || !(typeof e === "object" && "name" in e && e.name === "AbortError"))) {
            setSuggestions([]);
          }
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

  const choose = (candidate: string) => {
    onChoose(candidate);
    dismiss();
    if (inputRef && inputRef.current) inputRef.current.focus();
  };

  // Nothing is navigable while the field is unfocused, and no row opens active: Enter belongs to the
  // form until an arrow key claims it.
  const options = useMemo<string[]>(() => (focused ? suggestions : []), [focused, suggestions]);
  const typeahead = useTypeahead({
    keys: options,
    token: query,
    autoFirst: false,
    onCommit: choose,
    onDismiss: dismiss,
  });

  const listId = id + "-options";
  const open = focused && suggestions.length > 0;
  const activeIndex = typeahead.activeKey === null ? -1 : suggestions.indexOf(typeahead.activeKey);

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
      "aria-activedescendant": activeIndex >= 0 ? id + "-option-" + activeIndex : undefined,
      onKeyDown: typeahead.keyDown,
      onFocus: () => setFocused(true),
      onBlur: () => setFocused(false),
    },
  };
}

export function PathSuggestions({ picker }: PathSuggestionsProps) {
  if (!picker.open) return null;
  return (
    <ul id={picker.listId} class="path-suggestions" role="listbox">
      {picker.suggestions.map((candidate, index) => (
        <li id={picker.optionId(index)} key={candidate} role="option"
            class={"path-suggestion" + (candidate === picker.activeKey ? " active" : "")}
            aria-selected={candidate === picker.activeKey ? "true" : "false"}
            title={candidate}
            ref={picker.optionRef(candidate)}
            onMouseDown={(event) => event.preventDefault()}
            onMouseEnter={() => picker.activate(candidate)}
            onClick={() => picker.choose(candidate)}>{candidate}</li>))}
    </ul>);
}
