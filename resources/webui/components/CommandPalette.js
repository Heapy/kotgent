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

export function CommandPalette({ commands, onClose }) {
  const [query, setQuery] = useState("");
  const [activeIndex, setActiveIndex] = useState(-1);
  const activeOptionRef = useRef(null);
  const results = useMemo(() => filterCommands(commands, query), [commands, query]);
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

  return html`
    <${Dialog} id="command-palette" labelledBy="command-palette-title" onClose=${onClose}>
      <div class="command-palette-shell">
        <h2 id="command-palette-title" class="visually-hidden">Command palette</h2>
        <input
          id="command-palette-query"
          class="command-palette-query"
          type="search"
          role="combobox"
          placeholder="Search commands and sessions"
          autoComplete="off"
          autoFocus
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
        </ul>
      </div>
    <//>
  `;
}
