export interface MutexHolder {
  sessionId: string;
  acquiredAt: number;
}

export interface MutexWaiter {
  sessionId: string;
  since: number;
  granted: boolean;
}

export interface MutexEntry {
  key: string;
  holder: MutexHolder | null;
  waiters: MutexWaiter[];
}

export interface MutexListing {
  rev: number;
  mutexes: MutexEntry[];
  serverNow: number;
}

/** `receivedAt` is the client's monotonic time at receipt; `serverNow` is the daemon's clock at that moment. */
export interface ReceivedMutexListing extends MutexListing {
  receivedAt: number;
}

export const MUTEX_HELD_LONG_MS = 15 * 60_000;

function finite(value: unknown): value is number {
  return Number.isFinite(value);
}

function readListing(listing: unknown, receivedAt: number): ReceivedMutexListing | null {
  if (!listing || typeof listing !== "object") return null;
  const { rev, mutexes, serverNow } = listing as Partial<MutexListing>;
  if (!finite(rev) || !Array.isArray(mutexes)) return null;
  return {
    rev,
    mutexes: mutexes.filter((entry) => entry && typeof entry.key === "string")
      .map((entry) => ({ ...entry, waiters: Array.isArray(entry.waiters) ? entry.waiters : [] })),
    serverNow: finite(serverNow) ? serverNow : NaN,
    receivedAt,
  };
}

/** A snapshot is the socket's authoritative baseline and also re-bases elapsed time after a sleep. */
export function applyMutexSnapshot(listing: unknown, receivedAt: number): ReceivedMutexListing | null {
  return readListing(listing, receivedAt);
}

export function mergeMutexUpdate(
  current: ReceivedMutexListing | null,
  listing: unknown,
  receivedAt: number,
): ReceivedMutexListing | null {
  const incoming = readListing(listing, receivedAt);
  if (!incoming) return current;
  if (current && !(incoming.rev > current.rev)) return current;
  return incoming;
}

/** Age comes from the daemon's clock at receipt; later growth uses only the client's monotonic clock. */
export function mutexElapsed(listing: ReceivedMutexListing, since: number, clientNow: number): number | null {
  if (!finite(listing.serverNow) || !finite(since) || !finite(clientNow)) return null;
  return Math.max(0, listing.serverNow - since) + Math.max(0, clientNow - listing.receivedAt);
}

export function isHeldLong(elapsedMs: number | null) {
  return elapsedMs !== null && elapsedMs > MUTEX_HELD_LONG_MS;
}

export function formatMutexElapsed(elapsedMs: number | null) {
  if (elapsedMs === null) return "unknown";
  const seconds = Math.floor(elapsedMs / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ${String(seconds % 60).padStart(2, "0")}s`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${String(minutes % 60).padStart(2, "0")}m`;
}

export interface SessionMutexPill {
  key: string;
  kind: "held" | "waiting";
  position: number | null;
}

/** Positions count every waiter of the key, so `#1` is next in line even while a grant awaits its claim. */
export function sessionMutexPills(listing: ReceivedMutexListing | null, sessionId: string | null): SessionMutexPill[] {
  if (!listing || !sessionId) return [];
  const pills: SessionMutexPill[] = [];
  for (const entry of listing.mutexes) {
    if (entry.holder && entry.holder.sessionId === sessionId) {
      pills.push({ key: entry.key, kind: "held", position: null });
    }
    entry.waiters.forEach((waiter, index) => {
      if (waiter.sessionId === sessionId) pills.push({ key: entry.key, kind: "waiting", position: index + 1 });
    });
  }
  return pills;
}
