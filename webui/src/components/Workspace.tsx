import type { ComponentChildren, JSX } from "preact";
import { useEffect, useLayoutEffect, useRef, useState } from "preact/hooks";
import { AVAILABLE_COLUMN_TYPES, COLUMN_LABELS, activeTabOf, tabLabel, visibleColumns } from "../lib/workspace.ts";
import type { ColumnType, Tab, VisibleColumn } from "../lib/workspace.ts";
import {
  addColumn,
  addTab,
  closeColumn,
  closeTab,
  focusColumn,
  moveDivider,
  selectTab,
  setColumnType,
  workspaceOf,
} from "../state/layout.ts";
import { HeaderPopover } from "./HeaderPopover.tsx";
import { Icon } from "./Icon.tsx";
import { ColumnHeader } from "./ColumnHeader.tsx";
import { TerminalSlot } from "./TerminalPane.tsx";

export const MIN_COLUMN_WIDTH = 320;

const PHONE_QUERY = "(max-width: 720px)";

const KEY_STEP = 0.05;

export interface WorkspaceProps {
  sessionId: string;
  renderPanel: (type: ColumnType) => ComponentChildren;
}

function phoneNow() {
  return typeof window.matchMedia === "function" && window.matchMedia(PHONE_QUERY).matches;
}

function usePhone() {
  const [phone, setPhone] = useState(phoneNow);
  useEffect(() => {
    if (typeof window.matchMedia !== "function") return undefined;
    const query = window.matchMedia(PHONE_QUERY);
    const apply = () => setPhone(query.matches);
    apply();
    query.addEventListener("change", apply);
    return () => query.removeEventListener("change", apply);
  }, []);
  return phone;
}

interface DividerProps {
  left: VisibleColumn;
  right: VisibleColumn;
  // Zero until the row has been measured.
  width: number;
  // Stored fractions are relative to the whole tab, of which the visible columns hold this much.
  shownShare: number;
  onMove: (delta: number, minFraction: number) => void;
}

function Divider({ left, right, width, shownShare, onMove }: DividerProps) {
  const unitsPerPixel = width > 0 ? shownShare / width : 0;
  const moveBy = (pixels: number) => {
    // One spare pixel keeps rounding from pushing a column under the width that hides it.
    if (unitsPerPixel > 0 && pixels !== 0) onMove(pixels * unitsPerPixel, (MIN_COLUMN_WIDTH + 1) * unitsPerPixel);
  };

  const keyDown = (event: JSX.TargetedKeyboardEvent<HTMLDivElement>) => {
    const pixels = event.key === "ArrowLeft" ? -KEY_STEP * width
      : event.key === "ArrowRight" ? KEY_STEP * width
        : event.key === "Home" ? -width
          : event.key === "End" ? width
            : 0;
    if (pixels === 0) return;
    event.preventDefault();
    moveBy(pixels);
  };

  const pointerDown = (event: JSX.TargetedPointerEvent<HTMLDivElement>) => {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    event.preventDefault();
    const handle = event.currentTarget;
    const pointerId = event.pointerId;
    let lastX = event.clientX;
    handle.setPointerCapture(pointerId);
    handle.classList.add("dragging");
    const move = (next: PointerEvent) => {
      if (next.pointerId !== pointerId) return;
      const dx = next.clientX - lastX;
      lastX = next.clientX;
      moveBy(dx);
    };
    const end = (next: PointerEvent) => {
      if (next.pointerId !== pointerId) return;
      handle.classList.remove("dragging");
      handle.removeEventListener("pointermove", move);
      handle.removeEventListener("pointerup", end);
      handle.removeEventListener("pointercancel", end);
      handle.removeEventListener("lostpointercapture", end);
    };
    handle.addEventListener("pointermove", move);
    handle.addEventListener("pointerup", end);
    handle.addEventListener("pointercancel", end);
    handle.addEventListener("lostpointercapture", end);
  };

  return (
    <div
      class="workspace-divider"
      role="separator"
      tabIndex={0}
      aria-orientation="vertical"
      aria-label={"Resize " + COLUMN_LABELS[left.type] + " and " + COLUMN_LABELS[right.type]}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(left.fraction * 100)}
      onKeyDown={keyDown}
      onPointerDown={pointerDown}
    ></div>
  );
}

function candidatesOf(tab: Tab) {
  return tab.columns
    .map((column, index) => ({ index: index, type: column.type }))
    .filter((column) => AVAILABLE_COLUMN_TYPES.includes(column.type));
}

