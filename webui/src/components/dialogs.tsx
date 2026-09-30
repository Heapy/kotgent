/* Dialog is the sole owner of the native imperative API. Light-dismiss gestures use the backdrop or
 * touch grabber and fail toward preserving drafts. Copy interpolates literal `<` characters. */

import type { ComponentChildren, TargetedEvent } from "preact";
import { useCallback, useEffect, useMemo, useRef, useState } from "preact/hooks";
import { useSignal } from "@preact/signals";
import { AGENT_CHOICES, FIRST_AVAILABLE_AGENT } from "../lib/agents.ts";
import { basename, normalizePath, segmentsUnder } from "../lib/paths.ts";
import { MAX_GROUPING_LEVEL, TERMINAL_FONT_SIZES, sanitizePrefs } from "../lib/prefs.ts";
import { FAILED, IDLE_STATUS, READY, combineReadiness } from "../lib/readiness.ts";
import {
  displayName,
  normalizeTaskQuery,
  sessionTaskLinkDisabledReason,
  taskMatchesQuery,
} from "../lib/sessions.ts";
import { TERMINAL_UNICODE_MODES, terminalUnicodeMode } from "../lib/unicode.ts";
import type { ApiResponse } from "../lib/api.ts";
import { AUTH_TICKET_PATH, apiRequest, errorMessage } from "../lib/api.ts";
import {
  compareTasksByBoardOrder,
  fetchProjects,
  isOpenTaskState,
  taskStateLabel,
  taskStateRank,
} from "../lib/tasks.ts";
import { qrSvg } from "../lib/qr.ts";
import { PathSuggestions, usePathSuggestions } from "./PathSuggestions.tsx";
import { useTypeahead } from "./Typeahead.tsx";
import type { TypeaheadResult } from "./Typeahead.tsx";
import type { Preferences } from "../lib/prefs.ts";
import type { ReadinessStatus } from "../lib/readiness.ts";
import type { Session } from "../lib/sessions.ts";
import type { Project, Task } from "../lib/tasks.ts";

export interface DialogProps {
  id: string;
  labelledBy: string;
  lightDismiss?: boolean;
  onClose: () => void;
  children?: ComponentChildren;
}

interface OutsidePress {
  pointerId: number;
  released: boolean;
}

interface DialogDrag {
  pointerId: number;
  startX: number;
  startY: number;
  lastY: number;
  lastAt: number;
  velocity: number;
  travel: number;
  dragging: boolean;
}

const SWIPE_SLOP_PX = 8;
const SWIPE_DISMISS_PX = 96;
const SWIPE_FLICK_PX = 32;
const SWIPE_FLICK_VELOCITY = 0.5;
/** Ignore stale velocity samples so a dwell after a short pull cannot dismiss a draft. */
const SWIPE_FLICK_HANDOFF_MS = 90;

/** Inline animation must consult reduced-motion here because it outranks stylesheet rules. */
function prefersReducedMotion() {
  return typeof window !== "undefined" && typeof window.matchMedia === "function" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

export function Dialog({ id, labelledBy, lightDismiss = true, onClose, children }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  // Only one primary pointer's completed outside down-up-click may authorize dismissal.
  const outsidePress = useRef<OutsidePress | null>(null);
  const dragRef = useRef<DialogDrag | null>(null);

  useEffect(() => {
    const el = ref.current;
    if (el && !el.open) el.showModal();
  }, []);

  useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    const handler = () => onClose();
    el.addEventListener("close", handler);
    return () => el.removeEventListener("close", handler);
  }, [onClose]);

  // Target alone is insufficient: panel drags and native select popups can end on the dialog.
  const outside = (event: TargetedEvent<HTMLDialogElement, MouseEvent>) => {
    const el = ref.current;
    if (!el || event.target !== el) return false;
    const rect = el.getBoundingClientRect();
    return event.clientX < rect.left || event.clientX > rect.right ||
      event.clientY < rect.top || event.clientY > rect.bottom;
  };

  const springBack = (el: HTMLDialogElement, pointerId: number) => {
    if (el.hasPointerCapture(pointerId)) el.releasePointerCapture(pointerId);
    el.style.transition = prefersReducedMotion() ? "none" : "transform 160ms ease-out";
    el.style.transform = "";
  };

  const pointerDown = (event: TargetedEvent<HTMLDialogElement, PointerEvent>) => {
    // Never arm dismissal while busy; work may finish between press and click.
    if (!lightDismiss) return;
    const isOutside = outside(event);
    if (event.isPrimary && event.button === 0) {
      outsidePress.current = isOutside ? { pointerId: event.pointerId, released: false } : null;
    }
    if (isOutside || event.pointerType !== "touch") return;
    // A second contact must not replace the swipe owner.
    if (dragRef.current) return;
    // Only the grabber reserves touch; the head and body must remain scrollable/interactable.
    const from = event.target instanceof Element ? event.target : null;
    if (!from || !from.closest(".dialog-grabber")) return;
    dragRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      lastY: event.clientY,
      lastAt: event.timeStamp,
      velocity: 0,
      travel: 0,
      dragging: false,
    };
  };

  const pointerMove = (event: TargetedEvent<HTMLDialogElement, PointerEvent>) => {
    const drag = dragRef.current;
    const el = ref.current;
    if (!drag || !el || event.pointerId !== drag.pointerId) return;
    // Work may become busy after gesture claim; restore the panel immediately.
    if (!lightDismiss) {
      dragRef.current = null;
      if (drag.dragging) springBack(el, event.pointerId);
      return;
    }
    const travel = event.clientY - drag.startY;
    if (!drag.dragging) {
      // Capture only after a dominant downward movement, preserving taps and horizontal sweeps.
      if (travel < SWIPE_SLOP_PX || travel <= Math.abs(event.clientX - drag.startX)) {
        // Keep dwell time out of the first claimed velocity sample.
        drag.lastY = event.clientY;
        drag.lastAt = event.timeStamp;
        return;
      }
      drag.dragging = true;
      el.setPointerCapture(event.pointerId);
      el.style.transition = "none";
    }
    const elapsed = event.timeStamp - drag.lastAt;
    drag.velocity = elapsed > 0 && elapsed <= SWIPE_FLICK_HANDOFF_MS
      ? (event.clientY - drag.lastY) / elapsed
      : 0;
    drag.lastY = event.clientY;
    drag.lastAt = event.timeStamp;
    drag.travel = Math.max(0, travel);
    el.style.transform = "translateY(" + drag.travel + "px)";
  };

  const pointerUp = (event: TargetedEvent<HTMLDialogElement, PointerEvent>) => {
    // Only the arming pointer can complete or withdraw the backdrop press.
    const press = outsidePress.current;
    if (press && press.pointerId === event.pointerId) {
      if (outside(event)) press.released = true;
      else outsidePress.current = null;
    }
    const drag = dragRef.current;
    const el = ref.current;
    if (!drag || !el || event.pointerId !== drag.pointerId) return;
    dragRef.current = null;
    if (!drag.dragging) return;
    // Re-check busy state at the point dismissal is decided.
    if (!lightDismiss) {
      springBack(el, event.pointerId);
      return;
    }
    // Fold pointerup into the final sample; browsers need not emit a preceding move.
    const travel = Math.max(0, event.clientY - drag.startY);
    const elapsed = event.timeStamp - drag.lastAt;
    const velocity = event.clientY === drag.lastY
      ? (elapsed > SWIPE_FLICK_HANDOFF_MS ? 0 : drag.velocity)
      : (elapsed > 0 && elapsed <= SWIPE_FLICK_HANDOFF_MS
        ? (event.clientY - drag.lastY) / elapsed
        : 0);
    const flicked = travel > SWIPE_FLICK_PX && velocity > SWIPE_FLICK_VELOCITY;
    if (travel > SWIPE_DISMISS_PX || flicked) {
      if (el.hasPointerCapture(event.pointerId)) el.releasePointerCapture(event.pointerId);
      el.close();
      return;
    }
    springBack(el, event.pointerId);
  };

  // Platform cancellation restores rather than dismisses the draft.
  const pointerCancel = (event: TargetedEvent<HTMLDialogElement, PointerEvent>) => {
    const press = outsidePress.current;
    if (press && press.pointerId === event.pointerId) outsidePress.current = null;
    const drag = dragRef.current;
    const el = ref.current;
    if (!drag || !el || event.pointerId !== drag.pointerId) return;
    dragRef.current = null;
    if (!drag.dragging) return;
    springBack(el, event.pointerId);
  };

  const click = (event: TargetedEvent<HTMLDialogElement, MouseEvent> & { pointerId?: number }) => {
    const press = outsidePress.current;
    outsidePress.current = null;
    if (!press || !press.released || !lightDismiss || !outside(event)) return;
    // When click exposes pointer identity, require it to match the press.
    if (typeof event.pointerId === "number" && event.pointerId !== press.pointerId) return;
    if (ref.current) ref.current.close();
  };

  return (
    <dialog id={id} ref={ref} aria-labelledby={labelledBy} onPointerDown={pointerDown}
      onPointerMove={pointerMove} onPointerUp={pointerUp} onPointerCancel={pointerCancel}
      onClick={click}><div class="dialog-grabber" aria-hidden="true" />{children}</dialog>
  );
}

