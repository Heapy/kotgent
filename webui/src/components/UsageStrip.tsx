import { useEffect, useId, useLayoutEffect, useRef, useState } from "preact/hooks";
import { usage, usageClockOffset } from "../state/usage.ts";
import { providerUsageStaleAt, usageTimeLeft, usageWindowSeconds, usageWindowTime } from "../lib/usage.ts";
import type { ReceivedUsageWindow } from "../lib/usage.ts";

interface UsageWindowProps {
  window: ReceivedUsageWindow;
  now: number;
  serverTimeOffset: number | null;
}

export type UsageStripProps = Record<string, never>;

function usageLabel(window: ReceivedUsageWindow) {
  const seconds = usageWindowSeconds(window);
  if (seconds === 604800) return "7d";
  if (seconds === 18000) return "5h";
  return window.windowKey;
}

const localDate = (stamp: number | null) => stamp !== null && Number.isFinite(stamp)
  ? new Date(stamp).toLocaleString() : "unknown";

function UsageWindow({ window, now, serverTimeOffset }: UsageWindowProps) {
  const [open, setOpen] = useState<boolean>(false);
  const root = useRef<HTMLDivElement>(null);
  const tooltipId = useId();
  const label = usageLabel(window);
  const time = usageWindowTime(window, Math.max(now, performance.now()), serverTimeOffset);
  const timeLeft = usageTimeLeft(time.remainingMs);
  const elapsed = time.elapsedPercent == null ? "" : `, ${Math.round(time.elapsedPercent)}% of window elapsed`;

  useLayoutEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent | FocusEvent) => {
      if (!root.current?.contains(event.target as Node | null)) setOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopPropagation();
      setOpen(false);
    };
    document.addEventListener("pointerdown", outside, true);
    document.addEventListener("focusin", outside, true);
    document.addEventListener("keydown", escape, true);
    return () => {
      document.removeEventListener("pointerdown", outside, true);
      document.removeEventListener("focusin", outside, true);
      document.removeEventListener("keydown", escape, true);
    };
  }, [open]);

  return (
    <div ref={root} class="usage-window" data-window={window.windowKey}
         onPointerEnter={(event) => { if (event.pointerType === "mouse") setOpen(true); }}
         onPointerLeave={(event) => {
           if (event.pointerType === "mouse" && !event.currentTarget.querySelector("button:focus-visible")) {
             setOpen(false);
           }
         }}>
      <button type="button" class="usage-window-button"
              aria-label={`${window.provider} ${label}: ${window.usedPercent}% used${elapsed}. Time left: ${timeLeft}`}
              aria-describedby={tooltipId}
              onFocus={(event) => { if (event.currentTarget.matches(":focus-visible")) setOpen(true); }}
              onBlur={() => setOpen(false)}
              onClick={() => setOpen(true)}>
        <span class="usage-window-label">{label}</span>
        <span class="usage-meter">
          <progress class="usage-progress" max="100" value={window.usedPercent}
                    aria-label={window.provider + " " + label + " usage"}
                    aria-valuetext={window.usedPercent + "% used"}></progress>
          {time.elapsedPercent != null && (
            <span class="usage-now-marker" aria-hidden="true" style={{ "--elapsed": time.elapsedPercent + "%" }}></span>)}
        </span>
      </button>
      <div id={tooltipId} class="usage-tooltip" role="tooltip" hidden={!open}>
        <strong>{window.provider} · {label} · {window.usedPercent}% used</strong>
        <dl>
          <div><dt>Now</dt><dd>{localDate(time.now)}</dd></div>
          <div><dt>Resets</dt><dd>{localDate(window.resetsAt)}</dd></div>
          <div><dt>Time left</dt><dd>{timeLeft}</dd></div>
          <div><dt>Observed</dt><dd>{localDate(window.receivedAt)}</dd></div>
        </dl>
      </div>
    </div>);
}

export function UsageStrip(_props: UsageStripProps) {
  const windows = usage.value;
  const serverTimeOffset = usageClockOffset.value;
  const [, refresh] = useState<number>(0);
  const now = performance.now();
  const providers = new Map<string, ReceivedUsageWindow[]>();
  for (const window of windows) {
    if (!providers.has(window.provider)) providers.set(window.provider, []);
    providers.get(window.provider)!.push(window);
  }
  const rows = Array.from(providers, ([provider, values]) => ({
    provider,
    windows: values,
    staleAt: providerUsageStaleAt(values),
  }));
  const nextRefreshAt = Math.min(
    ...rows.map((row) => row.staleAt).filter((deadline) => deadline > now),
    ...windows.map((window) => usageWindowTime(window, now, serverTimeOffset).nextUpdateAt),
  );
  useLayoutEffect(() => {
    if (!Number.isFinite(nextRefreshAt)) return;
    const timer = setTimeout(() => refresh((tick) => tick + 1),
      Math.min(2_147_483_647, Math.ceil(Math.max(0, nextRefreshAt - performance.now()))));
    return () => clearTimeout(timer);
  }, [nextRefreshAt]);
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === "visible") refresh((tick) => tick + 1);
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, []);
  if (!rows.length) return null;
  return (
    <div id="usage-strip" role="group" aria-label="Usage limits">
      {rows.map((row) => (
        <div key={row.provider} data-provider={row.provider}
             class={"usage-provider" + (now >= row.staleAt ? " stale" : "")}>
          <span class="usage-provider-name">{row.provider}</span>
          <div class="usage-windows">
            {row.windows.map((window) => (
              <UsageWindow key={window.windowKey} window={window} now={now} serverTimeOffset={serverTimeOffset} />))}
          </div>
        </div>))}
    </div>);
}
