/*
 * The modal screens: New session, Upload files, Preferences, Phone access, Help.
 *
 * Each one is a native `<dialog>`, so Esc, focus trapping and the backdrop's paint come from the
 * platform. The [Dialog] wrapper below is the only place that talks to the imperative dialog API:
 * mounting the component opens it, and the native `close` event reports back so the parent can unmount
 * it. Open/closed is therefore ordinary Preact state — there is no second source of truth.
 *
 * What the platform does NOT give is a light dismiss: `showModal()` paints a backdrop but a press on it
 * closes nothing, so the wrapper adds the two gestures a pointer-only device has — a press outside the
 * panel, and a downward swipe of a TOUCH pointer off the panel's GRABBER. The grabber is the only handle
 * because it is the one strip of a dialog that never scrolls anything: `dialog:modal` makes an overflowing
 * `<dialog>` its own scroller, and `touch-action: none` on the head would turn the natural "pan the sheet
 * by its title" gesture into a dead zone. The two have different reaches, deliberately: a press outside
 * works for any pointer that can click, while the swipe needs `pointerType === "touch"` and a drawn
 * handle — and that handle is drawn wherever a COARSE pointer exists, not merely below the phone
 * breakpoint, because a tablet is wider than it and has no Esc either.
 *
 * Every rule below fails toward KEEPING the dialog, because what it holds is unsaved and local:
 * a swipe is claimed only once it is dominantly downward, a `pointercancel` — a gesture the platform took
 * away — springs back instead of committing, a flick counts only while its speed sample is fresh, and a
 * screen with work in flight opts out entirely (`lightDismiss`). Esc, the ×, and Cancel are unchanged.
 * Esc is not a uniform escape hatch to compare against: New session deliberately spends the first one on
 * its open cwd-completion list (`cwdKeyDown`), so the keyboard has a layer these gestures do not.
 *
 * htm is not an HTML parser and does not decode entities, so any literal `<` in the copy below is
 * interpolated as a JS string (`${"kt-<id>"}`) rather than written as `&lt;`.
 */

import { html } from "htm/preact";
import { useCallback, useEffect, useRef, useState } from "preact/hooks";
import { AGENT_CHOICES, FIRST_AVAILABLE_AGENT } from "../lib/agents.js";
import { basename, normalizePath, segmentsUnder } from "../lib/paths.js";
import { MAX_GROUPING_LEVEL, TERMINAL_FONT_SIZES, sanitizePrefs } from "../lib/prefs.js";
import { TERMINAL_UNICODE_MODES, terminalUnicodeMode } from "../lib/unicode.js";
import { apiRequest, errorMessage } from "../lib/api.js";
import { qrSvg } from "../lib/qr.js";

/** A drag this short is still a tap, so the press stays with the button or field underneath it. */
const SWIPE_SLOP_PX = 8;
/** Travel that dismisses on release — about a third of a phone dialog's height. */
const SWIPE_DISMISS_PX = 96;
/** A flick dismisses earlier: this much travel at this speed (px/ms) beats the distance rule. */
const SWIPE_FLICK_PX = 32;
const SWIPE_FLICK_VELOCITY = 0.5;
/**
 * How fresh a speed sample has to be to count as motion, matching `installSwipeScroll`'s measured
 * handoff. A stationary contact emits no `pointermove` at all, so without this the LAST sample stands
 * for however long the finger then rested: a quick 40px pull, two seconds of second-guessing, and a
 * lift would still read as a flick and throw the draft away. It cuts both ways — a sample that spans
 * a dwell is not a measurement of the flick that ended it either, so it counts as zero rather than as
 * a slow drag. Both directions therefore fail toward keeping the dialog.
 */
const SWIPE_FLICK_HANDOFF_MS = 90;

/**
 * The spring-back is set as an inline style, which outranks the stylesheet, so the motion preference
 * cannot be honoured from a media query the way `#sidebar`'s is — it is asked here instead.
 */
