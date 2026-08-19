/* Dialog is the sole owner of the native imperative API. Light-dismiss gestures use the backdrop or
 * touch grabber and fail toward preserving drafts. htm copy interpolates literal `<` characters. */

import { html } from "htm/preact";
import { useCallback, useEffect, useRef, useState } from "preact/hooks";
import { AGENT_CHOICES, FIRST_AVAILABLE_AGENT } from "../lib/agents.js";
import { basename, normalizePath, segmentsUnder } from "../lib/paths.js";
import { MAX_GROUPING_LEVEL, TERMINAL_FONT_SIZES, sanitizePrefs } from "../lib/prefs.js";
import { TERMINAL_UNICODE_MODES, terminalUnicodeMode } from "../lib/unicode.js";
import { AUTH_TICKET_PATH, apiRequest, errorMessage } from "../lib/api.js";
import { fetchProjects } from "../lib/tasks.js";
import { qrSvg } from "../lib/qr.js";

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

export function Dialog({ id, labelledBy, lightDismiss = true, onClose, children }) {
  const ref = useRef(null);
  // Only one primary pointer's completed outside down-up-click may authorize dismissal.
  const outsidePress = useRef(null);
  const dragRef = useRef(null);

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
  const outside = (event) => {
    const el = ref.current;
    if (!el || event.target !== el) return false;
    const rect = el.getBoundingClientRect();
    return event.clientX < rect.left || event.clientX > rect.right ||
      event.clientY < rect.top || event.clientY > rect.bottom;
  };

  const springBack = (el, pointerId) => {
    if (el.hasPointerCapture(pointerId)) el.releasePointerCapture(pointerId);
    el.style.transition = prefersReducedMotion() ? "none" : "transform 160ms ease-out";
    el.style.transform = "";
  };

  const pointerDown = (event) => {
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
    const from = event.target && event.target.closest ? event.target : null;
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

  const pointerMove = (event) => {
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

  const pointerUp = (event) => {
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
  const pointerCancel = (event) => {
    const press = outsidePress.current;
    if (press && press.pointerId === event.pointerId) outsidePress.current = null;
    const drag = dragRef.current;
    const el = ref.current;
    if (!drag || !el || event.pointerId !== drag.pointerId) return;
    dragRef.current = null;
    if (!drag.dragging) return;
    springBack(el, event.pointerId);
  };

  const click = (event) => {
    const press = outsidePress.current;
    outsidePress.current = null;
    if (!press || !press.released || !lightDismiss || !outside(event)) return;
    // When click exposes pointer identity, require it to match the press.
    if (typeof event.pointerId === "number" && event.pointerId !== press.pointerId) return;
    if (ref.current) ref.current.close();
  };

  return html`
    <dialog id=${id} ref=${ref} aria-labelledby=${labelledBy}
            onPointerDown=${pointerDown} onPointerMove=${pointerMove}
            onPointerUp=${pointerUp} onPointerCancel=${pointerCancel} onClick=${click}>
      <div class="dialog-grabber" aria-hidden="true"></div>
      ${children}
    </dialog>
  `;
}

const DIRECTORY_COMPLETION_DELAY_MS = 150;

/* Start and import share one form. taskRef belongs only to start requests; import discovers cwd from
 * the provider transcript unless the operator explicitly overrides it. */
export function NewSessionDialog({
  initialCwd, initialMode = "start", initialAgent = "", initialTaskRef = null,
  basePath, onStart, onImport, onClose,
}) {
  const [mode, setMode] = useState(initialMode);
  const [agent, setAgent] = useState(initialAgent);
  const [cwd, setCwd] = useState(initialMode === "import" ? "" : (initialCwd || ""));
  const [completionQuery, setCompletionQuery] = useState(null);
  const [suggestions, setSuggestions] = useState([]);
  const [activeSuggestion, setActiveSuggestion] = useState(-1);
  const [cwdFocused, setCwdFocused] = useState(false);
  const [name, setName] = useState("");
  const [tags, setTags] = useState("");
  const [sessionId, setSessionId] = useState("");
  const [registerOnly, setRegisterOnly] = useState(false);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const cwdRef = useRef(null);
  const agentRef = useRef(null);
  const sessionIdRef = useRef(null);
  const taskRef = typeof initialTaskRef === "string" && initialTaskRef.trim().length > 0
    ? initialTaskRef.trim()
    : null;

  // Focus the first unanswered field; only the free-terminal command preselects an agent.
  useEffect(() => {
    const target = agent ? cwdRef.current : agentRef.current;
    if (target) target.focus();
  }, []);

  useEffect(() => {
    if (completionQuery === null) return undefined;

    const typed = completionQuery.trim();
    const normalizedBase = normalizePath(basePath);
    setSuggestions([]);
    setActiveSuggestion(-1);
    if (!typed || (typed.charAt(0) !== "/" && normalizedBase.charAt(0) !== "/")) return undefined;

    const controller = new AbortController();
    const timer = setTimeout(() => {
      apiRequest("/directories/complete", {
        method: "POST",
        signal: controller.signal,
        body: JSON.stringify({ basePath: normalizedBase || null, input: typed }),
      })
        .then((response) => {
          if (controller.signal.aborted) return;
          const paths = response && Array.isArray(response.paths)
            ? response.paths.filter((path) => typeof path === "string")
            : [];
          setSuggestions(paths);
        })
        .catch((e) => {
          if (!controller.signal.aborted && (!e || e.name !== "AbortError")) setSuggestions([]);
        });
    }, DIRECTORY_COMPLETION_DELAY_MS);

    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [completionQuery, basePath]);

  const chooseSuggestion = (path) => {
    setCwd(path);
    setCompletionQuery(null);
    setSuggestions([]);
    setActiveSuggestion(-1);
    if (cwdRef.current) cwdRef.current.focus();
  };

  const cwdInput = (event) => {
    const value = event.target.value;
    setCwd(value);
    setCompletionQuery(value);
  };

  const cwdKeyDown = (event) => {
    if (!cwdFocused || suggestions.length === 0) return;
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActiveSuggestion((index) => (index + 1) % suggestions.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveSuggestion((index) => (index <= 0 ? suggestions.length - 1 : index - 1));
    } else if (event.key === "Enter" && activeSuggestion >= 0) {
      event.preventDefault();
      chooseSuggestion(suggestions[activeSuggestion]);
    } else if (event.key === "Escape") {
      event.preventDefault();
      setCompletionQuery(null);
      setSuggestions([]);
      setActiveSuggestion(-1);
    }
  };

  const chooseAgent = (event) => {
    setAgent(event.target.value);
    setError(null);
  };

  const switchMode = (next) => {
    setMode(next);
    setError(null);
    if (next === "import" && AGENT_CHOICES.some(
      (choice) => choice.value === agent && choice.importable === false,
    )) setAgent("");
    // Never carry a start-mode cwd into import as an accidental transcript-discovery override.
    setCwd(next === "import" ? "" : (initialCwd || ""));
    setCompletionQuery(null);
    setSuggestions([]);
    setActiveSuggestion(-1);
  };

  const submit = async (event) => {
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
        const body = { agent: agent, cwd: cwd.trim(), name: name.trim() || null, tags: tagList };
        if (taskRef) body.taskRef = taskRef;
        await onStart(body);
      }
    } catch (e) {
      // Import errors are already user-facing; start failures retain their contextual prefix.
      setError(mode === "import" ? errorMessage(e) : "Could not start session: " + errorMessage(e));
      setBusy(false);
    }
  };

  return html`
    <${Dialog} id="new-session-dialog" labelledBy="new-session-title" lightDismiss=${!busy}
               onClose=${onClose}>
      <form id="new-session-form" onSubmit=${submit}>
        <div class="dialog-head">
          <div>
            <h2 id="new-session-title">New session</h2>
            <p>${mode === "import"
              ? "Register a conversation started outside kotgent and continue it here."
              : "Start a coding agent in a tmux-backed workspace."}</p>
          </div>
          <button id="new-session-close" class="icon-button" type="button"
                  aria-label="Close" onClick=${onClose}>×</button>
        </div>

        <div class="dialog-mode" role="group" aria-label="New session mode">
          <button id="new-session-mode-start" type="button" disabled=${busy}
                  aria-pressed=${mode === "start" ? "true" : "false"}
                  onClick=${() => switchMode("start")}>Start new</button>
          <button id="new-session-mode-import" type="button" disabled=${busy}
                  aria-pressed=${mode === "import" ? "true" : "false"}
                  onClick=${() => switchMode("import")}>Import existing</button>
        </div>

        <fieldset class="field agent-picker"
                  aria-describedby=${agent ? null : "new-session-agent-hint"}>
          <legend>Agent</legend>
          <div class="agent-options">
            ${AGENT_CHOICES
              .filter((choice) => mode !== "import" || choice.importable !== false)
              .map((choice) => html`
              <label key=${choice.value}
                     class=${"agent-option" + (choice.available ? "" : " agent-option-unavailable")}>
                <input id=${"session-agent-" + choice.value} type="radio" name="session-agent"
                       value=${choice.value} disabled=${!choice.available}
                       aria-required=${choice.available ? "true" : null}
                       ref=${choice.value === FIRST_AVAILABLE_AGENT ? agentRef : null}
                       checked=${agent === choice.value} onChange=${chooseAgent} />
                <span class="agent-option-content">
                  <span class=${"agent-icon agent-icon-" + choice.value} aria-hidden="true">
                    <svg viewBox=${choice.viewBox} focusable="false"><path d=${choice.icon} /></svg>
                  </span>
                  <span class="agent-option-name">
                    ${choice.name}${!choice.available && html`<small>Soon</small>`}
                  </span>
                </span>
              </label>
            `)}
          </div>
          ${!agent && html`
            <p id="new-session-agent-hint" class="field-hint">
              ${mode === "import" ? "Pick the agent that owns the session." : "Pick one to start a session."}
            </p>
          `}
        </fieldset>

        ${mode === "import" && html`
          <label class="field">
            <span>Provider session id</span>
            <input id="session-provider-id" type="text" required spellcheck="false" autocomplete="off"
                   placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" ref=${sessionIdRef}
                   value=${sessionId} onInput=${(e) => setSessionId(e.target.value)} />
            <small class="field-hint">
              claude: the ${"<id>"}.jsonl transcript name under ~/.claude/projects — codex: the id in
              the ${"rollout-<ts>-<id>"}.jsonl file name — junie: the directory name under
              ~/.junie/sessions, e.g. ${"session-<date>-<time>-<suffix>"}. Shell sessions have no
              provider id and cannot be imported.
            </small>
          </label>
        `}

        <div class="field">
          <label for="session-cwd">
            Working directory${mode === "import" ? html` <small>optional</small>` : ""}
          </label>
          <div class="path-autocomplete">
            <input id="session-cwd" type="text" required=${mode === "start"} spellcheck="false"
                   autocomplete="off"
                   role="combobox" aria-autocomplete="list"
                   aria-expanded=${cwdFocused && suggestions.length > 0 ? "true" : "false"}
                   aria-controls="session-cwd-options"
                   aria-activedescendant=${activeSuggestion >= 0
                     ? "session-cwd-option-" + activeSuggestion
                     : null}
                   placeholder=${mode === "import"
                     ? "found from the transcript when omitted"
                     : "/path/to/project"} ref=${cwdRef}
                   value=${cwd} onInput=${cwdInput} onKeyDown=${cwdKeyDown}
                   onFocus=${() => setCwdFocused(true)} onBlur=${() => setCwdFocused(false)} />
            ${cwdFocused && suggestions.length > 0 && html`
              <ul id="session-cwd-options" class="path-suggestions" role="listbox">
                ${suggestions.map((path, index) => html`
                  <li id=${"session-cwd-option-" + index} key=${path} role="option"
                      class=${"path-suggestion" + (index === activeSuggestion ? " active" : "")}
                      aria-selected=${index === activeSuggestion ? "true" : "false"}
                      title=${path}
                      onMouseDown=${(event) => event.preventDefault()}
                      onMouseEnter=${() => setActiveSuggestion(index)}
                      onClick=${() => chooseSuggestion(path)}>${path}</li>
                `)}
              </ul>
            `}
          </div>
        </div>

        <label class="field">
          <span>Name <small>optional</small></span>
          <input id="session-name" type="text" maxlength="80" placeholder="Feature or task"
                 value=${name} onInput=${(e) => setName(e.target.value)} />
        </label>

        <label class="field">
          <span>Tags <small>optional, comma-separated</small></span>
          <input id="session-tags" type="text" placeholder="backend, urgent"
                 value=${tags} onInput=${(e) => setTags(e.target.value)} />
        </label>

        ${mode === "start" && taskRef && html`
          <div class="field">
            <span>Task</span>
            <p id="new-session-task-ref" class="field-hint">
              This session will be linked to ${taskRef} — the link is written by the same request that
              starts it, so a launch that fails leaves no link behind.
            </p>
          </div>
        `}

        ${mode === "import" && html`
          <label class="field checkbox-field">
            <input id="session-register-only" type="checkbox" checked=${registerOnly}
                   onChange=${(e) => setRegisterOnly(e.target.checked)} />
            <span>
              Register only
              <small class="field-hint">
                Skip the automatic resume and leave the session resumable — e.g. while the conversation
                is still open in the terminal it was started in.
              </small>
            </span>
          </label>
        `}

        ${error && html`<p id="new-session-error" class="form-error" role="alert">${error}</p>`}

        <div class="dialog-actions">
          <button id="new-session-cancel" class="button button-quiet" type="button"
                  onClick=${onClose}>Cancel</button>
          <button id="new-session-submit" class="button button-primary" type="submit" disabled=${busy}>
            ${mode === "import"
              ? (busy ? "Importing…" : "Import session")
              : (busy ? "Starting…" : "Start session")}
          </button>
        </div>
      </form>
    <//>
  `;
}

export function UploadFilesDialog({ session, onClose }) {
  const [files, setFiles] = useState([]);
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState("");
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const inputRef = useRef(null);
  const requestRef = useRef(null);

  useEffect(() => {
    if (inputRef.current) inputRef.current.focus();
    return () => {
      if (requestRef.current) requestRef.current.abort();
    };
  }, []);

  const selectedFiles = (event) => {
    setFiles(Array.from(event.currentTarget.files || []));
    setProgress("");
    setResult(null);
    setError(null);
  };

  const submit = async (event) => {
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
        const file = files[index];
        setProgress("Uploading " + (index + 1) + " of " + files.length + ": " + file.name);
        try {
          await apiRequest(
            "/sessions/" + encodeURIComponent(session.id) + "/files?name=" +
              encodeURIComponent(file.name),
            { method: "POST", body: file, signal: controller.signal },
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

  return html`
    <${Dialog} id="upload-dialog" labelledBy="upload-title" lightDismiss=${!busy} onClose=${onClose}>
      <form id="upload-form" onSubmit=${submit}>
        <div class="dialog-head">
          <div>
            <h2 id="upload-title">Upload files</h2>
            <p>Send files from this device to the selected session.</p>
          </div>
          <button id="upload-close" class="icon-button" type="button"
                  aria-label="Close" onClick=${onClose}>×</button>
        </div>

        <p class="upload-destination">
          Current folder <code>${session.cwd}</code>
        </p>

        <label class="field">
          <span>Files <small>up to 100 MiB each</small></span>
          <input id="upload-files" class="file-input" type="file" multiple ref=${inputRef}
                 disabled=${busy} onChange=${selectedFiles} />
          <small class="field-hint">
            Existing files are never replaced. Rename a file first if its name is already present.
          </small>
        </label>

        ${progress && html`<p class="upload-progress" role="status">${progress}</p>`}
        ${result && html`<p class="upload-result" role="status">${result}</p>`}
        ${error && html`<p id="upload-error" class="form-error upload-error" role="alert">${error}</p>`}

        <div class="dialog-actions">
          <button id="upload-cancel" class="button button-quiet" type="button"
                  onClick=${onClose}>${busy ? "Cancel upload" : "Close"}</button>
          <button id="upload-submit" class="button button-primary" type="submit"
                  disabled=${busy || files.length === 0}>
            ${busy ? "Uploading…" : files.length > 1 ? "Upload " + files.length + " files" : "Upload file"}
          </button>
        </div>
      </form>
    <//>
  `;
}

// Null means the task snapshot has not loaded; do not claim the backlog is empty.
function taskKeptSentence(count) {
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

export function DeleteProjectDialog({ project, taskCount = null, onDelete, onClose }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const cancelRef = useRef(null);

  // Default focus stays on the non-destructive action.
  useEffect(() => { if (cancelRef.current) cancelRef.current.focus(); }, []);

  const submit = async (event) => {
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

  return html`
    <${Dialog} id="delete-project-dialog" labelledBy="delete-project-title" lightDismiss=${!busy}
               onClose=${onClose}>
      <form id="delete-project-form" onSubmit=${submit}>
        <div class="dialog-head">
          <div>
            <h2 id="delete-project-title">Delete project</h2>
            <p>Takes it out of every selector. Nothing on disk is touched.</p>
          </div>
          <button id="delete-project-close" class="icon-button" type="button"
                  aria-label="Close" onClick=${onClose}>×</button>
        </div>

        <p class="dialog-subject">
          <strong id="delete-project-name">${project.name || project.id}</strong>
          <code id="delete-project-path">${project.path || "last-seen directory unknown"}</code>
        </p>

        <ul id="delete-project-facts" class="dialog-facts">
          <li>${taskKeptSentence(taskCount)}</li>
          <li>
            Its <code>.kotgent.json</code> stays on disk. This writes nothing into the directory, and
            a project whose directory is already gone is deleted just the same.
          </li>
          <li>
            Restore brings the project and all of that back, exactly as it is now. Adopting the same
            directory again from New project brings it back too, with the name in the file and this
            checkout's path.
          </li>
        </ul>

        ${error && html`<p id="delete-project-error" class="form-error" role="alert">${error}</p>`}

        <div class="dialog-actions">
          <button id="delete-project-cancel" class="button button-quiet" type="button"
                  ref=${cancelRef} onClick=${onClose}>Cancel</button>
          <button id="delete-project-submit" class="button button-danger" type="submit"
                  disabled=${busy}>${busy ? "Deleting…" : "Delete project"}</button>
        </div>
      </form>
    <//>
  `;
}

export function RestoreProjectDialog({ onRestore, onClose }) {
  const [state, setState] = useState({ status: "loading" });
  const [busyId, setBusyId] = useState(null);
  const [error, setError] = useState(null);
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

  const restore = async (project) => {
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

  return html`
    <${Dialog} id="restore-project-dialog" labelledBy="restore-project-title"
               lightDismiss=${!busyId} onClose=${onClose}>
      <div id="restore-project-form">
        <div class="dialog-head">
          <div>
            <h2 id="restore-project-title">Restore a deleted project</h2>
            <p>Clears the delete mark. The backlog comes back with it.</p>
          </div>
          <button id="restore-project-close" class="icon-button" type="button"
                  aria-label="Close" onClick=${onClose}>×</button>
        </div>

        ${restoreProjectBody(state, busyId, restore, load)}
        ${error && html`<p id="restore-project-error" class="form-error" role="alert">${error}</p>`}

        <div class="dialog-actions">
          <button id="restore-project-cancel" class="button button-quiet" type="button"
                  onClick=${onClose}>Close</button>
        </div>
      </div>
    <//>
  `;
}

function restoreProjectBody(state, busyId, restore, reload) {
  if (state.status === "loading") {
    return html`<p id="restore-project-status" class="dialog-status">Reading deleted projects…</p>`;
  }
  if (state.status === "error") {
    return html`
      <p id="restore-project-load-error" class="form-error" role="alert">
        Could not read the deleted projects: ${state.message}
      </p>
      <button id="restore-project-retry" class="button" type="button" onClick=${reload}>Try again</button>
    `;
  }
  if (state.projects.length === 0) {
    return html`
      <p id="restore-project-empty" class="dialog-empty">
        No deleted projects. Only a project delete puts one here, so there is nothing to bring back.
      </p>
    `;
  }
  return html`
    <ul id="restore-project-list" class="dialog-list">
      ${state.projects.map((project) => html`
        <li key=${project.id}>
          <button class="dialog-list-row" type="button" data-id=${project.id}
                  disabled=${!!busyId} onClick=${() => restore(project)}>
            <span class="dialog-list-name">${project.name || project.id}</span>
            <span class="dialog-list-sub">${project.path || "last-seen directory unknown"}</span>
            <span class="dialog-list-action">
              ${busyId === project.id ? "Restoring…" : "Restore"}
            </span>
          </button>
        </li>
      `)}
    </ul>
  `;
}

function groupingPreview(draft, sessions) {
  if (!draft.basePath) return "No base path — sessions are listed flat.";
  const base = normalizePath(draft.basePath);
  const baseLabel = basename(base) || base;
  const sample = sessions.find((s) => segmentsUnder(draft.basePath, s.cwd) !== null);
  if (!sample) {
    const placeholders = Array.from({ length: draft.groupingLevel }, (_, i) => "<dir" + (i + 1) + ">");
    return placeholders.length > 0
      ? "Tree below " + base + ": " + placeholders.join(" › ")
      : base + " → " + baseLabel + " (one folder for all sessions below the base)";
  }
  const segments = segmentsUnder(base, sample.cwd);
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

export function PreferencesDialog({ prefs, sessions, onSave, onClose }) {
  const [basePath, setBasePath] = useState(prefs.basePath);
  const [level, setLevel] = useState(String(prefs.groupingLevel));
  const [fontSize, setFontSize] = useState(String(prefs.terminalFontSize));
  const [unicode, setUnicode] = useState(prefs.terminalUnicode);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const inputRef = useRef(null);

  useEffect(() => { if (inputRef.current) inputRef.current.focus(); }, []);

  const submit = async (event) => {
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

  return html`
    <${Dialog} id="prefs-dialog" labelledBy="prefs-title" lightDismiss=${!busy} onClose=${onClose}>
      <form id="prefs-form" onSubmit=${submit}>
        <div class="dialog-head">
          <div>
            <h2 id="prefs-title">Preferences</h2>
            <p>Base path and tree depth are shared by every browser connected to this daemon.</p>
          </div>
          <button id="prefs-close" class="icon-button" type="button"
                  aria-label="Close" onClick=${onClose}>×</button>
        </div>

        <label class="field">
          <span>Base path</span>
          <input id="prefs-base-path" type="text" spellcheck="false" placeholder="/Users/you/dev"
                 ref=${inputRef} value=${basePath} onInput=${(e) => setBasePath(e.target.value)} />
          <small class="field-hint">
            Absolute path. Sessions below it form a folder tree, and new sessions default to it. Leave
            empty for one flat list.
          </small>
        </label>

        <label class="field">
          <span>Tree depth <small>maximum visible folders below the base path</small></span>
          <select id="prefs-grouping-level" value=${level} onChange=${(e) => setLevel(e.target.value)}>
            ${LEVEL_LABELS.slice(0, MAX_GROUPING_LEVEL + 1).map((label, value) => html`
              <option key=${value} value=${String(value)}>${label}</option>
            `)}
          </select>
          <small id="prefs-grouping-preview" class="field-hint">${preview}</small>
        </label>

        <label class="field">
          <span>Terminal font size</span>
          <select id="prefs-terminal-font-size" value=${fontSize}
                  onChange=${(e) => setFontSize(e.target.value)}>
            ${TERMINAL_FONT_SIZES.map((size) => html`
              <option key=${size} value=${String(size)}>
                ${(TERMINAL_FONT_LABELS.get(size) || "Custom") + " — " + size + " px"}
              </option>
            `)}
          </select>
          <small class="field-hint">
            Stored only in this browser and applied immediately to an attached terminal after you save.
          </small>
        </label>

        <label class="field">
          <span>Terminal unicode <small>how wide a character is measured</small></span>
          <select id="prefs-terminal-unicode" value=${unicode}
                  onChange=${(e) => setUnicode(e.target.value)}>
            ${TERMINAL_UNICODE_MODES.map((mode) => html`
              <option key=${mode.value} value=${mode.value}>${mode.label}</option>
            `)}
          </select>
          <small id="prefs-terminal-unicode-hint" class="field-hint">
            ${terminalUnicodeMode(unicode).hint} Stored only in this browser. A change applies to what the
            pane draws next, not to the screen already on it.
          </small>
        </label>

        ${error && html`<p id="prefs-error" class="form-error" role="alert">${error}</p>`}

        <div class="dialog-actions">
          <button id="prefs-cancel" class="button button-quiet" type="button"
                  onClick=${onClose}>Cancel</button>
          <button id="prefs-submit" class="button button-primary" type="submit" disabled=${busy}>
            ${busy ? "Saving…" : "Save"}
          </button>
        </div>
      </form>
    <//>
  `;
}

/* The QR contains the credential-free install URL so Safari cannot spend the installed PWA's ticket. */
export function PhoneDialog({ onClose }) {
  const [state, setState] = useState({ status: "loading" });

  const issue = useCallback(async () => {
    setState({ status: "loading" });
    try {
      const ticket = await apiRequest(AUTH_TICKET_PATH, { method: "POST" });
      setState({ status: "ready", ticket: ticket });
    } catch (e) {
      setState({ status: "error", message: errorMessage(e) });
    }
  }, []);

  useEffect(() => { issue(); }, [issue]);

  return html`
    <${Dialog} id="phone-dialog" labelledBy="phone-title" onClose=${onClose}>
      <div id="phone-form">
        <div class="dialog-head">
          <div>
            <h2 id="phone-title">Sign in from your phone</h2>
            <p>Scan to open kotgent on another device.</p>
          </div>
          <button id="phone-close" class="icon-button" type="button"
                  aria-label="Close" onClick=${onClose}>×</button>
        </div>
        ${phoneBody(state, issue, onClose)}
      </div>
    <//>
  `;
}

/** Display-only grouping is safe because normalizeTicketCode strips whitespace. */
function groupCode(code) {
  const value = String(code || "");
  if (value.length < 6 || value.length % 2 !== 0) return value;
  const half = value.length / 2;
  return value.slice(0, half) + " " + value.slice(half);
}

/** Strip the one-shot credential fragment from the public install URL. */
function installUrl(ticketUrl) {
  return String(ticketUrl || "").split("#", 1)[0];
}

function phoneBody(state, issue, onClose) {
  if (state.status === "loading") {
    return html`<p id="phone-status" class="phone-status">Minting a one-time sign-in code…</p>`;
  }
  if (state.status === "error") {
    return html`
      <p id="phone-error" class="form-error" role="alert">Could not mint a sign-in code: ${state.message}</p>
      <div class="dialog-actions">
        <button class="button button-quiet" type="button" onClick=${onClose}>Close</button>
        <button class="button button-primary" type="button" onClick=${issue}>Try again</button>
      </div>
    `;
  }

  const ticket = state.ticket || {};
  if (!ticket.publicUrl) return phoneSetup(onClose);
  const publicInstallUrl = installUrl(ticket.publicUrl);

  return html`
    <div id="phone-qr" class="phone-qr"
         dangerouslySetInnerHTML=${{ __html: qrSvg(publicInstallUrl) }}></div>
    <p class="phone-url"><code>${publicInstallUrl}</code></p>
    <p class="phone-code-hint">
      Scan this credential-free page in Safari, add Kotgent to the home screen, then launch the installed app.
    </p>
    ${ticket.ticket && html`
      <p id="phone-code" class="phone-code">${groupCode(ticket.ticket)}</p>
      <p class="phone-code-hint">
        Type this code into the installed app. It has its own cookie jar, and the QR deliberately does not
        spend the code in Safari.
      </p>`}
    <p class="phone-warn" role="note">
      The code is one-time · expires in 5 minutes · grants full terminal access. Refresh it if you did not
      just use it yourself.
    </p>
    <div class="dialog-actions">
      <button class="button button-quiet" type="button" onClick=${onClose}>Close</button>
      <button id="phone-refresh" class="button button-primary" type="button" onClick=${issue}>Refresh</button>
    </div>
  `;
}

function phoneSetup(onClose) {
  const port = window.location.port;
  const ingress = "  - hostname: <your-tunnel-host>\n    service: http://127.0.0.1:" + port;
  return html`
    <p id="phone-setup" class="phone-note">
      No public URL is configured, so there is nothing to point a phone at yet. Phone access runs over a
      Cloudflare tunnel to this daemon — a one-time setup:
    </p>
    <ol class="phone-steps">
      <li>
        Add an ingress rule to <code>~/.cloudflared/config.yml</code>:
        <pre class="help-code">${ingress}</pre>
      </li>
      <li>
        Put <strong>Cloudflare Access</strong> in front of the host and scope the policy to your own
        email only. This host fronts a terminal that can run anything on your Mac, so a loose policy is
        more dangerous here than anywhere else — do not publish it without the identity gate.
      </li>
      <li>
        Tell kotgent its public origin:
        <pre class="help-code">kotgent config set public-url https://your-tunnel-host</pre>
      </li>
      <li>Restart the daemon, then reopen this dialog to get a QR code.</li>
    </ol>
    <div class="dialog-actions">
      <button class="button button-primary" type="button" onClick=${onClose}>Done</button>
    </div>
  `;
}

const CLI_HELP = `kotgent list                  list sessions
kotgent start <agent> [cwd]   start a session (claude | codex | junie | shell)
kotgent import <agent> <id>   register a session started outside kotgent, then resume it
kotgent attach <id>           attach a raw terminal
kotgent interrupt <id>        send Ctrl-C
kotgent stop <id>             stop a session
kotgent resume <id>           resume a stopped/crashed/resumable session
kotgent daemon [--port N]     run the control plane`;

const STATES = [
  ["running", "badge-running", "The agent is working on a turn."],
  ["ready", "badge-ready", "Alive and idle — it finished its turn and is waiting for your next prompt."],
  ["needs approval", "badge-attention",
    "Blocked asking permission for an action. Answer it in the terminal; the approval clears by itself " +
    "as soon as the agent acts again."],
  ["needs answer", "badge-attention",
    "Blocked on a question. Modeled but never produced by the current Claude adapter — interactive " +
    "Claude gives no \"waiting for an answer\" signal."],
  ["stopped", "badge-dead", "The agent process exited cleanly (this is what Stop leaves behind)."],
  ["crashed", "badge-crashed", "The agent process exited abnormally or its pane was lost."],
  ["resumable", "badge-resumable", "Dead, but the conversation transcript survives — Resume can revive it."],
];

const CONTROLS = [
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
  ["Interrupt",
    "Sends Ctrl-C to the pane and marks the session ready, clearing any pending approval. Use it for a " +
    "turn that is stuck or running away. The agent stays alive."],
  ["Stop",
    "Kills the tmux session, so the agent process ends and the state becomes stopped. The conversation " +
    "transcript is kept, so this is reversible with Resume."],
  ["Resume",
    "Relaunches the agent against the saved transcript in a fresh tmux session and puts it back to " +
    "ready. It is refused while the provider's session id has not been captured yet — that id is what " +
    "a resume is addressed to."],
  ["Detach",
    "Closes only your terminal client. The agent keeps working; when the last viewer leaves, the daemon " +
    "drops its upstream tmux attach too."],
];

export function HelpDialog({ onClose }) {
  const bodyRef = useRef(null);
  useEffect(() => { if (bodyRef.current) bodyRef.current.scrollTop = 0; }, []);

  return html`
    <${Dialog} id="help-dialog" labelledBy="help-title" onClose=${onClose}>
      <div id="help-form">
        <div class="dialog-head">
          <div>
            <h2 id="help-title">How kotgent works</h2>
            <p>Sessions, states, and what each control does.</p>
          </div>
          <button id="help-close" class="icon-button" type="button"
                  aria-label="Close" onClick=${onClose}>×</button>
        </div>

        <div id="help-body" ref=${bodyRef}>
          <section class="help-section">
            <h3>Sessions</h3>
            <p>
              A session is one coding agent running inside its own <code>tmux</code> session
              (<code>${"kt-<id>"}</code>) in a working directory you pick. The daemon owns it — not this
              page. Closing the tab, detaching, or restarting the daemon does not stop the agent.
            </p>
            <p>
              What you see in the right pane is that real tmux pane. The daemon holds exactly one
              <code>tmux attach</code> per session and fans it out to every viewer, so a browser, an IDE
              and <code>kotgent attach</code> all watch and type into the same terminal.
            </p>
            <p>
              State is never stored directly: the agent reports events through hooks, they are appended
              to a per-session log, and the state you see is replayed from that log. That is why sessions
              survive a daemon restart.
            </p>
          </section>

          <section id="help-tmux" class="help-section">
            <h3>tmux, scrolling, and copying</h3>
            <p>
              Kotgent uses tmux's default prefix: press <kbd>Ctrl</kbd>+<kbd>B</kbd>, release both,
              then press the command key. Your <code>~/.tmux.conf</code> is not loaded on Kotgent's
              dedicated tmux server, so custom prefixes and bindings do not apply here.
            </p>
            <dl class="help-list">
              <dt><kbd>Ctrl</kbd>+<kbd>B</kbd>, then <kbd>[</kbd></dt>
              <dd>
                Enter tmux copy mode to browse the pane's 10,000-line history. Use the arrow,
                <kbd>Page Up</kbd>, and <kbd>Page Down</kbd> keys; the mouse wheel enters and scrolls
                this mode automatically.
              </dd>
              <dt><kbd>Esc</kbd> or <kbd>q</kbd></dt>
              <dd>
                Leave copy mode and return keyboard input to the agent. Scrolling all the way back to
                the bottom also exits it.
              </dd>
              <dt><kbd>Option</kbd>-drag, then <kbd>Cmd</kbd>+<kbd>C</kbd></dt>
              <dd>
                Select terminal text and copy it to the browser clipboard on macOS. Hold Option while
                dragging so xterm selects the text instead of sending the drag to tmux or the agent.
              </dd>
              <dt><kbd>Shift</kbd>-drag, then <kbd>Ctrl</kbd>+<kbd>C</kbd></dt>
              <dd>The equivalent browser-copy gesture on other platforms.</dd>
            </dl>
            <p class="help-note">
              Browser selection and tmux copy mode are separate: copying in the browser does not use
              tmux's paste buffer. Copy mode belongs to the pane and is shared by every viewer, so if
              typing appears to be ignored after someone scrolls, leave copy mode or return to the bottom.
              To leave, use the palette's Detach command — the ⋯ button in the terminal header, or
              <kbd>⌘</kbd>+<kbd>K</kbd>, then <kbd>E</kbd> — instead of tmux's detach binding; it closes
              only this viewer.
            </p>
          </section>

          <section class="help-section">
            <h3>States</h3>
            <dl class="help-list">
              ${STATES.map(([label, cls, description]) => html`
                <dt key=${label}><span class=${"badge " + cls}>${label}</span></dt>
                <dd key=${label + "-d"}>${description}</dd>
              `)}
            </dl>
            <p class="help-note">
              The first four are <em>alive</em> (a process is running in tmux), the last three are
              <em>dead</em>. The two "needs" states are the ones counted as needing attention.
            </p>
          </section>

          <section class="help-section">
            <h3>Controls</h3>
            <dl class="help-list">
              ${CONTROLS.map(([label, description]) => html`
                <dt key=${label}>${label}</dt>
                <dd key=${label + "-d"}>${description}</dd>
              `)}
            </dl>
          </section>

          <section class="help-section">
            <h3>The sidebar</h3>
            <p>
              "Needs attention" counts the sessions blocked on you and repeats them at the top so nothing
              is missed. The blue pill is the number of events appended since you last read the session,
              and the badge is its current state. With a base path set in Preferences, rows are grouped
              by working directory; anything outside that base path is grouped under its own path at the
              end. Click a group's header to collapse it — the collapsed groups are remembered in this
              browser, and one keeps its dot while it hides a session that needs attention.
            </p>
          </section>

          <section class="help-section">
            <h3>Access</h3>
            <p>
              The daemon listens on <code>127.0.0.1</code>, and this page signs in with a session cookie
              rather than a token in the URL. Run <code>kotgent web</code> to open it: that mints a
              one-time ticket, exchanges it for an <code>HttpOnly</code> cookie, and leaves nothing secret
              in the address bar. The master token (<code>~/.kotgent/token</code>) stays the machine's
              key — the hooks and the CLI use it. The cookie is a key to your agents, so treat this
              browser profile as you would an SSH session.
            </p>
          </section>

          <section class="help-section">
            <h3>The same thing from a terminal</h3>
            <pre class="help-code">${CLI_HELP}</pre>
          </section>
        </div>

        <div class="dialog-actions">
          <button id="help-done" class="button button-primary" type="button" onClick=${onClose}>Done</button>
        </div>
      </div>
    <//>
  `;
}