export interface StartSessionRequest {
  agent: string;
  cwd: string;
  name: string | null;
  tags: string[];
  taskRef?: string;
}

export interface ImportSessionRequest {
  agent: string;
  providerSessionId: string;
  cwd: string | null;
  name: string | null;
  tags: string[];
}

export interface NewSessionDialogProps {
  initialCwd?: string | undefined;
  initialMode?: "start" | "import" | undefined;
  initialAgent?: string | undefined;
  initialTaskRef?: string | null | undefined;
  basePath: string;
  onStart: (body: StartSessionRequest) => Promise<unknown>;
  onImport: (body: ImportSessionRequest, registerOnly: boolean) => Promise<unknown>;
  onClose: () => void;
}

/* Start and import share one form. taskRef belongs only to start requests; import discovers cwd from
 * the provider transcript unless the operator explicitly overrides it. */
export function NewSessionDialog({
  initialCwd, initialMode = "start", initialAgent = "", initialTaskRef = null,
  basePath, onStart, onImport, onClose,
}: NewSessionDialogProps) {
  const [mode, setMode] = useState(initialMode);
  const [agent, setAgent] = useState(initialAgent);
  const [cwd, setCwd] = useState(initialMode === "import" ? "" : (initialCwd || ""));
  const [name, setName] = useState("");
  const [tags, setTags] = useState("");
  const [sessionId, setSessionId] = useState("");
  const [registerOnly, setRegisterOnly] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const cwdRef = useRef<HTMLInputElement>(null);
  const agentRef = useRef<HTMLInputElement>(null);
  const sessionIdRef = useRef<HTMLInputElement>(null);
  const cwdPicker = usePathSuggestions({
    id: "session-cwd",
    basePath: basePath,
    inputRef: cwdRef,
    onChoose: setCwd,
  });
  const taskRef = typeof initialTaskRef === "string" && initialTaskRef.trim().length > 0
    ? initialTaskRef.trim()
    : null;

  // Focus the first unanswered field; only the free-terminal command preselects an agent.
  useEffect(() => {
    const target = agent ? cwdRef.current : agentRef.current;
    if (target) target.focus();
  }, []);

  const cwdInput = (event: TargetedEvent<HTMLInputElement, InputEvent>) => {
    const value = event.currentTarget.value;
    setCwd(value);
    cwdPicker.onType(value);
  };

  const chooseAgent = (event: TargetedEvent<HTMLInputElement>) => {
    setAgent(event.currentTarget.value);
    setError(null);
  };

  const switchMode = (next: "start" | "import") => {
    setMode(next);
    setError(null);
    if (next === "import" && AGENT_CHOICES.some(
      (choice) => choice.value === agent && choice.importable === false,
    )) setAgent("");
    // Never carry a start-mode cwd into import as an accidental transcript-discovery override.
    setCwd(next === "import" ? "" : (initialCwd || ""));
    cwdPicker.reset();
  };

  const submit = async (event: TargetedEvent<HTMLFormElement, SubmitEvent>) => {
    event.preventDefault();
    if (!agent) {
      // Report through the visible alert; native validation targets an invisible radio.
      setError(mode === "import"
        ? "Pick the agent that owns the session you are importing."
        : "Pick an agent to start a session.");
      if (agentRef.current) agentRef.current.focus();
      return;
    }
    if (mode === "import" && !sessionId.trim()) {
      // Native required accepts whitespace-only input.
      setError("Enter the provider session id to import.");
      if (sessionIdRef.current) sessionIdRef.current.focus();
      return;
    }
    const tagList = tags
      .split(",")
      .map((tag) => tag.trim())
      .filter((tag, index, all) => tag.length > 0 && all.indexOf(tag) === index);

    setBusy(true);
    setError(null);
    try {
      if (mode === "import") {
        await onImport({
          agent: agent,
          providerSessionId: sessionId.trim(),
          cwd: cwd.trim() || null,
          name: name.trim() || null,
          tags: tagList,
        }, registerOnly);
      } else {
        const body: StartSessionRequest = { agent: agent, cwd: cwd.trim(), name: name.trim() || null, tags: tagList };
        if (taskRef) body.taskRef = taskRef;
        await onStart(body);
      }
    } catch (e) {
      // Import errors are already user-facing; start failures retain their contextual prefix.
      setError(mode === "import" ? errorMessage(e) : "Could not start session: " + errorMessage(e));
      setBusy(false);
    }
  };

  return (
    <Dialog id="new-session-dialog" labelledBy="new-session-title" lightDismiss={!busy}
      onClose={onClose}>
      <form id="new-session-form" onSubmit={submit}>
        <div class="dialog-head">
          <div>
            <h2 id="new-session-title">New session</h2>
            <p>
              {mode === "import"
              ? "Register a conversation started outside kotgent and continue it here."
              : "Start a coding agent in a tmux-backed workspace."}
            </p>
          </div>
          <button id="new-session-close" class="icon-button" type="button" aria-label="Close"
            onClick={onClose}>×</button>
        </div>
        <div class="dialog-mode" role="group" aria-label="New session mode">
          <button id="new-session-mode-start" type="button" disabled={busy}
            aria-pressed={mode === "start" ? "true" : "false"} onClick={() => switchMode("start")}>Start new</button>
          <button id="new-session-mode-import" type="button" disabled={busy}
            aria-pressed={mode === "import" ? "true" : "false"} onClick={() => switchMode("import")}>Import existing</button>
        </div>
        <fieldset class="field agent-picker"
          aria-describedby={agent ? undefined : "new-session-agent-hint"}>
          <legend>Agent</legend>
          <div class="agent-options">
            {AGENT_CHOICES
              .filter((choice) => mode !== "import" || choice.importable !== false)
              .map((choice) => (
              <label key={choice.value}
                class={"agent-option" + (choice.available ? "" : " agent-option-unavailable")}>
                <input id={"session-agent-" + choice.value} type="radio" name="session-agent"
                  value={choice.value} disabled={!choice.available}
                  aria-required={choice.available ? "true" : undefined}
                  ref={choice.value === FIRST_AVAILABLE_AGENT ? agentRef : null}
                  checked={agent === choice.value} onChange={chooseAgent} />
                <span class="agent-option-content">
                  <span class={"agent-icon agent-icon-" + choice.value} aria-hidden="true"><svg viewBox={choice.viewBox} focusable="false"><path d={choice.icon} /></svg></span>
                  <span class="agent-option-name">
                    {choice.name}
                    {!choice.available && (
                      <small>Soon</small>
                    )}
                  </span>
                </span>
              </label>
            ))}
          </div>
          {!agent && (
            <p id="new-session-agent-hint" class="field-hint">{mode === "import" ? "Pick the agent that owns the session." : "Pick one to start a session."}</p>
          )}
        </fieldset>
        {mode === "import" && (
          <label class="field">
            <span>Provider session id</span>
            <input id="session-provider-id" type="text" required spellcheck={false}
              autocomplete="off" placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
              ref={sessionIdRef} value={sessionId} onInput={(e) => setSessionId(e.currentTarget.value)} />
            <small class="field-hint">
              {"claude: the "}
              {"<id>"}
              {`.jsonl transcript name under ~/.claude/projects — codex: the id in
              the `}
              {"rollout-<ts>-<id>"}
              {`.jsonl file name — junie: the directory name under
              ~/.junie/sessions, e.g. `}
              {"session-<date>-<time>-<suffix>"}
              {`. Shell sessions have no
              provider id and cannot be imported.`}
            </small>
          </label>
        )}
        <div class="field">
          <label for="session-cwd">
            Working directory
            {mode === "import" ? [
              " ",
              <small>optional</small>
            ] : ""}
          </label>
          <div class="path-autocomplete">
            <input id="session-cwd" type="text" required={mode === "start"} spellcheck={false}
              autocomplete="off" {...cwdPicker.fieldProps}
              placeholder={mode === "import"
                     ? "found from the transcript when omitted"
                     : "/path/to/project"}
              ref={cwdRef} value={cwd} onInput={cwdInput} />
            <PathSuggestions picker={cwdPicker} />
          </div>
        </div>
        <label class="field">
          <span>{"Name "}<small>optional</small></span>
          <input id="session-name" type="text" maxlength={200} placeholder="Feature or task"
            value={name} onInput={(e) => setName(e.currentTarget.value)} />
        </label>
        <label class="field">
          <span>{"Tags "}<small>optional, comma-separated</small></span>
          <input id="session-tags" type="text" placeholder="backend, urgent" value={tags}
            onInput={(e) => setTags(e.currentTarget.value)} />
        </label>
        {mode === "start" && taskRef && (
          <div class="field">
            <span>Task</span>
            <p id="new-session-task-ref" class="field-hint">
              {"This session will be linked to "}
              {taskRef}
              {` — the link is written by the same request that
              starts it, so a launch that fails leaves no link behind.`}
            </p>
          </div>
        )}
        {mode === "import" && (
          <label class="field checkbox-field">
            <input id="session-register-only" type="checkbox" checked={registerOnly}
              onChange={(e) => setRegisterOnly(e.currentTarget.checked)} />
            <span>
              Register only
              <small class="field-hint">
                {`Skip the automatic resume and leave the session resumable — e.g. while the conversation
                is still open in the terminal it was started in.`}
              </small>
            </span>
          </label>
        )}
        {error && (
          <p id="new-session-error" class="form-error" role="alert">{error}</p>
        )}
        <div class="dialog-actions">
          <button id="new-session-cancel" class="button button-quiet" type="button" onClick={onClose}>{busy ? "Close" : "Cancel"}</button>
          <button id="new-session-submit" class="button button-primary" type="submit" disabled={busy}>
            {mode === "import"
              ? (busy ? "Importing…" : "Import session")
              : (busy ? "Starting…" : "Start session")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

export interface RenameSessionDialogProps {
  session: Session;
  onRename: (sessionId: string, name: string) => Promise<unknown>;
  onClose: () => void;
}

export function RenameSessionDialog({ session, onRename, onClose }: RenameSessionDialogProps) {
  const [name, setName] = useState(session.name || "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const field = inputRef.current;
    if (field) {
      field.focus();
      field.select();
    }
  }, []);

  const submit = async (event: TargetedEvent<HTMLFormElement, SubmitEvent>) => {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await onRename(session.id, name);
    } catch (e) {
      setError("Could not rename the session: " + errorMessage(e));
      setBusy(false);
    }
  };

  return (
    <Dialog id="rename-session-dialog" labelledBy="rename-session-title" lightDismiss={!busy}
      onClose={onClose}>
      <form id="rename-session-form" onSubmit={submit}>
        <div class="dialog-head">
          <div>
            <h2 id="rename-session-title">Rename session</h2>
            <p>Changes the label only. The conversation, its terminal and its history are untouched.</p>
          </div>
          <button id="rename-session-close" class="icon-button" type="button" aria-label="Close"
            onClick={onClose}>×</button>
        </div>
        <label class="field">
          <span>Name</span>
          <input id="rename-session-name" type="text" maxlength={200} spellcheck={false}
            autocomplete="off" autocapitalize="off" autoCorrect="off"
            placeholder="leave empty for the automatic name" ref={inputRef} value={name}
            onInput={(e) => setName(e.currentTarget.value)} />
        </label>
        {error && (
          <p id="rename-session-error" class="form-error" role="alert">{error}</p>
        )}
        <div class="dialog-actions">
          <button id="rename-session-cancel" class="button button-quiet" type="button"
            onClick={onClose}>{busy ? "Close" : "Cancel"}</button>
          <button id="rename-session-submit" class="button button-primary" type="submit"
            disabled={busy}>{busy ? "Saving…" : "Save name"}</button>
        </div>
      </form>
    </Dialog>
  );
}

export interface UploadFilesDialogProps {
  session: Session;
  onClose: () => void;
}

export function UploadFilesDialog({ session, onClose }: UploadFilesDialogProps) {
  const [files, setFiles] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState("");
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const requestRef = useRef<AbortController | null>(null);

  useEffect(() => {
    if (inputRef.current) inputRef.current.focus();
    return () => {
      if (requestRef.current) requestRef.current.abort();
    };
  }, []);

  const selectedFiles = (event: TargetedEvent<HTMLInputElement>) => {
    setFiles(Array.from(event.currentTarget.files || []));
    setProgress("");
    setResult(null);
    setError(null);
  };

  const submit = async (event: TargetedEvent<HTMLFormElement, SubmitEvent>) => {
    event.preventDefault();
    if (files.length === 0 || busy) return;

    const controller = new AbortController();
    requestRef.current = controller;
    setBusy(true);
    setError(null);
    setResult(null);
    let uploaded = 0;
    const failures = [];
    try {
      for (let index = 0; index < files.length; index += 1) {
        const file = files[index]!;
        setProgress("Uploading " + (index + 1) + " of " + files.length + ": " + file.name);
        try {
          await apiRequest(
            "/sessions/" + encodeURIComponent(session.id) + "/files?name=" +
              encodeURIComponent(file.name),
            { method: "POST", body: file, signal: controller.signal, timeout: false },
          );
          uploaded += 1;
        } catch (e) {
          if (controller.signal.aborted) return;
          failures.push(file.name + ": " + errorMessage(e));
        }
      }

      setProgress("");
      setResult(
        "Uploaded " + uploaded + " " + (uploaded === 1 ? "file" : "files") +
          " to " + session.cwd + ".",
      );
      if (failures.length > 0) {
        setError(failures.join("\n"));
      }
      // Require a fresh selection so a second submit cannot replay successful files into conflicts.
      setFiles([]);
      if (inputRef.current) inputRef.current.value = "";
    } finally {
      if (requestRef.current === controller) requestRef.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  };

  return (
    <Dialog id="upload-dialog" labelledBy="upload-title" lightDismiss={!busy} onClose={onClose}>
      <form id="upload-form" onSubmit={submit}>
        <div class="dialog-head">
          <div><h2 id="upload-title">Upload files</h2><p>Send files from this device to the selected session.</p></div>
          <button id="upload-close" class="icon-button" type="button" aria-label="Close"
            onClick={onClose}>×</button>
        </div>
        <p class="upload-destination">{"Current folder "}<code>{session.cwd}</code></p>
        <label class="field">
          <span>{"Files "}<small>up to 100 MiB each</small></span>
          <input id="upload-files" class="file-input" type="file" multiple ref={inputRef}
            disabled={busy} onChange={selectedFiles} />
          <small class="field-hint">Existing files are never replaced. Rename a file first if its name is already present.</small>
        </label>
        {progress && (
          <p class="upload-progress" role="status">{progress}</p>
        )}
        {result && (
          <p class="upload-result" role="status">{result}</p>
        )}
        {error && (
          <p id="upload-error" class="form-error upload-error" role="alert">{error}</p>
        )}
        <div class="dialog-actions">
          <button id="upload-cancel" class="button button-quiet" type="button" onClick={onClose}>{busy ? "Cancel upload" : "Close"}</button>
          <button id="upload-submit" class="button button-primary" type="submit"
            disabled={busy || files.length === 0}>{busy ? "Uploading…" : files.length > 1 ? "Upload " + files.length + " files" : "Upload file"}</button>
        </div>
      </form>
    </Dialog>
  );
}

// Null means the task snapshot has not loaded; do not claim the backlog is empty.
function taskKeptSentence(count: number | null | undefined) {
  if (count === null || count === undefined) {
    return "Its tasks are kept, with their order, dependencies, comments and the sessions linked " +
      "to them.";
  }
  if (count === 0) return "It has no tasks, so nothing in the backlog changes.";
  if (count === 1) {
    return "Its 1 task is kept, with its dependencies, comments and the sessions linked to it.";
  }
  return "Its " + count + " tasks are kept, with their order, dependencies, comments and the " +
    "sessions linked to them.";
}

export interface DeleteProjectDialogProps {
  project: Project;
  taskCount?: number | null;
  onDelete: (projectId: string) => Promise<unknown>;
  onClose: () => void;
}

export function DeleteProjectDialog({ project, taskCount = null, onDelete, onClose }: DeleteProjectDialogProps) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);

  // Default focus stays on the non-destructive action.
  useEffect(() => { if (cancelRef.current) cancelRef.current.focus(); }, []);

  const submit = async (event: TargetedEvent<HTMLFormElement, SubmitEvent>) => {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await onDelete(project.id);
    } catch (e) {
      setError("Could not delete the project: " + errorMessage(e));
      setBusy(false);
    }
  };

  return (
    <Dialog id="delete-project-dialog" labelledBy="delete-project-title" lightDismiss={!busy}
      onClose={onClose}>
      <form id="delete-project-form" onSubmit={submit}>
        <div class="dialog-head">
          <div>
            <h2 id="delete-project-title">Delete project</h2>
            <p>Takes it out of every selector. Nothing on disk is touched.</p>
          </div>
          <button id="delete-project-close" class="icon-button" type="button" aria-label="Close"
            onClick={onClose}>×</button>
        </div>
        <p class="dialog-subject">
          <strong id="delete-project-name">{project.name || project.id}</strong>
          <code id="delete-project-path">{project.path || "last-seen directory unknown"}</code>
        </p>
        <ul id="delete-project-facts" class="dialog-facts">
          <li>{taskKeptSentence(taskCount)}</li>
          <li>
            {"Its "}
            <code>.kotgent.json</code>
            {` stays on disk. This writes nothing into the directory, and
            a project whose directory is already gone is deleted just the same.`}
          </li>
          <li>
            {`Restore brings the project and all of that back, exactly as it is now. Adopting the same
            directory again from New project brings it back too, with the name in the file and this
            checkout's path.`}
          </li>
        </ul>
        {error && (
          <p id="delete-project-error" class="form-error" role="alert">{error}</p>
        )}
        <div class="dialog-actions">
          <button id="delete-project-cancel" class="button button-quiet" type="button"
            ref={cancelRef} onClick={onClose}>{busy ? "Close" : "Cancel"}</button>
          <button id="delete-project-submit" class="button button-danger" type="submit"
            disabled={busy}>{busy ? "Deleting…" : "Delete project"}</button>
        </div>
      </form>
    </Dialog>
  );
}

