/*
 * Search view over the one command registry. Focus stays in the combobox while aria-activedescendant
 * points at the selected option, matching the working-directory autocomplete in dialogs.js.
 */

import { html } from "htm/preact";
import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { filterCommands } from "../lib/commands.js";
import { Dialog } from "./dialogs.js";

const LISTBOX_ID = "command-palette-results";
const OPTION_ID_PREFIX = "command-palette-option-";

function optionId(index) {
  return OPTION_ID_PREFIX + index;
}

function availableIndexes(items) {
  const indexes = [];
  items.forEach((item, index) => {
    if (!item.disabled) indexes.push(index);
  });
  return indexes;
}

export function CommandPalette({ commands, mode = "search", onModeChange, onClose }) {
  const [query, setQuery] = useState("");
  const [activeIndex, setActiveIndex] = useState(-1);
  const [leaderMessage, setLeaderMessage] = useState("");
  const activeOptionRef = useRef(null);
  const queryRef = useRef(null);
  const shellRef = useRef(null);
  const results = useMemo(() => filterCommands(commands, query), [commands, query]);
  const leaderCommands = commands.filter((item) => item.chord);
  const enabled = availableIndexes(results);
  const activeOptionId = activeIndex >= 0 ? optionId(activeIndex) : null;

  useEffect(() => {
    setActiveIndex(enabled.length > 0 ? enabled[0] : -1);
  }, [query]);

  useEffect(() => {
    if (activeOptionRef.current) {
      activeOptionRef.current.scrollIntoView({ block: "nearest" });
    }
  }, [activeIndex]);

  // Both modes must own the focus, not only search. `leaderKeyDown` sits on the shell, so it only ever
  // runs for a keystroke that BUBBLES out of that subtree — and leaving leader mode unfocused parks the
  // focus on the <dialog> itself, which is the shell's parent. Every mnemonic was silently dropped that
  // way; the grid's click handlers still worked, which is exactly what made it look like a chord bug.
  useEffect(() => {
    setLeaderMessage("");
    if (mode === "search") {
      if (queryRef.current) queryRef.current.focus();
    } else if (shellRef.current) {
      shellRef.current.focus();
    }
  }, [mode]);

  const closeThenRun = (item) => {
    if (!item || item.disabled) return;
    // Closing the native top-layer element is synchronous and preserves this click/key's user gesture
    // for clipboard commands. The wrapper's close event then unmounts it through ordinary app state.
    const dialog = document.getElementById("command-palette");
    if (dialog && dialog.open) dialog.close();
    else onClose();
    item.run();
  };

  const moveActive = (delta) => {
    if (enabled.length === 0) return;
    const current = enabled.indexOf(activeIndex);
    const next = current < 0
      ? (delta > 0 ? 0 : enabled.length - 1)
      : (current + delta + enabled.length) % enabled.length;
    setActiveIndex(enabled[next]);
  };

  const keyDown = (event) => {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      moveActive(1);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      moveActive(-1);
    } else if (event.key === "Enter" && activeIndex >= 0) {
      event.preventDefault();
      closeThenRun(results[activeIndex]);
    }
  };

  const runLeaderCommand = (item) => {
    if (item.disabled) {
      setLeaderMessage(item.title + ": " + item.disabled);
      return;
    }
    closeThenRun(item);
  };

  const leaderKeyDown = (event) => {
    if (mode !== "leader") return;
    if (event.code === "Space") {
      event.preventDefault(); // never let Space activate the focused search-mode row
      return;
    }
    if (event.code === "Backspace") {
      event.preventDefault();
      onModeChange("search");
      return;
    }
    const item = leaderCommands.find(
      (command) => event.code === "Key" + command.chord.toUpperCase(),
    );
    if (!item) return;
    event.preventDefault();
    runLeaderCommand(item);
  };

  return html`
    <${Dialog} id="command-palette" labelledBy="command-palette-title" onClose=${onClose}>
      ${/* tabIndex -1: programmatically focusable so leader mode can hold the keyboard, but never a
            tab stop of its own. */ ""}
      <div class=${"command-palette-shell " + mode} ref=${shellRef} tabIndex="-1"
           onKeyDown=${leaderKeyDown}>
        <h2 id="command-palette-title" class="visually-hidden">Command palette</h2>
        ${mode === "leader"
          ? html`
            <button
              id="command-palette-search-mode"
              class="command-palette-search-mode"
              type="button"
              onClick=${() => onModeChange("search")}
            >
              <span>Search commands and sessions</span>
              <kbd>⌘K</kbd>
            </button>
            <div class="command-palette-leader-grid" role="group" aria-label="Command shortcuts">
              ${leaderCommands.map((item) => html`
                <button
                  key=${item.id}
                  class=${"command-palette-leader-command" + (item.disabled ? " disabled" : "")}
                  type="button"
                  aria-disabled=${item.disabled ? "true" : null}
                  onClick=${() => runLeaderCommand(item)}
                >
                  <kbd class="command-palette-leader-key">${item.chord}</kbd>
                  <span>${item.title}</span>
                </button>
              `)}
            </div>
            <p class="command-palette-footer" role="status" aria-live="polite">
              ${leaderMessage || "Press a letter, Backspace to search, or Esc to close."}
            </p>`
          : html`
            <input
              id="command-palette-query"
              class="command-palette-query"
              type="search"
              role="combobox"
              placeholder="Search commands and sessions"
              autoComplete="off"
              autoFocus
              ref=${queryRef}
              aria-autocomplete="list"
              aria-controls=${LISTBOX_ID}
              aria-expanded="true"
              aria-activedescendant=${activeOptionId}
              value=${query}
              onInput=${(event) => setQuery(event.target.value)}
              onKeyDown=${keyDown}
            />
            <ul id=${LISTBOX_ID} class="command-palette-list" role="listbox">
              ${results.map((item, index) => html`
                <li
                  key=${item.id}
                  id=${optionId(index)}
                  class=${"command-palette-option" +
                    (index === activeIndex ? " active" : "") +
                    (item.disabled ? " disabled" : "")}
                  role="option"
                  aria-selected=${index === activeIndex ? "true" : "false"}
                  aria-disabled=${item.disabled ? "true" : null}
                  ref=${index === activeIndex ? activeOptionRef : null}
                  onMouseMove=${() => { if (!item.disabled) setActiveIndex(index); }}
                  onClick=${() => closeThenRun(item)}
                >
                  <span class="command-palette-copy">
                    <strong>${item.title}</strong>
                    ${item.subtitle && html`<small>${item.subtitle}</small>`}
                    ${item.disabled && html`
                      <small class="command-palette-disabled-reason">${item.disabled}</small>`}
                  </span>
                  <span class="command-palette-hint">
                    ${item.chord
                      ? html`<kbd class="command-palette-chord"
                                  title=${"Press Command-K, then " + item.chord.toUpperCase()}>
                          ${item.chord}
                        </kbd>`
                      : item.hint}
                  </span>
                </li>
              `)}
            </ul>`}
      </div>
    <//>
  `;
}