function prefersReducedMotion() {
  return typeof window !== "undefined" && typeof window.matchMedia === "function" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

export function Dialog({ id, labelledBy, lightDismiss = true, onClose, children }) {
  const ref = useRef(null);
  // The one press that may authorize a light dismiss: `{ pointerId, released }`, or null.
  //
  // A dismiss is a POSITIVELY COMPLETED down → up → click transaction by ONE pointer, and each part
  // of that is load-bearing. A set of votes is worse than a flag: a press that answers with no
  // `click` at all (a secondary button reports `contextmenu`/`auxclick`) stays armed until a later
  // drag OUT of the panel spends it — the very false close this pairing exists to prevent. Hence
  // `event.button === 0` — the primary BUTTON, which a touch contact and a pen TIP both report while a
  // barrel button or an eraser does not — plus `event.isPrimary`, because on a touchscreen a SECOND
  // finger also reports button 0 and only the primary pointer produces a click at all. Then one slot
  // that each later press replaces, and `released`, which is what stops a pointer that is still down
  // from authorizing a DIFFERENT pointer's click. The cost is a false negative worth one tap: a second
  // contact landing inside disarms a pending backdrop press.
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

  // A press on the backdrop is reported on the <dialog> itself, so the target alone is nearly enough —
  // but a drag that STARTED on the panel (selecting a path, releasing a slider) also ends as a click on
  // the dialog, and so does a click that a native <select> popup let through. The geometry is therefore
  // checked against the panel's own box, and both halves of the press must land outside it.
  const outside = (event) => {
    const el = ref.current;
    if (!el || event.target !== el) return false;
    const rect = el.getBoundingClientRect();
    return event.clientX < rect.left || event.clientX > rect.right ||
      event.clientY < rect.top || event.clientY > rect.bottom;
  };

  /** Give the panel back to the layout, animated unless the operator asked for less motion. */
  const springBack = (el, pointerId) => {
    if (el.hasPointerCapture(pointerId)) el.releasePointerCapture(pointerId);
    el.style.transition = prefersReducedMotion() ? "none" : "transform 160ms ease-out";
    el.style.transform = "";
  };

  const pointerDown = (event) => {
    // A press made while the screen is busy arms NOTHING. Gating only the closing was not enough: a
    // batch can finish between a backdrop press and its click, and the arm would then be spent on the
    // freshly rendered result. The slot is cleared by the click that follows a press, so nothing armed
    // before a busy period can outlive it either.
    if (!lightDismiss) return;
    const isOutside = outside(event);
    // `isPrimary` as well as the primary BUTTON: on a touchscreen every contact reports `button === 0`,
    // but only the primary one produces a `click` at all — so a second finger could otherwise arm a
    // press that the FIRST finger's click then spent, closing from a gesture that began on the panel.
    if (event.isPrimary && event.button === 0) {
      outsidePress.current = isOutside ? { pointerId: event.pointerId, released: false } : null;
    }
    if (isOutside || event.pointerType !== "touch") return;
    // One finger owns the swipe. A second contact must not restart it under a new id, or the release
    // of the first would find a drag it does not own and leave the panel translated.
    if (dragRef.current) return;
    // Only the grabber starts a swipe. Not the head: an overflowing `<dialog>` is its own scroller, so
    // the head is a scroll surface, and the `touch-action: none` this gesture needs would kill the pan.
    // Not the body either — it scrolls (help, the palette's list) or holds fields a finger must reach.
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
    // The screen can turn busy under a gesture that is already owned. Hand the panel back at once
    // instead of letting a finger drag the one screen that must stay visible off its own progress —
    // a contact that then rests without lifting would park it there with no release to spring it.
    if (!lightDismiss) {
      dragRef.current = null;
      if (drag.dragging) springBack(el, event.pointerId);
      return;
    }
    const travel = event.clientY - drag.startY;
    if (!drag.dragging) {
      // Claimed only once the finger has clearly moved DOWN and moved down MORE than sideways, and only
      // then captured: an uncaptured pointer keeps a tap underneath working as an ordinary click, and a
      // sweep across the sheet is not a dismissal just because it drifted 8px down. The axis test is a
      // LOCK, not a running condition — a claimed gesture stays claimed, the way a native sheet does.
      if (travel < SWIPE_SLOP_PX || travel <= Math.abs(event.clientX - drag.startX)) {
        // Keep the speed baseline current even while the gesture is unclaimed: leaving it at the press
        // would divide the first sample after the claim by the whole press, dwell included.
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
    // Only the pointer that armed the press can complete or withdraw it: a release outside completes
    // it, a release INSIDE is a drag onto the panel and withdraws it, and another pointer's release
    // is neither. Without this, a finger merely HOLDING the backdrop authorized a mouse's click.
    const press = outsidePress.current;
    if (press && press.pointerId === event.pointerId) {
      if (outside(event)) press.released = true;
      else outsidePress.current = null;
    }
    const drag = dragRef.current;
    const el = ref.current;
    // Identity is checked BEFORE the ref is cleared: another finger's release is not this drag's end,
    // and clearing first would abandon a live swipe with the panel stuck under its transform.
    if (!drag || !el || event.pointerId !== drag.pointerId) return;
    dragRef.current = null;
    if (!drag.dragging) return;
    // A screen can become busy under a gesture that is already owned (a second pointer pressing
    // Upload), so the opt-out is re-read HERE, where the dismissal is actually decided.
    if (!lightDismiss) {
      springBack(el, event.pointerId);
      return;
    }
    // The release carries a position of its own and a browser need not precede it with a
    // `pointermove`, so it is folded in as the final sample: a swipe that reverses and lifts must not
    // dismiss on the reading from before the reversal. A release that moved nowhere leaves the last
    // sample standing, aged; anything else is measured from the release itself.
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

  // A cancel is the platform TAKING the gesture away (a system edge pull, palm rejection, an incoming
  // call), never a release. It shares nothing with `pointerUp` but the cleanup: evaluating distance
  // here would close a dialog on an interruption the operator did not perform.
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
    // Pointer Events makes `click` a PointerEvent, so where the platform names the pointer that
    // produced it, it has to be the one that pressed. Where it does not, `released` above is the
    // whole guarantee — hence that flag rather than this check being the load-bearing half.
    if (typeof event.pointerId === "number" && event.pointerId !== press.pointerId) return;
    if (ref.current) ref.current.close();
  };

  return html`
    <dialog id=${id} ref=${ref} aria-labelledby=${labelledBy}
            onPointerDown=${pointerDown} onPointerMove=${pointerMove}
            onPointerUp=${pointerUp} onPointerCancel=${pointerCancel} onClick=${click}>
      ${/* The swipe affordance and its only handle — including for the palette, which draws no
            `.dialog-head`. It exists wherever a coarse pointer does, which is where the gesture is. */ ""}
      <div class="dialog-grabber" aria-hidden="true"></div>
      ${children}
    </dialog>
  `;
}

// --- New session -----------------------------------------------------------------------------------

const DIRECTORY_COMPLETION_DELAY_MS = 150;

/**
 * One form, two modes. "Start new" launches an agent (`onStart`); "Import existing" registers a
 * conversation started OUTSIDE kotgent by its provider session id (`onImport(body, registerOnly)` →
 * `POST /sessions/import`). Import needs no working directory — the daemon discovers it from the
 * provider's own transcript — so the cwd field is `required` only in start mode. Import errors
 * (400/409) are shown verbatim in the form's error line: the daemon's text already names the fix
 * (a duplicate names the existing kotgent session, a claude cwd mismatch names the workaround).
 */
export function NewSessionDialog({
  initialCwd, initialMode = "start", initialAgent = "", basePath, onStart, onImport, onClose,
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

  // There is deliberately no general default agent, so the picker — not the prefilled path — is the
  // first answer this dialog normally needs. The free-terminal command is the one explicit preselection;
  // for it, the working directory is the first unanswered field and receives focus instead.
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
    setCompletionQuery(null); // selecting is not another typing event: keep the just-closed list closed
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
    // Each mode re-enters with its own cwd default. A prefilled start-mode cwd (a group's "+") must
    // not ride into import mode: import sends any non-empty cwd as an explicit override of the
    // daemon's transcript discovery, and the codex probe ignores cwd entirely — the group's
    // directory, not the rollout's recorded one, would be stored as the session's project dir for
    // good. A cwd meant to override discovery is typed in import mode itself.
    setCwd(next === "import" ? "" : (initialCwd || ""));
    setCompletionQuery(null);
    setSuggestions([]);
    setActiveSuggestion(-1);
  };

  const submit = async (event) => {
    event.preventDefault();
    if (!agent) {
      // The one requirement the browser cannot report here. A `disabled` submit would swallow both the
      // click and Enter with no feedback at all, and native `required` would anchor its bubble on a
      // radio that is `opacity: 0` — so the missing choice is reported through the dialog's own
      // `role="alert"` line, and focus goes back to the picker.
      setError(mode === "import"
        ? "Pick the agent that owns the session you are importing."
        : "Pick an agent to start a session.");
      if (agentRef.current) agentRef.current.focus();
      return;
    }
    if (mode === "import" && !sessionId.trim()) {
      // Native `required` catches an empty field but not a whitespace-only one.
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
        await onStart({ agent: agent, cwd: cwd.trim(), name: name.trim() || null, tags: tagList });
      }
    } catch (e) {
      // The import route's own 400/409 text is already user-facing ("cannot import session: …" plus
      // the fix), so it is shown verbatim; the start path keeps its established prefix. Reached only
      // while THIS form is still the mounted one: app.js rethrows a completion's failure into the
      // form only when the submitted dialog is still current, and routes it to the status line
      // otherwise — a setError after unmount would be a silent no-op and the error would vanish.
      setError(mode === "import" ? errorMessage(e) : "Could not start session: " + errorMessage(e));
      setBusy(false);
    }
  };

  return html`
    ${/* Every button is already disabled while the launch is in flight, for the same reason the light
          dismiss is: a close here unmounts a fully typed draft — agent, cwd, name, tags — while the
          request completes invisibly. Esc, the ×, and Cancel still work; those are deliberate. */ ""}
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

// --- File upload -----------------------------------------------------------------------------------

/**
 * Pick one or more files from this browser/device and upload them directly into the selected session's
 * working directory. The destination path is display-only: the request carries only the session id and a
 * leaf filename, and the daemon resolves the row's current cwd itself.
 */
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
      // A second press with the same selection would hit conflicts for every successful file. Clear the
      // native FileList and require an explicit new pick; the result above remains visible.
      setFiles([]);
      if (inputRef.current) inputRef.current.value = "";
    } finally {
      if (requestRef.current === controller) requestRef.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  };

  return html`
    ${/* The one screen that opts out of light dismiss, and only while it is working: unmounting aborts
          the in-flight request, and the loop then returns before it can name which files landed and
          which did not. A backdrop press or a swipe is exactly the accident that costs that report;
          Esc, the ×, and Cancel stay, because those are the operator saying it on purpose. */ ""}
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

// --- Preferences -----------------------------------------------------------------------------------

/** Describe what the draft settings would do, using a real session cwd when one is available. */
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

        ${/* Off by default, and off means nothing is downloaded: the width tables live in addons this
              browser fetches only once a mode selects one. Their table has to agree with the one tmux
              laid the pane out with, so the choice is deliberately the operator's. */ ""}
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
                  disabled=${busy} onClick=${onClose}>Cancel</button>
          <button id="prefs-submit" class="button button-primary" type="submit" disabled=${busy}>
            ${busy ? "Saving…" : "Save"}
          </button>
        </div>
      </form>
    <//>
  `;
}

// --- Phone access ----------------------------------------------------------------------------------

/**
 * Sign in on a second device. Minting a ticket here is the same `POST /auth/ticket` the CLI's
 * `kotgent web` uses; the difference is the QR, drawn over the returned `publicUrl` WITHOUT its credential
 * fragment so Safari can install the PWA without spending the code that PWA needs. When no public URL is
 * configured the daemon returns `publicUrl: null` — there is nothing a phone could reach, so the dialog
 * explains how to set the tunnel up instead of drawing a dead QR.
 *
 * The ticket is a full-access, one-time credential with a short life. That is stated plainly under the
 * code, and "Refresh" mints a new one (each minting leaves the previous ticket to expire on its own).
 */
export function PhoneDialog({ onClose }) {
  const [state, setState] = useState({ status: "loading" });

  const issue = useCallback(async () => {
    setState({ status: "loading" });
    try {
      const ticket = await apiRequest("/auth/ticket", { method: "POST" });
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

/**
 * Split a login code in the middle (`A1B2C3D4` → `A1B2 C3D4`) — the way a human reads eight symbols off a
 * screen anyway. The daemon strips whitespace before it looks the code up (`normalizeTicketCode`), so the
 * space is display-only and typing it back changes nothing.
 */
function groupCode(code) {
  const value = String(code || "");
  if (value.length < 6 || value.length % 2 !== 0) return value;
  const half = value.length / 2;
  return value.slice(0, half) + " " + value.slice(half);
}

/**
 * The public `/auth` page is the install surface; its fragment is the one-shot credential. The QR must stop
 * before `#` so opening it in Safari cannot spend the code the newly installed PWA still needs.
 */
function installUrl(ticketUrl) {
  return String(ticketUrl || "").split("#", 1)[0];
}

/** Render the changing part of [PhoneDialog] for the current fetch state. */
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

/** No public URL configured: explain the one-time tunnel setup rather than draw an unreachable QR. */
function phoneSetup(onClose) {
  // The daemon always serves this page on its own explicit port, so window.location.port is always set.
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

// --- Help ------------------------------------------------------------------------------------------

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
