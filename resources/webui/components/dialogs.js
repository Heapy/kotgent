/*
 * The three modal screens: New session, Preferences, Help.
 *
 * Each one is a native `<dialog>`, so Esc, focus trapping and the backdrop come from the platform. The
 * [Dialog] wrapper below is the only place that talks to the imperative dialog API: mounting the
 * component opens it, and the native `close` event (Esc or the backdrop) reports back so the parent can
 * unmount it. Open/closed is therefore ordinary Preact state — there is no second source of truth.
 *
 * htm is not an HTML parser and does not decode entities, so any literal `<` in the copy below is
 * interpolated as a JS string (`${"kt-<id>"}`) rather than written as `&lt;`.
 */

import { html } from "htm/preact";
import { useCallback, useEffect, useRef, useState } from "preact/hooks";
import { groupFor, joinPath, normalizePath, segmentsUnder } from "../lib/paths.js";
import { MAX_GROUPING_LEVEL, TERMINAL_FONT_SIZES, sanitizePrefs } from "../lib/prefs.js";
import { apiRequest, errorMessage } from "../lib/api.js";
import { qrSvg } from "../lib/qr.js";

function Dialog({ id, labelledBy, onClose, children }) {
  const ref = useRef(null);

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

  return html`<dialog id=${id} ref=${ref} aria-labelledby=${labelledBy}>${children}</dialog>`;
}

// --- New session -----------------------------------------------------------------------------------

const DIRECTORY_COMPLETION_DELAY_MS = 150;

/*
 * The picker's choices, in display order. `available: false` renders an announced but unselectable card:
 * the kind is shown as planned, and its radio is `disabled`, so it leaves the tab order and the arrow-key
 * group entirely. That flag has to agree with the daemon's `agentFactoryOf`, which is the real gate — it
 * rejects an unknown kind with a 400 before any tmux side effect, so a card enabled here ahead of its
 * adapter would only produce a failed start. `icon` is a stroked path in a 24×24 box, not a brand mark.
 */
const AGENT_CHOICES = [
  {
    value: "claude", name: "Claude", available: true,
    icon: "M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6 5.6 18.4",
  },
  {
    value: "codex", name: "Codex", available: true,
    icon: "m6 7 5 5-5 5M13 17h5",
  },
  {
    value: "junie", name: "Junie", available: false,
    icon: "M9 4h6M13 4v9a4 4 0 0 1-8 0",
  },
  {
    value: "cursor", name: "Cursor", available: false,
    icon: "M5 3V17l4-3.5 3 6.5 2.6-1.1-2.9-6.4h4.5Z",
  },
];

/** The card that takes the dialog's initial focus while no agent is chosen. */
const FIRST_AVAILABLE_AGENT = AGENT_CHOICES.find((choice) => choice.available).value;

