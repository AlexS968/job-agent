const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");

class Element {
  constructor(tag = "div") { this.tag = tag; this.children = []; this._text = ""; this.value = "ALL"; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  set textContent(value) { this._text = value; this.children = []; }
  get textContent() { return this._text + this.children.map(c => c.textContent ?? c).join(" "); }
  setAttribute() {}
  addEventListener() {}
}
function app() {
  const elements = new Map();
  const document = {querySelector: key => {
    if (!elements.has(key)) elements.set(key, new Element());
    return elements.get(key);
  }, querySelectorAll: () => [], createElement: tag => new Element(tag)};
  const context = vm.createContext({document, URL, URLSearchParams,
    location: {href: "https://example.test/", hash: ""}, window: {addEventListener() {}},
    fetch: async () => ({ok: true, json: async () => ({feedbackEnabled: false})}),
    createReadRecovery: () => ({cancel() {}, read() { throw Error("Unexpected read"); }})});
  vm.runInContext(fs.readFileSync(path.join(__dirname, "../lambda/app.js"), "utf8"), context);
  vm.runInContext("config = {feedbackEnabled: true};", context);
  return context;
}
const vacancy = {vacancyId: 1, title: "Synthetic Java Engineer", employer: "Synthetic employer",
  source: "HIMALAYAS", url: "https://himalayas.app/jobs/fixture", applicationUrl: "https://example.test/apply",
  advertisedDirection: "REMOTE_EUROPE", recommended: false, ownerDecision: "NEED_MORE_INFO",
  ownerReason: "SALARY_UNKNOWN", feedbackEvaluationId: 2, evaluationId: null,
  explanation: {decisionLabel: "No current assessment", assessment: "An earlier assessment is stale.",
    relevance: "Stored Java/backend evidence supports role relevance.",
    questions: ["Salary is not stated — ask the employer."], exclusions: [],
    nextAction: "Inspect the posting and check permission to work from Austria."}};

test("card preserves attribution, actionable uncertainty and distinct owner judgment", () => {
  const context = app();
  context.item = vacancy;
  const card = vm.runInContext('renderVacancy(item, "alex-at")', context);
  const text = card.textContent;
  assert.match(text, /Himalayas/);
  assert.match(text, /Remote Europe/);
  assert.match(text, /Application assessment: No current assessment/);
  assert.match(text, /ask the employer/);
  assert.match(text, /Your decision.*need more info.*earlier evaluation/);
  assert.doesNotMatch(text, /ROLE_NOT_ESTABLISHED|SALARY_UNKNOWN|Reasons: none|Role: unknown/);
  assert.equal(card.children[0].children[0].href, vacancy.url);
  assert.equal(card.children.find(c => c.tag === "a").href, vacancy.applicationUrl);
  assert.doesNotMatch(text, /Save decision/); // No write against a missing/stale assessment.
});

test("a missing refreshed posting link stays explicit and cannot invent a recommendation", () => {
  const context = app();
  context.item = {...vacancy, url: null, applicationUrl: null, advertisedDirection: "UNKNOWN"};
  const card = vm.runInContext('renderVacancy(item, "alex-de")', context);
  assert.equal(card.children[0].textContent, vacancy.title);
  assert.match(card.textContent, /Posting link is unavailable/);
  assert.match(card.textContent, /does not establish work-country permission/);
  assert.equal(card.children.filter(c => c.tag === "a").length, 0);
});
