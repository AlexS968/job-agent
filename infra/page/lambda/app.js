const loadingIndicator = document.querySelector("#read-loading");
const loadingText = document.querySelector("#read-loading-text");
const readRecovery = createReadRecovery({status: (active, waking) => {
  loadingIndicator.hidden = !active;
  loadingText.textContent = waking ? "The database is waking up…" : "Loading data…";
}});
const readFetch = (url, options) => readRecovery.read(url, options);
let config, token, profile = "alex-de", offset = 0;
const state = document.querySelector("#state");
const items = document.querySelector("#items");
const more = document.querySelector("#more");
const retry = document.querySelector("#retry");
const yieldState = document.querySelector("#yield-state");
const yieldItems = document.querySelector("#yield-items");
const yieldMore = document.querySelector("#yield-more");
const yieldRetry = document.querySelector("#yield-retry");
const qualityState = document.querySelector("#quality-state");
const qualityDetails = document.querySelector("#quality-details");
const qualityRetry = document.querySelector("#quality-retry");
let yieldOffset = 0;
const base64url = bytes => btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const configPromise = fetch("/config", {cache: "no-store"}).then(r => { if (!r.ok) throw Error(); return r.json(); });
async function signIn() {
  config = await configPromise;
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const challenge = base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))));
  sessionStorage.setItem("sign-in-view", selectedView());
  sessionStorage.setItem("pkce", verifier);
  const stateValue = base64url(crypto.getRandomValues(new Uint8Array(24)));
  sessionStorage.setItem("oauth-state", stateValue);
  const url = new URL(config.domain + "/oauth2/authorize");
  url.search = new URLSearchParams({response_type:"code",client_id:config.clientId,redirect_uri:config.redirect,
    scope:"openid",code_challenge_method:"S256",code_challenge:challenge,state:stateValue});
  location.assign(url);
}
async function exchangeCode(code, returnedState) {
  config = await configPromise;
  const verifier = sessionStorage.getItem("pkce");
  const expectedState = sessionStorage.getItem("oauth-state");
  sessionStorage.removeItem("pkce");
  sessionStorage.removeItem("oauth-state");
  history.replaceState(null, "", "/");
  if (!verifier || !expectedState || expectedState !== returnedState)
    throw Error("Sign-in session expired. Please sign in again.");
  const body = new URLSearchParams({grant_type:"authorization_code",client_id:config.clientId,
    redirect_uri:config.redirect,code,code_verifier:verifier});
  const result = await fetch(config.domain + "/oauth2/token", {method:"POST",
    headers:{"Content-Type":"application/x-www-form-urlencoded"},body});
  if (!result.ok) throw Error("Sign-in failed. Please try again.");
  token = (await result.json()).access_token;
  document.querySelector("#signin").hidden = true;
  document.querySelector("#signout").hidden = false;
  document.querySelector("#profiles").hidden = false;
  document.querySelector("#vacancy-filters").hidden = false;
  document.querySelector("#quality-section").hidden = false;
  document.querySelector("#yield-section").hidden = false;
  document.querySelector("#source-report-filters").hidden = false;
  const returnView = sessionStorage.getItem("sign-in-view");
  sessionStorage.removeItem("sign-in-view");
  const returnPath = pageViews.includes(returnView) && returnView !== "vacancies" ? "#" + returnView : "/";
  history.replaceState(null, "", returnPath);
  return selectView();
}
function line(label, values) {
  const p = document.createElement("p");
  p.textContent = `${label}: ${values.length ? values.join(", ").replaceAll("_", " ").toLowerCase() : "none recorded"}`;
  return p;
}
const decisionReasons = {
  INTERESTED: [],
  NOT_INTERESTED: ["ROLE_MISMATCH", "SENIORITY", "LOCATION_OR_WORK_COUNTRY", "REMOTE_POLICY",
    "SALARY", "TECH_STACK", "COMPANY_OR_BUSINESS_MODEL", "OTHER"],
  NEED_MORE_INFO: ["WORK_COUNTRY_UNCONFIRMED", "SALARY_UNKNOWN", "REMOTE_POLICY_UNKNOWN",
    "ROLE_DETAILS_UNKNOWN", "OTHER"]
};
const readable = value => value.replaceAll("_", " ").toLowerCase();
function feedbackEditor(item, selectedProfile) {
  const section = document.createElement("section");
  const heading = document.createElement("h3"); heading.textContent = "Your decision";
  const latest = document.createElement("p");
  latest.textContent = item.ownerDecision ?
    `${readable(item.ownerDecision)}${item.ownerReason ? " — " + readable(item.ownerReason) : ""}` +
      (item.feedbackEvaluationId !== item.evaluationId ? " (recorded for an earlier evaluation)" : "") :
    "No owner decision recorded.";
  section.append(heading, latest);
  if (!config.feedbackEnabled || !item.recommended) return section;
  const choice = document.createElement("select");
  const reason = document.createElement("select");
  const button = document.createElement("button"); button.type = "button"; button.textContent = "Save decision";
  const status = document.createElement("p"); status.setAttribute("role", "status");
  const choiceLabel = document.createElement("label"); choiceLabel.textContent = "Decision "; choiceLabel.append(choice);
  const reasonLabel = document.createElement("label"); reasonLabel.textContent = "Reason "; reasonLabel.append(reason);
  for (const value of ["", ...Object.keys(decisionReasons)]) {
    const option = document.createElement("option"); option.value = value;
    option.textContent = value ? readable(value) : "Choose a decision"; choice.append(option);
  }
  let requestId;
  function updateReasons() {
    requestId = undefined;
    reason.replaceChildren();
    for (const value of ["", ...(decisionReasons[choice.value] || [])]) {
      const option = document.createElement("option"); option.value = value;
      option.textContent = value ? readable(value) : "Choose a reason"; reason.append(option);
    }
    reasonLabel.hidden = choice.value === "INTERESTED";
  }
  choice.addEventListener("change", updateReasons);
  reason.addEventListener("change", () => { requestId = undefined; });
  updateReasons();
  button.addEventListener("click", async () => {
    if (!choice.value || (choice.value !== "INTERESTED" && !reason.value)) {
      status.textContent = "Choose a decision and its reason."; return;
    }
    requestId ||= crypto.randomUUID();
    button.disabled = true; status.textContent = "Saving decision…";
    try {
      const response = await fetch("/feedback", {method: "POST", cache: "no-store",
        headers: {Authorization: "Bearer " + token, "Content-Type": "application/json"},
        body: JSON.stringify({requestId, profile: selectedProfile, vacancyId: item.vacancyId,
          evaluationId: item.evaluationId, profileVersion: item.profileVersion, revision: item.revision,
          decision: choice.value, reason: choice.value === "INTERESTED" ? null : reason.value})});
      if (!response.ok) throw Error(response.status === 409 ?
        "This evaluation changed or the request was reused. Reload before another decision." :
        "Decision was not confirmed. Retry the same selection; it will not create a duplicate.");
      const saved = await response.json();
      latest.textContent = `${readable(saved.decision)}` +
        (saved.reason ? ` — ${readable(saved.reason)}` : "");
      status.textContent = "Decision saved. Earlier decisions remain in history.";
      requestId = undefined;
    } catch (error) { status.textContent = error.message; }
    finally { button.disabled = false; }
  });
  section.append(choiceLabel, reasonLabel, button, status);
  return section;
}
async function load() {
  const selectedProfile = profile, requestedOffset = offset;
  const selectedSource = document.querySelector("#vacancy-source").value;
  const selectedStatus = document.querySelector("#vacancy-status").value;
  const country = selectedProfile === "alex-de" ? "Germany" : "Austria";
  document.querySelector("#selected-country").textContent = `${country} vacancy review`;
  document.querySelectorAll("[data-profile]").forEach(button =>
    button.setAttribute("aria-pressed", String(button.dataset.profile === profile)));
  state.textContent = "Loading vacancies…";
  more.hidden = true;
  retry.hidden = true;
  try {
    const query = new URLSearchParams({profile:selectedProfile, source:selectedSource, status:selectedStatus,
      offset:String(requestedOffset),limit:"20"});
    const response = await readFetch("/vacancies?" + query, {headers:{Authorization:"Bearer " + token},cache:"no-store"});
    if (response.status === 401 || response.status === 403) throw Error("Access expired or denied. Sign in again.");
    if (!response.ok) throw Error("Vacancies could not be loaded. Please retry later.");
    const page = await response.json();
    if (selectedProfile !== profile || requestedOffset !== offset ||
        selectedSource !== document.querySelector("#vacancy-source").value ||
        selectedStatus !== document.querySelector("#vacancy-status").value) return;
    if (requestedOffset === 0) items.replaceChildren();
    for (const item of page.items) items.append(renderVacancy(item, selectedProfile));
    const scope = document.querySelector("#vacancy-status").selectedOptions[0].textContent;
    state.textContent = page.total ?
      `${scope}: ${page.total} distinct stored vacancies for the selected source scope; showing ${items.children.length}. ` +
      `Assessment profile: ${country}. Advertised geography is not work-country eligibility.` :
      `No stored vacancies match these filters for the ${country} assessment. Other sources or list scopes may contain observations.`;
    if (page.nextOffset !== null) { offset = page.nextOffset; more.hidden = false; }
  } catch (error) {
    if (error.name === "AbortError") return;
    if (selectedProfile !== profile) return;
    state.textContent = `${country} vacancies could not be loaded. ${error.message}`;
    retry.hidden = false;
  }
}
function paragraph(text) {
  const node = document.createElement("p"); node.textContent = text; return node;
}
function renderVacancy(item, selectedProfile) {
  const card = document.createElement("article");
  const heading = document.createElement("h3");
  if (item.url) {
    const link = document.createElement("a");
    link.href = item.url; link.rel = "noopener noreferrer"; link.textContent = item.title; heading.append(link);
  } else heading.textContent = item.title;
  card.append(heading);
  const sources = {LINKEDIN:"LinkedIn alert", HIMALAYAS:"Himalayas", CAREER_BOARDS:"Verified company career board"};
  const employer = item.employer || "Employer not established";
  const employerEvidence = ["LINKEDIN", "HIMALAYAS"].includes(item.source) && item.employer
    ? `Advertised employer: ${employer} (source claim)` : employer;
  card.append(paragraph(`${sources[item.source] || "Stored source"} · ${employerEvidence}`));
  const directions = {GERMANY:"Germany", AUSTRIA:"Austria", REMOTE_EUROPE:"Remote Europe",
    REMOTE_WORLDWIDE:"Remote Worldwide", UNKNOWN:"Unknown"};
  card.append(paragraph(`Advertised geography: ${directions[item.advertisedDirection] || "Unknown"}` +
    (item.advertisedLocation || item.location ? ` (${item.advertisedLocation || item.location})` : "") +
    ". This does not establish work-country permission."));
  const explanation = item.explanation;
  card.append(paragraph("Application assessment: " + explanation.decisionLabel),
    paragraph(explanation.assessment), paragraph(explanation.relevance));
  for (const text of [...explanation.exclusions, ...explanation.questions]) card.append(paragraph(text));
  card.append(paragraph(explanation.nextAction));
  if (item.applicationUrl) {
    const apply = document.createElement("a"); apply.href = item.applicationUrl;
    apply.rel = "noopener noreferrer"; apply.textContent = "Apply on the advertised application site"; card.append(apply);
  }
  if (!item.url) card.append(paragraph("Posting link is unavailable until Job Agent refreshes this evidence."));
  card.append(feedbackEditor(item, selectedProfile));
  return card;
}

