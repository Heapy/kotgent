// Serialize reads of an unversioned source. A response overtaken by a later request is discarded; each
// read owns one readiness token, and failure reporting is requested per waiter.

export function createSerialRefresh({ read, begin, succeed, fail, report }) {
  const queue = { requested: 0, settled: 0, running: false, waiters: [] };

  return function refresh(reportFailure = true) {
    const request = ++queue.requested;
    const result = new Promise((resolve) => {
      queue.waiters.push({ request: request, reportFailure: reportFailure, resolve: resolve });
    });
    if (queue.running) return result;

    queue.running = true;
    void (async () => {
      try {
        while (queue.settled < queue.requested) {
          const reading = queue.requested;
          const token = begin();
          let rows = null;
          let failure = null;
          try {
            rows = await read();
          } catch (e) {
            failure = e;
          }

          if (reading !== queue.requested) continue;

          const ready = queue.waiters.filter((waiter) => waiter.request <= reading);
          queue.waiters = queue.waiters.filter((waiter) => waiter.request > reading);
          queue.settled = reading;
          // A store that throws while taking the rows may already have applied them. The read still counts
          // as failed: no caller builds on rows it cannot confirm, and the readiness gets its retry.
          if (!failure) {
            try {
              succeed(rows);
            } catch (e) {
              failure = e;
            }
          }
          try {
            if (failure) {
              if (ready.some((waiter) => waiter.reportFailure)) report(failure);
              fail(token, failure);
            }
          } finally {
            for (const waiter of ready) waiter.resolve(failure ? null : rows);
          }
        }
      } finally {
        // No port may leave the queue holding a waiter or the pump; both would outlive the page.
        queue.settled = queue.requested;
        for (const waiter of queue.waiters) waiter.resolve(null);
        queue.waiters = [];
        queue.running = false;
      }
    })();
    return result;
  };
}
