/* Bounded recovery for protected reads, with one active HTTP attempt across sections. */
(function (root) {
  function createReadRecovery({fetcher = fetch, now = Date.now, budgetMs = 60000,
                               requestMs = 25000, delayMs = 2000, status = () => {}} = {}) {
    const pending = new Map();
    let epoch = 0;
    let queue = Promise.resolve();
    const aborted = () => new DOMException("Read cancelled", "AbortError");
    function cancel() {
      epoch++;
      for (const task of pending.values()) task.controller.abort();
      pending.clear();
      status(false, false);
    }
    function check(task) {
      if (task.epoch !== epoch || task.controller.signal.aborted) throw aborted();
      if (now() >= task.deadline) throw Error("Loading timed out. Please retry.");
    }
    async function attempt(task, url, options) {
      check(task);
      const controller = new AbortController();
      const abort = () => controller.abort();
      task.controller.signal.addEventListener("abort", abort, {once: true});
      const timer = setTimeout(abort, Math.min(requestMs, task.deadline - now()));
      try {
        const result = await fetcher(url, {...options, signal: controller.signal});
        const text = await result.text();
        check(task);
        return new Response(text, {status: result.status, headers: result.headers});
      } finally {
        clearTimeout(timer);
        task.controller.signal.removeEventListener("abort", abort);
      }
    }
    function pause(task) {
      return new Promise((resolve, reject) => {
        const signal = task.controller.signal;
        const abort = () => { clearTimeout(timer); reject(aborted()); };
        const timer = setTimeout(() => {
          signal.removeEventListener("abort", abort);
          resolve();
        }, Math.min(delayMs, Math.max(0, task.deadline - now())));
        signal.addEventListener("abort", abort, {once: true});
        if (signal.aborted) abort();
      });
    }
    async function read(url, options) {
      const key = new URL(url, "https://local.invalid").pathname;
      pending.get(key)?.controller.abort();
      const task = {controller: new AbortController(), epoch, deadline: now() + budgetMs};
      pending.set(key, task);
      status(true, false);
      try {
        while (true) {
          check(task);
          const call = queue.then(() => attempt(task, url, options));
          queue = call.catch(() => {});
          let result;
          try {
            result = await call;
          } catch (error) {
            check(task);
            if (error.name !== "AbortError" && !(error instanceof TypeError)) throw error;
          }
          check(task);
          if (result && ![429, 500, 502, 503, 504].includes(result.status)) {
            const json = result.json.bind(result);
            result.json = async () => { const body = await json(); check(task); return body; };
            return result;
          }
          let waking = false;
          if (result) {
            const diagnostic = result.clone();
            try {
              const body = await diagnostic.json();
              if (body.code === "READ_FAILED") return result;
              waking = body.code === "DATABASE_RESUMING";
            } catch { /* Gateway errors may be text. */ }
          }
          status(true, waking);
          await pause(task);
        }
      } finally {
        if (pending.get(key) === task) pending.delete(key);
        status(pending.size > 0, false);
      }
    }
    return {read, cancel};
  }
  root.createReadRecovery = createReadRecovery;
  if (typeof module !== "undefined") module.exports = {createReadRecovery};
})(globalThis);