function codes(values) {
  const entries = Object.entries(values || {});
  return entries.length ? entries.map(([code, count]) => `${code.replaceAll("_", " ").toLowerCase()}: ${count}`).join("; ") : "none recorded";
}
async function loadQuality() {
  const selectedProfile = profile;
  const country = selectedProfile === "alex-de" ? "Germany" : "Austria";
  qualityState.textContent = `Loading ${country} explanation…`;
  qualityDetails.replaceChildren(); qualityRetry.hidden = true;
  try {
    const result = await readFetch("/quality?" + new URLSearchParams({profile:selectedProfile}),
      {headers:{Authorization:"Bearer " + token},cache:"no-store"});
    if (!result.ok) throw Error("Explanation is temporarily unavailable.");
    const data = await result.json();
    if (selectedProfile !== profile) return;
    qualityState.textContent = data.stored === 0 ?
      `No vacancies are stored from active verified sources in this sample for ${country}.` :
      `Of ${data.stored} distinct stored vacancies, ${data.roleRelevant} have a relevant role and ` +
      `${data.shortlisted} have a confirmed ${country} location and reach the useful shortlist.`;
    const funnel = document.createElement("p");
    funnel.textContent = `Where the ${data.stored} stored vacancies stand: ${codes(data.stages)}. ` +
      "These stages are mutually exclusive; a missing evaluation is not an owner rejection.";
    const evidence = document.createElement("p");
    evidence.textContent = `Overlapping evidence (do not add these counts): exclusions — ${codes(data.exclusions)}; ` +
      `unknown conditions — ${codes(data.unknowns)}.`;
    const feedback = document.createElement("p");
    const decisions = data.feedback.map(row =>
      `${readable(row.decision)}${row.reason ? " / " + readable(row.reason) : ""}: ${row.count}`).join("; ");
    feedback.textContent = `${data.feedbackVacancies} of ${data.stored} distinct stored vacancies have an owner ` +
      `decision for ${country}. Latest decision per vacancy: ${decisions || "none"}. ` +
      "Owner judgments are separate from agent evaluations; applications are not tracked." +
      (data.feedbackVacancies < 10 ? " This small feedback sample supports no trend conclusion." : "");
    const limits = document.createElement("p");
    limits.textContent = `All stored observations from currently active verified sources as of ${data.asOf}; ` +
      `current profile ${data.profileVersion || "not registered"}, ${data.policyVersion}, ${data.countryEvidencePolicy}. ` +
      `${data.crossSourceOverlap} vacancies are linked to multiple active sources; Germany and Austria ` +
      "counts can refer to the same vacancy. This sample is not the whole job market.";
    const comparison = document.createElement("p");
    const c = data.comparison;
    comparison.textContent = `Version check: ${c.summary} Latest ${data.profileVersion || "none"}: ` +
      `${c.currentEvaluated} evaluated; prior ${c.previousVersion || "none"}: ` +
      `${c.previousEvaluated} evaluated; ${c.matchedVacancies} matched vacancies, ` +
      `${c.pairedFeedback} with decisions on both versions. Source processing versions in the ` +
      `bounded sample: ${codes(data.sourceVersions)}.`;
    qualityDetails.append(funnel, evidence, feedback, limits, comparison);
    if (data.examples.length) {
      const list = document.createElement("ul");
      for (const example of data.examples) {
        const item = document.createElement("li");
        const link = document.createElement("a"); link.href = example.url;
        link.rel = "noopener noreferrer"; link.textContent = example.title;
        item.append(`${readable(example.stage)}: `, link); list.append(item);
      }
      const heading = document.createElement("h3"); heading.textContent = "Examples from stored evaluations";
      qualityDetails.append(heading, list);
    }
  } catch (error) {
    if (error.name === "AbortError") return;
    if (selectedProfile !== profile) return;
    qualityState.textContent = `${country} explanation could not be loaded. ${error.message}`;
    qualityRetry.hidden = false;
  }
}
async function loadYield() {
  const selectedProfile = profile;
  yieldState.textContent = "Loading source counts…";
  yieldMore.hidden = true;
  yieldRetry.hidden = true;
  try {
    const query = new URLSearchParams({profile:selectedProfile,offset:String(yieldOffset),limit:"20"});
    const result = await readFetch("/yield?" + query, {headers:{Authorization:"Bearer " + token},cache:"no-store"});
    if (!result.ok) throw Error("Source counts are temporarily unavailable. Use Retry source counts.");
    const data = await result.json();
    if (selectedProfile !== profile) return;
    if (yieldOffset === 0) yieldItems.replaceChildren();
    for (const entry of data.rows) {
      const card = document.createElement("article");
      const heading = document.createElement("h3");
      heading.textContent = `${entry.source} · ${entry.profile === "alex-de" ? "Germany" : "Austria"} (${entry.profileVersion})`;
      const counts = document.createElement("p");
      counts.textContent = `Observed listings: ${entry.observed}; distinct stored vacancies: ${entry.stored}; current evaluations: ${entry.evaluated}; useful shortlist: ${entry.shortlisted}.`;
      const losses = document.createElement("p");
      losses.textContent = `Excluded: ${entry.excluded}; unmatched roles: ${entry.roleUnmatched}; country evidence unknown: ${entry.countryUnknown}; without a current evaluation: ${Math.max(0, entry.stored-entry.evaluated)}.`;
      card.append(heading, counts, losses, line("Exclusion reasons", [codes(entry.exclusions)]),
        line("Unknown conditions", [codes(entry.unknowns)]));
      yieldItems.append(card);
    }
    yieldState.textContent = data.rows.length ?
      `All stored observations as of ${data.rows[0].asOf}. Policy ${data.policyVersion}; country evidence ${data.countryEvidencePolicy}. Cross-source overlap: ${data.rows[0].crossSourceOverlap} vacancies. Source counts can overlap.` :
      "No active sources or registered country profiles in this view. Zero here does not describe all vacancies on a platform or in a country.";
    if (data.nextOffset !== null) { yieldOffset = data.nextOffset; yieldMore.hidden = false; }
  } catch (error) {
    if (error.name === "AbortError") return;
    if (selectedProfile !== profile) return;
    yieldState.textContent = error.message; yieldRetry.hidden = false;
  }
}
const reportDays = document.querySelector("#report-days");
const reportGeography = document.querySelector("#report-geography");
const reportState = document.querySelector("#source-report-state");
const reportRetry = document.querySelector("#source-report-retry");
const authState = document.querySelector("#auth-state");
let reportRequest = 0;