export function NewSessionDialog({ initialCwd, basePath, onStart, onClose }) {
  const [agent, setAgent] = useState("");
  const [cwd, setCwd] = useState(initialCwd || "");
  const [completionQuery, setCompletionQuery] = useState(null);
  const [suggestions, setSuggestions] = useState([]);
  const [activeSuggestion, setActiveSuggestion] = useState(-1);
  const [cwdFocused, setCwdFocused] = useState(false);
  const [name, setName] = useState("");
  const [tags, setTags] = useState("");
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const cwdRef = useRef(null);
  const agentRef = useRef(null);

  // There is deliberately no default agent, so the picker — not the prefilled path — is the first
  // answer this dialog needs, and it is where the initial focus belongs. Seeding `agent` from a
  // preference later would hand the landing spot back to the path field on its own.
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

  const submit = async (event) => {
    event.preventDefault();
    if (!agent) {
      // The one requirement the browser cannot report here. A `disabled` submit would swallow both the
      // click and Enter with no feedback at all, and native `required` would anchor its bubble on a
      // radio that is `opacity: 0` — so the missing choice is reported through the dialog's own
      // `role="alert"` line, and focus goes back to the picker.
      setError("Pick an agent to start a session.");
      if (agentRef.current) agentRef.current.focus();
      return;
    }
    const tagList = tags
      .split(",")
      .map((tag) => tag.trim())
      .filter((tag, index, all) => tag.length > 0 && all.indexOf(tag) === index);

    setBusy(true);
    setError(null);
    try {
      await onStart({ agent: agent, cwd: cwd.trim(), name: name.trim() || null, tags: tagList });
    } catch (e) {
      setError("Could not start session: " + errorMessage(e));
      setBusy(false);
    }
  };

  return html`
    <${Dialog} id="new-session-dialog" labelledBy="new-session-title" onClose=${onClose}>
      <form id="new-session-form" onSubmit=${submit}>
        <div class="dialog-head">
          <div>
            <h2 id="new-session-title">New session</h2>
            <p>Start a coding agent in a tmux-backed workspace.</p>
          </div>
          <button id="new-session-close" class="icon-button" type="button"
                  aria-label="Close" onClick=${onClose}>×</button>
        </div>

        <fieldset class="field agent-picker"
                  aria-describedby=${agent ? null : "new-session-agent-hint"}>
          <legend>Agent</legend>
          <div class="agent-options">
            ${AGENT_CHOICES.map((choice) => html`
              <label key=${choice.value}
                     class=${"agent-option" + (choice.available ? "" : " agent-option-unavailable")}>
                <input id=${"session-agent-" + choice.value} type="radio" name="session-agent"
                       value=${choice.value} disabled=${!choice.available}
                       aria-required=${choice.available ? "true" : null}
                       ref=${choice.value === FIRST_AVAILABLE_AGENT ? agentRef : null}
                       checked=${agent === choice.value} onChange=${chooseAgent} />
                <span class="agent-option-content">
                  <span class=${"agent-icon agent-icon-" + choice.value} aria-hidden="true">
                    <svg viewBox="0 0 24 24" focusable="false"><path d=${choice.icon} /></svg>
                  </span>
                  <span class="agent-option-name">
                    ${choice.name}${!choice.available && html`<small>Soon</small>`}
                  </span>
                </span>
              </label>
            `)}
          </div>
          ${!agent && html`
            <p id="new-session-agent-hint" class="field-hint">Pick one to start a session.</p>
          `}
        </fieldset>

        <div class="field">
          <label for="session-cwd">Working directory</label>
          <div class="path-autocomplete">
            <input id="session-cwd" type="text" required spellcheck="false" autocomplete="off"
                   role="combobox" aria-autocomplete="list"
                   aria-expanded=${cwdFocused && suggestions.length > 0 ? "true" : "false"}
                   aria-controls="session-cwd-options"
                   aria-activedescendant=${activeSuggestion >= 0
                     ? "session-cwd-option-" + activeSuggestion
                     : null}
                   placeholder="/path/to/project" ref=${cwdRef}
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

        ${error && html`<p id="new-session-error" class="form-error" role="alert">${error}</p>`}

        <div class="dialog-actions">
          <button id="new-session-cancel" class="button button-quiet" type="button"
                  onClick=${onClose}>Cancel</button>
          <button id="new-session-submit" class="button button-primary" type="submit" disabled=${busy}>
            ${busy ? "Starting…" : "Start session"}
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
  const sample = sessions.find((s) => segmentsUnder(draft.basePath, s.cwd) !== null);
  if (!sample) {
    const placeholders = Array.from({ length: draft.groupingLevel }, (_, i) => "<dir" + (i + 1) + ">");
    return "Groups at " + joinPath(draft.basePath, placeholders);
  }
  return normalizePath(sample.cwd) + " → " + groupFor(sample.cwd, draft.basePath, draft.groupingLevel).path;
}

const LEVEL_LABELS = [
  "0 — one group for everything",
  "1 — one group per direct child",
  "2 — two levels deep",
  "3 — three levels deep",
  "4 — four levels deep",
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
    <${Dialog} id="prefs-dialog" labelledBy="prefs-title" onClose=${onClose}>
      <form id="prefs-form" onSubmit=${submit}>
        <div class="dialog-head">
          <div>
            <h2 id="prefs-title">Preferences</h2>
            <p>Base path and grouping are shared by every browser connected to this daemon.</p>
          </div>
          <button id="prefs-close" class="icon-button" type="button"
                  aria-label="Close" onClick=${onClose}>×</button>
        </div>

        <label class="field">
          <span>Base path</span>
          <input id="prefs-base-path" type="text" spellcheck="false" placeholder="/Users/you/dev"
                 ref=${inputRef} value=${basePath} onInput=${(e) => setBasePath(e.target.value)} />
          <small class="field-hint">
            Absolute path. Sessions below it are grouped by directory, and new sessions default to it.
            Leave empty for one flat list.
          </small>
        </label>

        <label class="field">
          <span>Grouping level <small>directories below the base path</small></span>
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
kotgent start <agent> [cwd]   start a session (claude | codex)
kotgent attach <id>           attach a raw terminal
kotgent interrupt <id>        send Ctrl-C
kotgent stop <id>             stop a session
kotgent resume <id>           resume a stopped/crashed session
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
              Use Kotgent's Detach button instead of tmux's detach binding; it closes only this viewer.
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
