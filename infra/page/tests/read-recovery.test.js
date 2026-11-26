const {test} = require("node:test");
const assert = require("node:assert/strict");
const {createReadRecovery} = require("../lambda/read-recovery.js");
const reply = (status, code) => new Response(JSON.stringify({code}), {status});

test("confirmed resume then success uses safe status", async () => {
  const statuses = [];
  let attempts = 0;
  const recovery = createReadRecovery({delayMs: 1, status: (...args) => statuses.push(args),
    fetcher: async () => ++attempts === 1 ? reply(503, "DATABASE_RESUMING") : reply(200, "ok")});
  assert.equal((await recovery.read("/vacancies")).status, 200);
  assert.equal(attempts, 2);
  assert.ok(statuses.some(([active, waking]) => active && waking));
});

test("retry budget expires with generic failures", async () => {
  let time = 0;
  const statuses = [];
  const recovery = createReadRecovery({budgetMs: 20, delayMs: 1, now: () => time,
    status: (...args) => statuses.push(args), fetcher: async () => {
      time += 11;
      return reply(503, "READ_UNAVAILABLE");
    }});
  await assert.rejects(recovery.read("/quality"), /Loading timed out/);
  assert.ok(statuses.every(([, waking]) => !waking));
});

test("auth validation and permanent database failures stop", async () => {
  for (const [code, body] of [[401, ""], [403, ""], [400, ""], [422, ""], [500, "READ_FAILED"]]) {
    let attempts = 0;
    const recovery = createReadRecovery({fetcher: async () => { attempts++; return reply(code, body); }});
    assert.equal((await recovery.read("/yield")).status, code);
    assert.equal(attempts, 1);
  }
});

test("cancellation aborts active reads and queued requests", async () => {
  let entered;
  const started = new Promise(resolve => { entered = resolve; });
  let attempts = 0;
  const recovery = createReadRecovery({fetcher: (url, options) => {
    attempts++;
    entered();
    return new Promise((resolve, reject) => options.signal.addEventListener("abort",
      () => reject(new DOMException("cancelled", "AbortError")), {once: true}));
  }});
  const first = recovery.read("/vacancies");
  const second = recovery.read("/linkedin");
  const checked = Promise.all([assert.rejects(first, {name: "AbortError"}),
    assert.rejects(second, {name: "AbortError"})]);
  await started;
  recovery.cancel();
  await checked;
  assert.equal(attempts, 1);
});

test("cancelled late responses and parsed bodies cannot replace new results", async () => {
  let finish, entered;
  const started = new Promise(resolve => { entered = resolve; });
  const recovery = createReadRecovery({fetcher: () => new Promise(resolve => { finish = resolve; entered(); })});
  const old = recovery.read("/source-report?days=30");
  const checked = assert.rejects(old, {name: "AbortError"});
  await started;
  recovery.cancel();
  finish(reply(200, "obsolete"));
  await checked;
  const fast = createReadRecovery({fetcher: async () => reply(200, "old")});
  const response = await fast.read("/market-analysis");
  fast.cancel();
  await assert.rejects(response.json(), {name: "AbortError"});
});