const pageViews = ["vacancies", "source-report", "market-analysis"];
const marketState = document.querySelector("#market-analysis-state");
const marketRetry = document.querySelector("#market-analysis-retry");
let marketRequest = 0;

function selectedView() {
  const view = location.hash.slice(1);
  return pageViews.includes(view) ? view : "vacancies";
}

function selectView() {
  readRecovery.cancel();
  const view = selectedView();
  document.querySelector("#vacancy-view").hidden = view !== "vacancies";
  document.querySelector("#source-report-view").hidden = view !== "source-report";
  document.querySelector("#market-analysis-view").hidden = view !== "market-analysis";
  for (const entry of pageViews) {
    document.querySelector("#show-" + entry).setAttribute("aria-current", view === entry ? "page" : "false");
  }
  const title = {"vacancies": "vacancies", "source-report": "Source Report", "market-analysis": "Market Analysis"};
  document.title = "Job Agent " + title[view];
  if (!token) return;
  if (view === "source-report") return loadSourceReport();
  if (view === "market-analysis") return loadMarketAnalysis();
  {
    load(); loadQuality(); loadYield();
  }
}

function reportRow(entry, label) {
  const row = document.createElement("tr");
  const heading = document.createElement("th");
  heading.scope = "row";
  heading.append(label);
  row.append(heading);
  for (const key of ["vacancies", "candidates", "shortlistDe", "shortlistAt", "channelOnly"]) {
    const cell = document.createElement("td");
    cell.textContent = entry[key];
    row.append(cell);
  }
  return row;
}