export interface ForceReleaseMutexDialogProps {
  mutexKey: string;
  holderSessionId: string;
  holder: string;
  onRelease: (key: string, holderSessionId: string) => Promise<unknown>;
  onClose: () => void;
}

export function ForceReleaseMutexDialog({
  mutexKey, holderSessionId, holder, onRelease, onClose,
}: ForceReleaseMutexDialogProps) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => { if (cancelRef.current) cancelRef.current.focus(); }, []);

  const submit = async (event: TargetedEvent<HTMLFormElement, SubmitEvent>) => {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await onRelease(mutexKey, holderSessionId);
    } catch (e) {
      setError("Could not release the mutex: " + errorMessage(e));
      setBusy(false);
    }
  };

  return (
    <Dialog id="force-release-dialog" labelledBy="force-release-title" lightDismiss={!busy} onClose={onClose}>
      <form id="force-release-form" onSubmit={submit}>
        <div class="dialog-head">
          <div>
            <h2 id="force-release-title">Force release mutex</h2>
            <p>Takes the mutex away from the session that holds it.</p>
          </div>
          <button id="force-release-close" class="icon-button" type="button" aria-label="Close"
            onClick={onClose}>×</button>
        </div>
        <p class="dialog-subject">
          <strong id="force-release-key">{mutexKey}</strong>
          <span id="force-release-holder">held by {holder}</span>
        </p>
        <ul class="dialog-facts">
          <li>The holding session keeps running, and a command it started under this mutex is not stopped.</li>
          <li>The first session waiting for it gets the mutex next.</li>
        </ul>
        {error && (
          <p id="force-release-error" class="form-error" role="alert">{error}</p>
        )}
        <div class="dialog-actions">
          <button id="force-release-cancel" class="button button-quiet" type="button"
            ref={cancelRef} onClick={onClose}>{busy ? "Close" : "Cancel"}</button>
          <button id="force-release-submit" class="button button-danger" type="submit"
            disabled={busy}>{busy ? "Releasing…" : "Force release"}</button>
        </div>
      </form>
    </Dialog>
  );
}