export function Workspace({ sessionId, renderPanel }: WorkspaceProps) {
  const ws = workspaceOf(sessionId);
  const tab = activeTabOf(ws);
  const phone = usePhone();
  const columnsRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);

  useLayoutEffect(() => {
    const row = columnsRef.current;
    if (!row) return undefined;
    const measure = () => setWidth(row.getBoundingClientRect().width);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(row);
    return () => observer.disconnect();
  }, []);

  const shown = visibleColumns(tab, {
    width: width,
    minWidth: MIN_COLUMN_WIDTH,
    narrow: phone,
    available: AVAILABLE_COLUMN_TYPES,
  });
  const candidates = candidatesOf(tab);
  const shownIndexes = new Set(shown.map((column) => column.index));
  const shownShare = shown.reduce((sum, column) => sum + (tab.fractions[column.index] || 0), 0);

  const column = (entry: VisibleColumn) => (
    <section key={tab.id + ":" + entry.type} class="workspace-column" data-type={entry.type}
             aria-label={COLUMN_LABELS[entry.type]} style={{ flex: entry.fraction + " 1 0px" }}
             onFocusIn={() => focusColumn(sessionId, tab.id, entry.index)}>
      <div class="workspace-column-body">
        {entry.type === "terminal" ? <TerminalSlot /> : renderPanel(entry.type)}
      </div>
    </section>
  );

  const body: ComponentChildren[] = [];
  shown.forEach((entry, i) => {
    const previous = shown[i - 1];
    if (previous) {
      body.push(
        <Divider key={tab.id + ":divider:" + previous.index + ":" + entry.index} left={previous} right={entry}
                 width={width} shownShare={shownShare}
                 onMove={(delta, minFraction) =>
                   moveDivider(sessionId, tab.id, previous.index, entry.index, delta, minFraction)} />,
      );
    }
    body.push(column(entry));
  });

  return (
    <div class="workspace">
      {candidates.length > shown.length && shown.length > 0 && (
        <div class="workspace-switcher" role="group" aria-label="Shown column">
          {candidates.map((entry) => (
            <button key={entry.index} type="button" class="button button-small workspace-switch"
                    data-type={entry.type} aria-pressed={shownIndexes.has(entry.index) ? "true" : "false"}
                    onClick={() => focusColumn(sessionId, tab.id, entry.index)}>
              {COLUMN_LABELS[entry.type]}
            </button>
          ))}
        </div>)}
      <div class="workspace-columns" id="workspace-panel" role="tabpanel"
           aria-label={tabLabel(tab)} ref={columnsRef}>
        {shown.length > 0 ? body : (
          <div class="workspace-unavailable">
            <p class="field-hint">This tab only holds panels this version cannot show.</p>
            <button type="button" class="button button-small"
                    onClick={() => addColumn(sessionId, tab.id, tab.columns.length - 1)}>Add a terminal</button>
          </div>
        )}
      </div>
    </div>
  );
}

/** The toolbar and panels share the layout owner; moving controls never replaces a terminal. */
export function WorkspaceToolbar({ sessionId }: { sessionId: string }) {
  const ws = workspaceOf(sessionId);
  const tab = activeTabOf(ws);
  const phone = usePhone();
  const activeTabRef = useRef<HTMLDivElement>(null);
  const candidates = candidatesOf(tab);
  const focused = candidates.find((entry) => entry.index === tab.focus) ?? candidates[0];
  const held = new Set(tab.columns.map((column) => column.type));
  const canAdd = AVAILABLE_COLUMN_TYPES.some((type) => !held.has(type));
  useLayoutEffect(() => {
    activeTabRef.current?.scrollIntoView({ block: "nearest", inline: "nearest" });
  }, [sessionId, tab.id, phone]);

  const tabs = (close: () => void) => <>
    <div class="workspace-tabs" role="tablist" aria-label="Workspace tabs">
      {ws.tabs.map((entry) => {
        const active = entry.id === tab.id;
        const label = tabLabel(entry);
        return <div key={entry.id} class={"workspace-tab" + (active ? " active" : "")}
                    role="presentation" ref={active ? activeTabRef : null}>
          <button type="button" role="tab" class="workspace-tab-label" id={"workspace-tab-" + entry.id}
                  aria-selected={active} aria-controls="workspace-panel"
                  onClick={() => { selectTab(sessionId, entry.id); close(); }}>{label}</button>
          {ws.tabs.length > 1 && <button type="button" class="workspace-tab-close"
            aria-label={"Close tab " + label} title="Close tab"
            onClick={() => closeTab(sessionId, entry.id)}><Icon name="close" /></button>}
        </div>;
      })}
    </div>
    <button type="button" id="workspace-add-tab" class="header-control workspace-add-tab"
            aria-label="New tab" title="New tab" onClick={() => { addTab(sessionId); close(); }}>
      <Icon name="plus" />{phone && "New tab"}
    </button>
  </>;

  return <div class="workspace-toolbar">
    {phone ? <HeaderPopover key={sessionId} id="workspace-tab-picker" label="Workspace tabs"
      className="workspace-tab-picker" panelClass="workspace-tab-menu"
      initialFocus=".workspace-tab.active .workspace-tab-label"
      trigger={<><Icon name={focused?.type === "task" || focused?.type === "plan" ? focused.type : "terminal"} /><span>{tabLabel(tab)}</span><Icon name="caret" /></>}>
      {tabs}
    </HeaderPopover> : <div class="workspace-desktop-tabs">{tabs(() => {})}</div>}
    <HeaderPopover key={sessionId + ":layout"} id="workspace-layout" label="Configure columns"
      trigger={<Icon name="columns" />} panelClass="workspace-layout-menu">
      {(close) => <>
        <p class="header-popover-label">Columns in this tab</p>
        {candidates.map((entry) => <ColumnHeader key={entry.index} type={entry.type}
          types={AVAILABLE_COLUMN_TYPES} canAdd={canAdd} canClose={tab.columns.length > 1}
          onType={(type) => setColumnType(sessionId, tab.id, entry.index, type)}
          onAdd={() => { addColumn(sessionId, tab.id, entry.index); close(); }}
          onClose={() => closeColumn(sessionId, tab.id, entry.index)} />)}
        {candidates.length === 0 && <button class="button" type="button"
          onClick={() => { addColumn(sessionId, tab.id, tab.columns.length - 1); close(); }}>Add a terminal</button>}
      </>}
    </HeaderPopover>
  </div>;
}