function renderSourceReport(data) {
  const rows = document.querySelector("#source-report-rows");
  const boards = document.querySelector("#source-report-boards");
  rows.replaceChildren();
  boards.replaceChildren();
  boards.hidden = true;
  for (const entry of data.channels) {
    let label = entry.label;
    if (entry.channel === "CAREER_BOARDS" && data.employers.length) {
      label = document.createElement("button");
      label.type = "button";
      label.textContent = entry.label;
      label.setAttribute("aria-controls", boards.id);
      label.setAttribute("aria-expanded", "false");
      label.addEventListener("click", () => {
        boards.hidden = !boards.hidden;
        label.setAttribute("aria-expanded", String(!boards.hidden));
      });
    }
    rows.append(reportRow(entry, label));
  }
  for (const employer of data.employers) {
    boards.append(reportRow(employer, employer.label));
  }
}

async function loadSourceReport() {
  if (!token) return;
  const request = ++reportRequest;
  const results = document.querySelector("#source-report-results");
  const notes = document.querySelector("#source-report-notes");
  reportState.textContent = "Loading source report…";
  results.hidden = true;
  notes.hidden = true;
  reportRetry.hidden = true;
  try {
    const query = new URLSearchParams({days: reportDays.value, geography: reportGeography.value});
    const result = await readFetch("/source-report?" + query,
      {headers: {Authorization: "Bearer " + token}, cache: "no-store"});
    if (request !== reportRequest || selectedView() !== "source-report") return;
    if (result.status === 401 || result.status === 403) {
      reportState.textContent = "Access expired or denied. Sign in again.";
      return;
    }
    if (result.status === 422) {
      reportState.textContent = "This period exceeds the report capacity." +
        (reportDays.value === "30" ? " Try the 7-day period." : "");
      return;
    }
    if (!result.ok) throw Error();
    const data = await result.json();
    if (request !== reportRequest || selectedView() !== "source-report") return;
    reportState.textContent = data.total ? `${data.total} unique vacancies. Confirmed cross-channel overlap: ${data.overlap}.` :
      "No stored vacancies match this period and advertised geography.";
    reportState.textContent += ` Current shortlist as of ${new Date(data.asOf).toLocaleString()}.`;
    renderSourceReport(data);
    if (data.employersTruncated) {
      reportState.textContent += " The employer breakdown shows the first 50 employers.";
    }
    notes.hidden = false;
    results.hidden = false;
  } catch (error) {
    if (error.name === "AbortError") return;
    if (request !== reportRequest || selectedView() !== "source-report") return;
    reportState.textContent = "The source report is temporarily unavailable.";
    reportRetry.hidden = false;
  }
}

