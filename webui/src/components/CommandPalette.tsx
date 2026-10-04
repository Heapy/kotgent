import type { TargetedKeyboardEvent } from "preact";
import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { filterCommands } from "../lib/commands.ts";
import type { Command } from "../lib/commands.ts";
import { Dialog } from "./dialogs.tsx";
import type { DialogHandle } from "./dialogs.tsx";
import { useTypeahead } from "./Typeahead.tsx";

export interface CommandPaletteProps {
  commands: readonly Command[];
  mode?: "leader" | "search";
  onModeChange: (mode: "leader" | "search") => void;
  onClose: () => void;
}

const LISTBOX_ID = "command-palette-results";
const OPTION_ID_PREFIX = "command-palette-option-";

function optionId(index: number) {
  return OPTION_ID_PREFIX + index;
}

export function CommandPalette({ commands, mode = "leader", onModeChange, onClose }: CommandPaletteProps) {
  const [query, setQuery] = useState<string>("");
  const [leaderMessage, setLeaderMessage] = useState<string>("");
  const queryRef = useRef<HTMLInputElement>(null);
  const dialogRef = useRef<DialogHandle>(null);
  const shellRef = useRef<HTMLDivElement>(null);
  const results = useMemo<Command[]>(() => filterCommands(commands, query), [commands, query]);
  const leaderCommands = commands.filter((item): item is Command & { chord: string } => !!item.chord);
  // A disabled row is drawn but never navigated to, so it is not one of the keys.
  const enabledIds = useMemo<string[]>(
    () => results.filter((item) => !item.disabled).map((item) => item.id),
    [results],
  );

  // Leader mode must focus the shell because its mnemonics rely on bubbled key events.
  useEffect(() => {
    setLeaderMessage("");
    if (mode === "search") {
      if (queryRef.current) queryRef.current.focus();
    } else if (shellRef.current) {
      shellRef.current.focus();
    }
  }, [mode]);

  const closeThenRun = (item: Command | null | undefined) => {
    if (!item || item.disabled) return;
    // Close synchronously so clipboard commands retain the initiating user gesture.
    if (dialogRef.current) dialogRef.current.close();
    else onClose();
    item.run();
  };

  const typeahead = useTypeahead({
    keys: enabledIds,
    token: query,
    onCommit: (id) => closeThenRun(results.find((item) => item.id === id)),
  });
  const activeIndex = results.findIndex((item) => item.id === typeahead.activeKey);
  const activeOptionId = activeIndex >= 0 ? optionId(activeIndex) : null;

  const runLeaderCommand = (item: Command) => {
    if (item.disabled) {
      setLeaderMessage(item.title + ": " + item.disabled);
      return;
    }
    closeThenRun(item);
  };

  const leaderKeyDown = (event: TargetedKeyboardEvent<HTMLDivElement>) => {
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

  return (
    <Dialog id="command-palette" labelledBy="command-palette-title" onClose={onClose} handleRef={dialogRef}>
      <div class={"command-palette-shell " + mode} ref={shellRef} tabIndex={-1}
           onKeyDown={leaderKeyDown}>
        <h2 id="command-palette-title" class="visually-hidden">Command palette</h2>
        <div class="command-palette-top">
          {mode === "leader"
            ? (
              <button
                id="command-palette-search-mode"
                class="command-palette-search-mode"
                type="button"
                onClick={() => onModeChange("search")}
              >
                <span>Search commands and sessions</span>
                <kbd>K</kbd>
              </button>)
            : (
              <input
                id="command-palette-query"
                class="command-palette-query"
                type="search"
                role="combobox"
                placeholder="Search commands and sessions"
                autoComplete="off"
                autoFocus
                ref={queryRef}
                aria-autocomplete="list"
                aria-controls={LISTBOX_ID}
                aria-expanded="true"
                aria-activedescendant={activeOptionId ?? undefined}
                value={query}
                onInput={(event) => setQuery(event.currentTarget.value)}
                onKeyDown={typeahead.keyDown}
              />)}
          <button id="command-palette-close" class="icon-button command-palette-close" type="button"
                  aria-label="Close" onClick={onClose}>×</button>
        </div>
        {mode === "leader"
          ? <>
            <div class="command-palette-leader-grid" role="group" aria-label="Command shortcuts">
              {leaderCommands.map((item) => (
                <button
                  key={item.id}
                  class={"command-palette-leader-command" + (item.disabled ? " disabled" : "")}
                  type="button"
                  aria-disabled={item.disabled ? "true" : undefined}
                  onClick={() => runLeaderCommand(item)}
                >
                  <kbd class="command-palette-leader-key">{item.chord}</kbd>
                  <span>{item.title}</span>
                </button>
              ))}
            </div>
            <p class="command-palette-footer" role="status" aria-live="polite">
              {leaderMessage || "Press a letter, K to search, or Esc to close."}
            </p></>
          : (
            <ul id={LISTBOX_ID} class="command-palette-list" role="listbox">
              {results.map((item, index) => (
                <li
                  key={item.id}
                  id={optionId(index)}
                  class={"command-palette-option" +
                    (index === activeIndex ? " active" : "") +
                    (item.disabled ? " disabled" : "")}
                  role="option"
                  aria-selected={index === activeIndex ? "true" : "false"}
                  aria-disabled={item.disabled ? "true" : undefined}
                  ref={typeahead.optionRef(item.id)}
                  onMouseMove={() => { if (!item.disabled) typeahead.activate(item.id); }}
                  onClick={() => closeThenRun(item)}
                >
                  <span class="command-palette-copy">
                    <strong>{item.title}</strong>
                    {item.subtitle && <small>{item.subtitle}</small>}
                    {item.disabled && (
                      <small class="command-palette-disabled-reason">{item.disabled}</small>)}
                  </span>
                  <span class="command-palette-hint">
                    {item.chord
                      ? <kbd class="command-palette-chord"
                             title={"Press Command-K, then " + item.chord.toUpperCase()}>
                          {item.chord}
                        </kbd>
                      : item.hint}
                  </span>
                </li>
              ))}
            </ul>)}
      </div>
    </Dialog>
  );
}
