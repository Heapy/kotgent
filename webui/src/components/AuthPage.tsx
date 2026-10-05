import { useEffect, useRef, useState } from "preact/hooks";

interface AuthPageProps {
  exchangePath: string;
  codeLength: string;
  ttlMinutes: string;
  initialTicket: string | null;
}

function refusal(status: number): string {
  if (status === 429) return "Too many attempts. Wait a minute, then try again.";
  if (status === 0) return "Could not reach kotgent. Check the connection and try again.";
  return "That code is not valid. It may have expired or already been used.";
}

export function AuthPage({ exchangePath, codeLength, ttlMinutes, initialTicket }: AuthPageProps) {
  const [message, setMessage] = useState(initialTicket ? "Signing in…" : "Enter your sign-in code.");
  const [error, setError] = useState(false);
  const [showForm, setShowForm] = useState(!initialTicket);
  const [busy, setBusy] = useState(!!initialTicket);
  const [selectCode, setSelectCode] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const inFlight = useRef(false);
  const alive = useRef(false);

  async function exchange(ticket: string, selectOnFailure: boolean): Promise<void> {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(false);
    setMessage("Signing in…");
    let status = 0;
    try {
      const response = await fetch(exchangePath, {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ticket }),
      });
      if (!alive.current) return;
      if (response.ok) {
        window.location.replace("/");
        return;
      }
      status = response.status;
    } catch {
      // A network failure permits another attempt with the same code.
    }
    if (!alive.current) return;
    inFlight.current = false;
    setMessage(refusal(status));
    setError(true);
    setSelectCode(selectOnFailure);
    setShowForm(true);
    setBusy(false);
  }

  useEffect(() => {
    alive.current = true;
    if (initialTicket) void exchange(initialTicket, false);
    return () => { alive.current = false; };
  }, []);

  useEffect(() => {
    if (!showForm || busy) return;
    if (selectCode) inputRef.current?.select();
    else inputRef.current?.focus();
  }, [showForm, busy, selectCode]);

  return <main class="auth-page">
    <h1>Kotgent</h1>
    <p id="status" class={error ? "error" : ""} role="status" aria-live="polite">{message}</p>
    <form id="code-form" hidden={!showForm} onSubmit={(event) => {
      event.preventDefault();
      const ticket = inputRef.current?.value.trim();
      if (!ticket) {
        inputRef.current?.focus();
        return;
      }
      void exchange(ticket, true);
    }}>
      <label for="code" class="hint">Sign-in code</label>
      <input ref={inputRef} id="code" name="code" type="text" required
        autoComplete="one-time-code" autocapitalize="characters" autoCorrect="off"
        spellcheck={false} inputMode="latin" enterKeyHint="go" aria-describedby="code-help" />
      <button id="code-submit" type="submit" disabled={busy}>Sign in</button>
      <p id="code-help" class="hint">{codeLength} characters, one-time, good for {ttlMinutes} minutes.</p>
    </form>
    <p id="hint" class="hint" hidden={!showForm}>Get a code with <code>kotgent web</code>.</p>
  </main>;
}