function monthLabel(month) {
  return new Intl.DateTimeFormat("en", {month: "long", year: "numeric", timeZone: "UTC"})
    .format(new Date(month + "-01T00:00:00Z"));
}

function comparisonRow(label, values) {
  const row = document.createElement("tr");
  const heading = document.createElement("th");
  heading.scope = "row";
  heading.textContent = label;
  row.append(heading);
  for (const value of values) {
    const cell = document.createElement("td");
    cell.textContent = value;
    row.append(cell);
  }
  return row;
}

function renderMarketAnalysis(months) {
  document.querySelector("#market-older").textContent = monthLabel(months[0].month);
  document.querySelector("#market-newer").textContent = monthLabel(months[1].month);
  const rows = document.querySelector("#market-analysis-rows");
  rows.replaceChildren(comparisonRow("Distinct vacancies observed", months.map(month => month.total)),
    comparisonRow("Confirmed cross-channel overlap", months.map(month => month.overlap)));
  for (const [index, source] of months[0].sources.entries()) {
    rows.append(comparisonRow(source.label, months.map(month => {
      const entry = month.sources[index];
      const observed = entry.vacancies === 0 ? "No stored observations" : String(entry.vacancies);
      return entry.coverage === "UNKNOWN" ? observed + " · coverage unknown" : observed;
    })));
  }
  for (const [index, geography] of months[0].geographies.entries()) {
    rows.append(comparisonRow("Advertised: " + geography.label,
      months.map(month => month.geographies[index].vacancies)));
  }
  const boards = document.querySelector("#market-analysis-boards");
  boards.replaceChildren();
  for (const month of months) {
    const heading = document.createElement("h2");
    heading.textContent = monthLabel(month.month);
    boards.append(heading);
    if (!month.boards.length) {
      const empty = document.createElement("p");
      empty.textContent = "No stored observations from verified boards. Coverage unknown.";
      boards.append(empty);
      continue;
    }
    const list = document.createElement("ul");
    for (const board of month.boards) {
      const item = document.createElement("li");
      item.textContent = `${board.employer} (${board.board}): ${board.vacancies}`;
      list.append(item);
    }
    boards.append(list);
    if (month.boardsTruncated) {
      const limit = document.createElement("p");
      limit.textContent = "Showing up to 50 boards by observed vacancy count.";
      boards.append(limit);
    }
  }
}