export interface RestoreProjectDialogProps {
  onRestore: (projectId: string) => Promise<unknown>;
  onClose: () => void;
}

type RestoreProjectState =
  | { status: "loading" }
  | { status: "ready"; projects: Project[] }
  | { status: "error"; message: string };

export function RestoreProjectDialog({ onRestore, onClose }: RestoreProjectDialogProps) {
  const [state, setState] = useState<RestoreProjectState>({ status: "loading" });
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Avoid state updates after the dismissible dialog unmounts.
  const aliveRef = useRef(true);
  useEffect(() => () => { aliveRef.current = false; }, []);

  const load = useCallback(async () => {
    setState({ status: "loading" });
    setError(null);
    try {
      const rows = await fetchProjects(true);
      if (!aliveRef.current) return;
      setState({ status: "ready", projects: Array.isArray(rows) ? rows : [] });
    } catch (e) {
      if (!aliveRef.current) return;
      setState({ status: "error", message: errorMessage(e) });
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const restore = async (project: Project) => {
    if (busyId) return;
    setBusyId(project.id);
    setError(null);
    try {
      await onRestore(project.id);
    } catch (e) {
      if (!aliveRef.current) return;
      setError("Could not restore the project: " + errorMessage(e));
      setBusyId(null);
    }
  };

  return (
    <Dialog id="restore-project-dialog" labelledBy="restore-project-title" lightDismiss={!busyId}
      onClose={onClose}>
      <div id="restore-project-form">
        <div class="dialog-head">
          <div>
            <h2 id="restore-project-title">Restore a deleted project</h2>
            <p>Clears the delete mark. The backlog comes back with it.</p>
          </div>
          <button id="restore-project-close" class="icon-button" type="button" aria-label="Close"
            onClick={onClose}>×</button>
        </div>
        {restoreProjectBody(state, busyId, restore, load)}
        {error && (
          <p id="restore-project-error" class="form-error" role="alert">{error}</p>
        )}
        <div class="dialog-actions">
          <button id="restore-project-cancel" class="button button-quiet" type="button"
            onClick={onClose}>Close</button>
        </div>
      </div>
    </Dialog>
  );
}

function restoreProjectBody(
  state: RestoreProjectState,
  busyId: string | null,
  restore: (project: Project) => Promise<void>,
  reload: () => Promise<void>,
) {
  if (state.status === "loading") {
    return (
      <p id="restore-project-status" class="dialog-status">Reading deleted projects…</p>
    );
  }
  if (state.status === "error") {
    return [
      <p id="restore-project-load-error" class="form-error" role="alert">{"Could not read the deleted projects: "}{state.message}</p>,
      <button id="restore-project-retry" class="button" type="button" onClick={reload}>Try again</button>
    ];
  }
  if (state.projects.length === 0) {
    return (
      <p id="restore-project-empty" class="dialog-empty">No deleted projects. Only a project delete puts one here, so there is nothing to bring back.</p>
    );
  }
  return (
    <ul id="restore-project-list" class="dialog-list">
      {state.projects.map((project) => (
        <li key={project.id}>
          <button class="dialog-list-row" type="button" data-id={project.id} disabled={!!busyId}
            onClick={() => restore(project)}>
            <span class="dialog-list-name">{project.name || project.id}</span>
            <span class="dialog-list-sub">{project.path || "last-seen directory unknown"}</span>
            <span class="dialog-list-action">{busyId === project.id ? "Restoring…" : "Restore"}</span>
          </button>
        </li>
      ))}
    </ul>
  );
}

function openTasksForProject(tasks: Task[] | null | undefined) {
  return (tasks || [])
    .filter((task) => task && isOpenTaskState(task.state))
    .sort((left, right) => {
      const state = taskStateRank(left.state) - taskStateRank(right.state);
      return state !== 0 ? state : compareTasksByBoardOrder(left, right);
    });
}

function linkSessionChanged(initial: Session | null | undefined, current: Session | null | undefined) {
  if (!initial || !current || initial.id !== current.id) return true;
  return initial.projectId !== current.projectId ||
    sessionTaskLinkDisabledReason(current) !== null;
}

export interface LinkTaskDialogProps {
  initialSession: Session | null;
  session: Session | null | undefined;
  tasks?: readonly Task[];
  tasksStatus?: ReadinessStatus;
  projectsStatus?: ReadinessStatus;
  projectActive?: boolean;
  onRetryProjects: () => unknown;
  onLink: (sessionId: string, taskRef: string, projectId: string | null) => Promise<unknown>;
  onClose: () => void;
}

export function LinkTaskDialog({
  initialSession,
  session,
  tasks = [],
  tasksStatus = IDLE_STATUS,
  projectsStatus = IDLE_STATUS,
  projectActive = false,
  onRetryProjects,
  onLink,
  onClose,
}: LinkTaskDialogProps) {
  const [query, setQuery] = useState("");
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  // Suppress errors that arrive after the picker unmounts.
  const aliveRef = useRef(true);
  // The synchronous signal guard prevents two commits in the same task.
  const busyTask = useSignal<string | null>(null);
  const busyTaskRef = busyTask.value;
  const busy = busyTaskRef !== null;
  const changed = !busy && linkSessionChanged(initialSession, session);
  // Judged on the project read alone: once that list has answered, an archived project is a definite
  // refusal and there is no reason to wait for the task snapshot before saying so.
  const projectUnavailable = projectsStatus.state === READY && !projectActive;
  // Both reads must settle; failure supplies the retry control's message.
  const readiness = combineReadiness(tasksStatus, projectsStatus);
  const ready = readiness.state === READY;
  const failure = readiness.state === FAILED ? readiness.error : null;
  const projectId = initialSession && initialSession.projectId;
  const rows = useMemo(
    () => (projectActive
      ? openTasksForProject((tasks || []).filter((task) => task && task.project === projectId))
      : []),
    [tasks, projectId, projectActive],
  );
  const normalizedQuery = normalizeTaskQuery(query);
  const results = useMemo(() => {
    // Preserve the source array identity for an empty query.
    if (!normalizedQuery) return rows;
    return rows.filter((task) => taskMatchesQuery(task, normalizedQuery));
  }, [rows, normalizedQuery]);
  // While any of these hold, the list is not the keyboard's to navigate and Enter must not link.
  const listLocked = changed || projectUnavailable || !ready;
  const keys = useMemo(
    () => (listLocked ? [] : results.map((task) => task.ref)),
    [results, listLocked],
  );

  useEffect(() => () => { aliveRef.current = false; }, []);
  useEffect(() => {
    if (!busy && error && inputRef.current) inputRef.current.focus();
  }, [busy, error]);

  // The callback captures `typeahead`, which is read only after both declarations complete.
  const choose = async (task: Task | undefined) => {
    if (!task || busyTask.peek() !== null || listLocked || !initialSession) return;
    setError(null);
    typeahead.activate(task.ref);
    busyTask.value = task.ref;
    try {
      await onLink(initialSession.id, task.ref, initialSession.projectId);
    } catch (e) {
      if (!aliveRef.current) return;
      setError(
        "Could not link " + displayName(initialSession) + " to " + task.ref + ": " + errorMessage(e),
      );
      busyTask.value = null;
    }
  };

  // Only keyboard navigation clears the error; pointer and focus activation are passive.
  const typeahead = useTypeahead({
    keys: keys,
    token: normalizedQuery,
    onNavigate: () => setError(null),
    onCommit: (ref: string) => choose(results.find((task) => task.ref === ref)),
  });
  const activeTaskRef = typeahead.activeKey;
  const activeIndex = results.findIndex((task) => task.ref === activeTaskRef);
  const listId = !listLocked && results.length > 0 ? "link-task-list" : null;

  const listBody = linkTaskBody({
    changed: changed,
    projectUnavailable: projectUnavailable,
    failure: failure,
    ready: ready,
    rows: rows,
    results: results,
    query: query,
    activeTaskRef: activeTaskRef,
    busy: busy,
    busyTaskRef: busyTaskRef,
    typeahead: typeahead,
    choose: choose,
    onRetryProjects: onRetryProjects,
  });

  return (
    <Dialog id="link-task-dialog" labelledBy="link-task-title" lightDismiss={!busy} onClose={onClose}>
      <div id="link-task-form" aria-busy={busy ? "true" : "false"}>
        <div class="dialog-head">
          <div>
            <h2 id="link-task-title">Link session to a task</h2>
            <p>{initialSession ? displayName(initialSession) : "Selected session"}{" · open tasks in its project"}</p>
          </div>
          <button id="link-task-close" class="icon-button" type="button" aria-label="Close"
            onClick={onClose}>×</button>
        </div>
        <label class="field link-picker-search">
          <span>Search by ref or title</span>
          <input id="link-task-query" type="search" role="combobox" autoComplete="off" autoFocus
            spellcheck={false} placeholder="local:42 or task title" ref={inputRef}
            aria-autocomplete="list" aria-controls={listId ?? undefined} aria-expanded={listId ? "true" : "false"}
            aria-activedescendant={listId && activeIndex >= 0
                   ? "link-task-option-" + activeIndex
                   : undefined}
            disabled={busy || changed || projectUnavailable || failure !== null} value={query}
            onInput={(event) => { setError(null); setQuery(event.currentTarget.value); }}
            onKeyDown={typeahead.keyDown} />
        </label>
        <div class="link-picker-results">{listBody}</div>
        {error && (
          <p id="link-task-error" class="form-error" role="alert">{error}</p>
        )}
        <div class="dialog-actions">
          <button id="link-task-cancel" class="button button-quiet" type="button" onClick={onClose}>{busy ? "Close" : "Cancel"}</button>
        </div>
      </div>
    </Dialog>
  );
}

interface LinkTaskBodyProps {
  changed: boolean;
  projectUnavailable: boolean;
  failure: string | null;
  ready: boolean;
  rows: Task[];
  results: Task[];
  query: string;
  activeTaskRef: string | null;
  busy: boolean;
  busyTaskRef: string | null;
  typeahead: TypeaheadResult<string>;
  choose: (task: Task | undefined) => Promise<void>;
  onRetryProjects: () => unknown;
}

function linkTaskBody({
  changed, projectUnavailable, failure, ready, rows, results, query,
  activeTaskRef, busy, busyTaskRef, typeahead, choose, onRetryProjects,
}: LinkTaskBodyProps) {
  if (changed) {
    return (
      <p id="link-task-changed" class="form-error" role="status">The selected session changed while this picker was open. Close it and try again.</p>
    );
  }
  if (projectUnavailable) {
    return (
      <p id="link-task-project-missing" class="form-error" role="status">This session's project is no longer active. Restore it before linking a task.</p>
    );
  }
  if (failure) {
    return (
      <div id="link-task-failed" role="alert">
        <p class="form-error">{failure}</p>
        <div class="dialog-actions"><button id="link-task-retry" class="button" type="button" onClick={onRetryProjects}>Try again</button></div>
      </div>
    );
  }
  if (!ready) {
    return (
      <p id="link-task-status" class="dialog-status">Reading open tasks…</p>
    );
  }
  if (results.length === 0) {
    return (
      <p id="link-task-empty" class="dialog-empty">
        {rows.length === 0
          ? "No open tasks in this session's project."
          : "No open tasks match “" + query.trim() + "”."}
      </p>
    );
  }
  return (
    <ul id="link-task-list" class="dialog-list link-picker-list" role="listbox">
      {results.map((task, index) => (
        <li key={task.ref} role="presentation">
          <button id={"link-task-option-" + index}
            class={"dialog-list-row link-picker-option" +
              (task.ref === activeTaskRef ? " active" : "")}
            type="button" role="option" aria-selected={task.ref === activeTaskRef ? "true" : "false"}
            data-ref={task.ref} data-state={task.state} disabled={busy}
            ref={typeahead.optionRef(task.ref)} onMouseEnter={() => typeahead.activate(task.ref)}
            onFocus={() => typeahead.activate(task.ref)} onClick={() => choose(task)}>
            <span class="dialog-list-name">{task.title || task.ref}</span>
            <span class="dialog-list-sub link-picker-meta">
              <span>{task.ref}</span>
              {task.blocked && (
                <span class="link-picker-blocked" title="A dependency is not done yet">Blocked</span>
              )}
            </span>
            <span class="dialog-list-action link-picker-state">
              {busyTaskRef === task.ref
                ? "Linking…"
                : taskStateLabel(task.state)}
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}

function groupingPreview(draft: Preferences, sessions: readonly Session[]) {
  if (!draft.basePath) return "No base path — sessions are listed flat.";
  const base = normalizePath(draft.basePath);
  const baseLabel = basename(base) || base;
  const sample = sessions.find((s) => segmentsUnder(draft.basePath, s.cwd) !== null);
  const segments = sample ? segmentsUnder(base, sample.cwd) : null;
  if (!sample || !segments) {
    const placeholders = Array.from({ length: draft.groupingLevel }, (_, i) => "<dir" + (i + 1) + ">");
    return placeholders.length > 0
      ? "Tree below " + base + ": " + placeholders.join(" › ")
      : base + " → " + baseLabel + " (one folder for all sessions below the base)";
  }
  const visible = segments.slice(0, draft.groupingLevel);
  const branch = visible.length > 0 ? visible.join(" › ") : baseLabel;
  const bucketed = segments.length > visible.length ? " (deeper folders stay here)" : "";
  return normalizePath(sample.cwd) + " → " + branch + bucketed;
}

const LEVEL_LABELS = [
  "0 — one base folder",
  "1 — direct child folders",
  "2 — up to two folder levels",
  "3 — up to three folder levels",
  "4 — up to four folder levels",
];

const TERMINAL_FONT_LABELS = new Map([
  [11, "Small"],
  [13, "Medium"],
  [16, "Large"],
]);

export interface PreferencesDialogProps {
  prefs: Preferences;
  sessions: readonly Session[];
  onSave: (prefs: Preferences) => Promise<unknown>;
  onClose: () => void;
}

export function PreferencesDialog({ prefs, sessions, onSave, onClose }: PreferencesDialogProps) {
  const [basePath, setBasePath] = useState(prefs.basePath);
  const [level, setLevel] = useState(String(prefs.groupingLevel));
  const [fontSize, setFontSize] = useState(String(prefs.terminalFontSize));
  const [unicode, setUnicode] = useState<string>(prefs.terminalUnicode);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => { if (inputRef.current) inputRef.current.focus(); }, []);

  const submit = async (event: TargetedEvent<HTMLFormElement, SubmitEvent>) => {
    event.preventDefault();
    const cleaned = normalizePath(basePath);
    if (cleaned.length > 0 && cleaned.charAt(0) !== "/") {
      setError("Base path must be absolute (start with /).");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onSave(sanitizePrefs({
        basePath: cleaned,
        groupingLevel: level,
        terminalFontSize: fontSize,
        terminalUnicode: unicode,
      }));
    } catch (e) {
      setError("Could not save preferences: " + errorMessage(e));
      setBusy(false);
    }
  };

  const preview = groupingPreview(sanitizePrefs({
    basePath: basePath,
    groupingLevel: level,
    terminalFontSize: fontSize,
  }), sessions);

  return (
    <Dialog id="prefs-dialog" labelledBy="prefs-title" lightDismiss={!busy} onClose={onClose}>
      <form id="prefs-form" onSubmit={submit}>
        <div class="dialog-head">
          <div>
            <h2 id="prefs-title">Preferences</h2>
            <p>Base path and tree depth are shared by every browser connected to this daemon.</p>
          </div>
          <button id="prefs-close" class="icon-button" type="button" aria-label="Close"
            onClick={onClose}>×</button>
        </div>
        <label class="field">
          <span>Base path</span>
          <input id="prefs-base-path" type="text" spellcheck={false} placeholder="/Users/you/dev"
            ref={inputRef} value={basePath} onInput={(e) => setBasePath(e.currentTarget.value)} />
          <small class="field-hint">
            {`Absolute path. Sessions below it form a folder tree, and new sessions default to it. Leave
            empty for one flat list.`}
          </small>
        </label>
        <label class="field">
          <span>{"Tree depth "}<small>maximum visible folders below the base path</small></span>
          <select id="prefs-grouping-level" value={level} onChange={(e) => setLevel(e.currentTarget.value)}>
            {LEVEL_LABELS.slice(0, MAX_GROUPING_LEVEL + 1).map((label, value) => (
              <option key={value} value={String(value)}>{label}</option>
            ))}
          </select>
          <small id="prefs-grouping-preview" class="field-hint">{preview}</small>
        </label>
        <label class="field">
          <span>Terminal font size</span>
          <select id="prefs-terminal-font-size" value={fontSize}
            onChange={(e) => setFontSize(e.currentTarget.value)}>
            {TERMINAL_FONT_SIZES.map((size) => (
              <option key={size} value={String(size)}>{(TERMINAL_FONT_LABELS.get(size) || "Custom") + " — " + size + " px"}</option>
            ))}
          </select>
          <small class="field-hint">Stored only in this browser and applied immediately to an attached terminal after you save.</small>
        </label>
        <label class="field">
          <span>{"Terminal unicode "}<small>how wide a character is measured</small></span>
          <select id="prefs-terminal-unicode" value={unicode}
            onChange={(e) => setUnicode(e.currentTarget.value)}>
            {TERMINAL_UNICODE_MODES.map((mode) => (
              <option key={mode.value} value={mode.value}>{mode.label}</option>
            ))}
          </select>
          <small id="prefs-terminal-unicode-hint" class="field-hint">
            {terminalUnicodeMode(unicode).hint}
            {` Stored only in this browser. A change applies to what the
            pane draws next, not to the screen already on it.`}
          </small>
        </label>
        {error && (
          <p id="prefs-error" class="form-error" role="alert">{error}</p>
        )}
        <div class="dialog-actions">
          <button id="prefs-cancel" class="button button-quiet" type="button" onClick={onClose}>{busy ? "Close" : "Cancel"}</button>
          <button id="prefs-submit" class="button button-primary" type="submit" disabled={busy}>{busy ? "Saving…" : "Save"}</button>
        </div>
      </form>
    </Dialog>
  );
}

export interface PhoneDialogProps {
  onClose: () => void;
}

interface AuthTicket {
  publicUrl?: string | null;
  ticket?: string | null;
}

type PhoneState =
  | { status: "loading" }
  | { status: "ready"; ticket: ApiResponse<AuthTicket> }
  | { status: "error"; message: string };

/* The QR contains the credential-free install URL so Safari cannot spend the installed PWA's ticket. */
export function PhoneDialog({ onClose }: PhoneDialogProps) {
  const [state, setState] = useState<PhoneState>({ status: "loading" });

  const issue = useCallback(async () => {
    setState({ status: "loading" });
    try {
      const ticket = await apiRequest<AuthTicket>(AUTH_TICKET_PATH, { method: "POST" });
      setState({ status: "ready", ticket: ticket });
    } catch (e) {
      setState({ status: "error", message: errorMessage(e) });
    }
  }, []);

  useEffect(() => { issue(); }, [issue]);

  return (
    <Dialog id="phone-dialog" labelledBy="phone-title" onClose={onClose}>
      <div id="phone-form">
        <div class="dialog-head">
          <div><h2 id="phone-title">Sign in from your phone</h2><p>Scan to open kotgent on another device.</p></div>
          <button id="phone-close" class="icon-button" type="button" aria-label="Close"
            onClick={onClose}>×</button>
        </div>
        {phoneBody(state, issue, onClose)}
      </div>
    </Dialog>
  );
}

/** Display-only grouping is safe because normalizeTicketCode strips whitespace. */
function groupCode(code: string | null | undefined) {
  const value = String(code || "");
  if (value.length < 6 || value.length % 2 !== 0) return value;
  const half = value.length / 2;
  return value.slice(0, half) + " " + value.slice(half);
}

/** Strip the one-shot credential fragment from the public install URL. */
function installUrl(ticketUrl: string | null | undefined) {
  return String(ticketUrl || "").split("#", 1)[0]!;
}

function phoneBody(state: PhoneState, issue: () => Promise<void>, onClose: () => void) {
  if (state.status === "loading") {
    return (
      <p id="phone-status" class="phone-status">Minting a one-time sign-in code…</p>
    );
  }
  if (state.status === "error") {
    return [
      <p id="phone-error" class="form-error" role="alert">{"Could not mint a sign-in code: "}{state.message}</p>,
      <div class="dialog-actions">
        <button class="button button-quiet" type="button" onClick={onClose}>Close</button>
        <button class="button button-primary" type="button" onClick={issue}>Try again</button>
      </div>
    ];
  }

  const ticket = typeof state.ticket === "object" && state.ticket ? state.ticket : {};
  if (!ticket.publicUrl) return phoneSetup(onClose);
  const publicInstallUrl = installUrl(ticket.publicUrl);

  return [
    <div id="phone-qr" class="phone-qr" dangerouslySetInnerHTML={{ __html: qrSvg(publicInstallUrl) }} />,
    <p class="phone-url"><code>{publicInstallUrl}</code></p>,
    <p class="phone-code-hint">Scan this credential-free page in Safari, add Kotgent to the home screen, then launch the installed app.</p>,
    ticket.ticket && [
      <p id="phone-code" class="phone-code">{groupCode(ticket.ticket)}</p>,
      <p class="phone-code-hint">
        {`Type this code into the installed app. It has its own cookie jar, and the QR deliberately does not
        spend the code in Safari.`}
      </p>
    ],
    <p class="phone-warn" role="note">
      {`The code is one-time · expires in 5 minutes · grants full terminal access. Refresh it if you did not
      just use it yourself.`}
    </p>,
    <div class="dialog-actions">
      <button class="button button-quiet" type="button" onClick={onClose}>Close</button>
      <button id="phone-refresh" class="button button-primary" type="button" onClick={issue}>Refresh</button>
    </div>
  ];
}

function phoneSetup(onClose: () => void) {
  const port = window.location.port;
  const ingress = "  - hostname: <your-tunnel-host>\n    service: http://127.0.0.1:" + port;
  return [
    <p id="phone-setup" class="phone-note">
      {`No public URL is configured, so there is nothing to point a phone at yet. Phone access runs over a
      Cloudflare tunnel to this daemon — a one-time setup:`}
    </p>,
    <ol class="phone-steps">
      <li>{"Add an ingress rule to "}<code>~/.cloudflared/config.yml</code>:<pre class="help-code">{ingress}</pre></li>
      <li>
        {"Put "}
        <strong>Cloudflare Access</strong>
        {` in front of the host and scope the policy to your own
        email only. This host fronts a terminal that can run anything on your Mac, so a loose policy is
        more dangerous here than anywhere else — do not publish it without the identity gate.`}
      </li>
      <li>
        Tell kotgent its public origin:
        <pre class="help-code">kotgent config set public-url https://your-tunnel-host</pre>
      </li>
      <li>Restart the daemon, then reopen this dialog to get a QR code.</li>
    </ol>,
    <div class="dialog-actions"><button class="button button-primary" type="button" onClick={onClose}>Done</button></div>
  ];
}

const CLI_HELP = `kotgent list                  list sessions
kotgent start <agent> [cwd]   start a session (claude | codex | junie | shell)
kotgent import <agent> <id>   register a session started outside kotgent, then resume it
kotgent attach <id>           attach a raw terminal
kotgent interrupt <id>        send Ctrl-C
kotgent stop <id>             stop a session
kotgent resume <id>           resume a stopped/crashed/resumable session (never a lost one)
kotgent daemon [--port N]     run the control plane`;

const STATES: [string, string, string][] = [
  ["running", "badge-running", "The agent is working on a turn."],
  ["ready", "badge-ready", "Alive and idle — it finished its turn and is waiting for your next prompt."],
  ["needs approval", "badge-attention",
    "Blocked asking permission for an action. Answer it in the terminal; the approval clears by itself " +
    "as soon as the agent acts again."],
  ["needs answer", "badge-attention",
    "Blocked on a question. Modeled but never produced by the current Claude adapter — interactive " +
    "Claude gives no \"waiting for an answer\" signal."],
  ["stopped", "badge-dead", "The agent process exited cleanly (this is what Stop leaves behind)."],
  ["crashed", "badge-crashed",
    "The agent process exited with a failure, or its launch did. The daemon reclassifies it as resumable " +
    "or lost as soon as it looks at the agent's own store."],
  ["lost", "badge-lost",
    "Dead and unrecoverable: the pane is gone and the agent no longer keeps the conversation — Claude " +
    "deletes a transcript 30 days after its last turn. A stopped session whose transcript is gone turns lost " +
    "at the next daemon start or refused Resume. Resume is refused; start a new session."],
  ["resumable", "badge-resumable", "Dead, but the conversation transcript survives — Resume can revive it."],
];

const CONTROLS: [string, string][] = [
  ["New session",
    "Starts an agent in the directory you give it. With a base path set in Preferences, each group's " +
    "+ starts one in that group's directory instead."],
  ["Import",
    "The New session dialog's second mode: registers a conversation started outside kotgent by its " +
    "provider session id. Registration alone touches nothing — the session arrives resumable — and " +
    "unless you tick “register only”, it is resumed for you right away."],
  ["Attach",
    "Connects this browser to the session's terminal. It starts nothing — it only opens a view on an " +
    "agent that is already running."],
  ["Link to task",
    "For a live session that has no task yet, open the command palette and press L. Search the open " +
    "tasks in that session's project by ref or title, then choose one. A to-do task moves to in " +
    "progress; linking never prevents another session from working on the same task."],
  ["Interrupt",
    "Sends Ctrl-C to the pane and marks the session ready, clearing any pending approval. Use it for a " +
    "turn that is stuck or running away. The agent stays alive."],
  ["Stop",
    "Kills the tmux session, so the agent process ends and the state becomes stopped. Resume revives it " +
    "for as long as the agent keeps the conversation transcript."],
  ["Resume",
    "Relaunches the agent against the saved transcript in a fresh tmux session and puts it back to " +
    "ready. It is refused while the provider's session id has not been captured yet — that id is what " +
    "a resume is addressed to — and for a lost session, whose transcript the agent has deleted."],
  ["Detach",
    "Closes only your terminal client. The agent keeps working; when the last viewer leaves, the daemon " +
    "drops its upstream tmux attach too."],
];

export interface HelpDialogProps {
  onClose: () => void;
}

export function HelpDialog({ onClose }: HelpDialogProps) {
  const bodyRef = useRef<HTMLDivElement>(null);
  useEffect(() => { if (bodyRef.current) bodyRef.current.scrollTop = 0; }, []);

  return (
    <Dialog id="help-dialog" labelledBy="help-title" onClose={onClose}>
      <div id="help-form">
        <div class="dialog-head">
          <div><h2 id="help-title">How kotgent works</h2><p>Sessions, states, and what each control does.</p></div>
          <button id="help-close" class="icon-button" type="button" aria-label="Close"
            onClick={onClose}>×</button>
        </div>
        <div id="help-body" ref={bodyRef}>
          <section class="help-section">
            <h3>Sessions</h3>
            <p>
              {"A session is one coding agent running inside its own "}
              <code>tmux</code>
              {` session
              (`}
              <code>{"kt-<id>"}</code>
              {`) in a working directory you pick. The daemon owns it — not this
              page. Closing the tab, detaching, or restarting the daemon does not stop the agent.`}
            </p>
            <p>
              What you see in the right pane is that real tmux pane. The daemon holds exactly one
              <code>tmux attach</code>
              {` per session and fans it out to every viewer, so a browser, an IDE
              and `}
              <code>kotgent attach</code>
              {" all watch and type into the same terminal."}
            </p>
            <p>
              {`State is never stored directly: the agent reports events through hooks, they are appended
              to a per-session log, and the state you see is replayed from that log. That is why sessions
              survive a daemon restart.`}
            </p>
          </section>
          <section id="help-tmux" class="help-section">
            <h3>tmux, scrolling, and copying</h3>
            <p>
              {"Kotgent uses tmux's default prefix: press "}
              <kbd>Ctrl</kbd>
              +
              <kbd>B</kbd>
              {`, release both,
              then press the command key. Your `}
              <code>~/.tmux.conf</code>
              {` is not loaded on Kotgent's
              dedicated tmux server, so custom prefixes and bindings do not apply here.`}
            </p>
            <dl class="help-list">
              <dt><kbd>Ctrl</kbd>+<kbd>B</kbd>{", then "}<kbd>[</kbd></dt>
              <dd>
                Enter tmux copy mode to browse the pane's 10,000-line history. Use the arrow,
                <kbd>Page Up</kbd>
                {", and "}
                <kbd>Page Down</kbd>
                {` keys; the mouse wheel enters and scrolls
                this mode automatically.`}
              </dd>
              <dt><kbd>Esc</kbd>{" or "}<kbd>q</kbd></dt>
              <dd>
                {`Leave copy mode and return keyboard input to the agent. Scrolling all the way back to
                the bottom also exits it.`}
              </dd>
              <dt><kbd>Option</kbd>{"-drag, then "}<kbd>Cmd</kbd>+<kbd>C</kbd></dt>
              <dd>
                {`Select terminal text and copy it to the browser clipboard on macOS. Hold Option while
                dragging so xterm selects the text instead of sending the drag to tmux or the agent.`}
              </dd>
              <dt><kbd>Shift</kbd>{"-drag, then "}<kbd>Ctrl</kbd>+<kbd>C</kbd></dt>
              <dd>The equivalent browser-copy gesture on other platforms.</dd>
            </dl>
            <p class="help-note">
              {`Browser selection and tmux copy mode are separate: copying in the browser does not use
              tmux's paste buffer. Copy mode belongs to the pane and is shared by every viewer, so if
              typing appears to be ignored after someone scrolls, leave copy mode or return to the bottom.
              To leave, use the palette's Detach command — the ⋯ button in the terminal header, or`}
              <kbd>⌘</kbd>
              +
              <kbd>K</kbd>
              {", then "}
              <kbd>E</kbd>
              {` — instead of tmux's detach binding; it closes
              only this viewer.`}
            </p>
          </section>
          <section class="help-section">
            <h3>States</h3>
            <dl class="help-list">
              {STATES.map(([label, cls, description]) => [
                <dt key={label}><span class={"pill badge " + cls}>{label}</span></dt>,
                <dd key={label + "-d"}>{description}</dd>
              ])}
            </dl>
            <p class="help-note">
              {"The first four are "}
              <em>alive</em>
              {" (a process is running in tmux), the last three are"}
              <em>dead</em>
              . The two "needs" states are the ones counted as needing attention.
            </p>
          </section>
          <section class="help-section">
            <h3>Controls</h3>
            <dl class="help-list">
              {CONTROLS.map(([label, description]) => [
                <dt key={label}>{label}</dt>,
                <dd key={label + "-d"}>{description}</dd>
              ])}
            </dl>
          </section>
          <section class="help-section">
            <h3>The sidebar</h3>
            <p>
              {`"Needs attention" repeats the sessions blocked on you at the top so nothing is missed; click
              its header to collapse it to their count. The blue pill is the number of events appended since
              you last read the session, and the badge is its current state. With a base path set in Preferences, rows are grouped
              by working directory; anything outside that base path is grouped under its own path at the
              end. Click a group's header to collapse it — collapsed groups and "Needs attention" are
              remembered in this browser, and a group keeps its dot while it hides a session that needs
              attention.`}
            </p>
            <p>
              {`ADHD mode, the pin button in the sidebar header, lists only the sessions you pinned, the
              sessions shown under a group header you pinned, and the session you have selected. It hides
              "Needs attention" altogether, even for pinned sessions, though their notifications still fire.`}
            </p>
          </section>
          <section class="help-section">
            <h3>Access</h3>
            <p>
              {"The daemon listens on "}
              <code>127.0.0.1</code>
              {`, and this page signs in with a session cookie
              rather than a token in the URL. Run `}
              <code>kotgent web</code>
              {` to open it: that mints a
              one-time ticket, exchanges it for an `}
              <code>HttpOnly</code>
              {` cookie, and leaves nothing secret
              in the address bar. The master token (`}
              <code>~/.kotgent/token</code>
              {`) stays the machine's
              key — the hooks and the CLI use it. The cookie is a key to your agents, so treat this
              browser profile as you would an SSH session.`}
            </p>
          </section>
          <section class="help-section"><h3>The same thing from a terminal</h3><pre class="help-code">{CLI_HELP}</pre></section>
        </div>
        <div class="dialog-actions"><button id="help-done" class="button button-primary" type="button" onClick={onClose}>Done</button></div>
      </div>
    </Dialog>
  );
}
