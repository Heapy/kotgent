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

export function CommandPalette({ commands, mode = "leader", onModeChange, onClose }) {
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

  // Leader mode must focus the shell because its mnemonics rely on bubbled key events.
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
    // Close synchronously so clipboard commands retain the initiating user gesture.
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
    // Suppress Space on the focused shell, but not on its buttons.
    if (event.code === "Space" && event.target === event.currentTarget) {
      event.preventDefault();
      return;
    }
    // Reserve K/Backspace for search before command lookup or modifier filtering.
    if (event.code === "KeyK" || event.code === "Backspace") {
      event.preventDefault();
      onModeChange("search");
      return;
    }
    // Chords are sequential: release Command-K before the mnemonic, leaving modified letters to the browser.
    if (event.metaKey || event.ctrlKey) return;
    const item = leaderCommands.find(
      (command) => event.code === "Key" + command.chord.toUpperCase(),
    );
    if (!item) return;
    event.preventDefault();
    runLeaderCommand(item);
  };

  return html`
    <${Dialog} id="command-palette" labelledBy="command-palette-title" onClose=${onClose}>
      <div class=${"command-palette-shell " + mode} ref=${shellRef} tabIndex="-1"
           onKeyDown=${leaderKeyDown}>
        <h2 id="command-palette-title" class="visually-hidden">Command palette</h2>
        <div class="command-palette-top">
          ${mode === "leader"
            ? html`
              <button
                id="command-palette-search-mode"
                class="command-palette-search-mode"
                type="button"
                onClick=${() => onModeChange("search")}
              >
                <span>Search commands and sessions</span>
                <kbd>K</kbd>
              </button>`
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
              />`}
          <button id="command-palette-close" class="icon-button command-palette-close" type="button"
                  aria-label="Close" onClick=${onClose}>×</button>
        </div>
        ${mode === "leader"
          ? html`
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
              ${leaderMessage || "Press a letter, K to search, or Esc to close."}
            </p>`
          : html`
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