async function loadMarketAnalysis() {
  if (!token) return;
  const request = ++marketRequest;
  const results = document.querySelector("#market-analysis-results");
  results.hidden = true;
  marketRetry.hidden = true;
  marketState.textContent = "Loading market analysis…";
  try {
    const result = await readFetch("/market-analysis", {
      headers: {Authorization: "Bearer " + token}, cache: "no-store"
    });
    if (request !== marketRequest || selectedView() !== "market-analysis") return;
    if (result.status === 401 || result.status === 403) {
      marketState.textContent = "Access expired or denied. Sign in again.";
      return;
    }
    if (result.status === 422) {
      marketState.textContent = "The two completed months exceed the analysis capacity.";
      return;
    }
    if (!result.ok) throw Error();
    const data = await result.json();
    if (request !== marketRequest || selectedView() !== "market-analysis") return;
    renderMarketAnalysis(data.months);
    marketState.textContent = data.months.some(month => month.total > 0)
      ? "Stored observations for two completed months." : "There are no stored observations for these months.";
    document.querySelector("#market-analysis-limits").textContent =
      "Coverage is unknown. These counts describe the stored sample. Trend conclusions are withheld because comparable source collection could not be established.";
    results.hidden = false;
  } catch (error) {
    if (error.name === "AbortError") return;
    if (request !== marketRequest || selectedView() !== "market-analysis") return;
    marketState.textContent = "Market analysis is temporarily unavailable.";
    marketRetry.hidden = false;
  }
}

marketRetry.addEventListener("click", loadMarketAnalysis);
window.addEventListener("hashchange", selectView);
reportDays.addEventListener("change", () => { readRecovery.cancel(); loadSourceReport(); });
reportGeography.addEventListener("change", () => { readRecovery.cancel(); loadSourceReport(); });
reportRetry.addEventListener("click", loadSourceReport);
selectView();
document.querySelector("#signin").addEventListener("click", () => signIn().catch(() => authState.textContent = "Sign-in unavailable."));
document.querySelector("#signout").addEventListener("click", () => {
  readRecovery.cancel();
  token = undefined;
  const url = new URL(config.domain + "/logout");
  url.search = new URLSearchParams({client_id:config.clientId,logout_uri:config.redirect});
  location.assign(url);
});
document.querySelectorAll("[data-profile]").forEach(button => button.addEventListener("click", () => {
  readRecovery.cancel();
  profile = button.dataset.profile; offset = 0; yieldOffset = 0;
  load(); loadQuality(); loadYield();
}));
more.addEventListener("click", load);
retry.addEventListener("click", load);
yieldMore.addEventListener("click", loadYield);
yieldRetry.addEventListener("click", loadYield);
qualityRetry.addEventListener("click", loadQuality);
for (const id of ["vacancy-source", "vacancy-status"]) {
  document.querySelector("#" + id).addEventListener("change", () => {
    readRecovery.cancel(); offset = 0; load();
  });
}
const code = new URL(location.href).searchParams.get("code");
if (code) exchangeCode(code, new URL(location.href).searchParams.get("state"))
  .catch(error => authState.textContent = error.message);
