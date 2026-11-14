let config, token, profile = "alex-de", offset = 0;
const state = document.querySelector("#state");
const items = document.querySelector("#items");
const more = document.querySelector("#more");
const retry = document.querySelector("#retry");
const yieldState = document.querySelector("#yield-state");
const yieldItems = document.querySelector("#yield-items");
const yieldMore = document.querySelector("#yield-more");
const yieldRetry = document.querySelector("#yield-retry");
let yieldOffset = 0;
const base64url = bytes => btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const configPromise = fetch("/config", {cache: "no-store"}).then(r => { if (!r.ok) throw Error(); return r.json(); });
async function signIn() {
  config = await configPromise;
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const challenge = base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))));
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
  await load();
  await loadYield();
}
function line(label, values) {
  const p = document.createElement("p");
  p.textContent = `${label}: ${values.length ? values.join(", ").replaceAll("_", " ").toLowerCase() : "none recorded"}`;
  return p;
}
async function load() {
  const country = profile === "alex-de" ? "Germany" : "Austria";
  document.querySelector("#selected-country").textContent = `${country} opportunities`;
  document.querySelectorAll("[data-profile]").forEach(button =>
    button.setAttribute("aria-pressed", String(button.dataset.profile === profile)));
  state.textContent = "Loading vacancies…";
  more.hidden = true;
  retry.hidden = true;
  try {
    const query = new URLSearchParams({profile,offset:String(offset),limit:"20"});
    const response = await fetch("/vacancies?" + query, {headers:{Authorization:"Bearer " + token},cache:"no-store"});
    if (response.status === 401 || response.status === 403) throw Error("Access expired or denied. Sign in again.");
    if (!response.ok) throw Error("Vacancies could not be loaded. Please retry later.");
    const page = await response.json();
    if (offset === 0) items.replaceChildren();
    for (const item of page.items) {
      const card = document.createElement("article");
      const heading = document.createElement("h2");
      const link = document.createElement("a");
      link.href = item.url; link.rel = "noopener noreferrer"; link.textContent = item.title;
      heading.append(link); card.append(heading);
      const employer = document.createElement("p"); employer.textContent = item.employer || "Employer unknown";
      card.append(employer, line("Evaluation", [item.decision]), line("Reasons", item.reasons),
        line("Needs verification", item.unknowns));
      items.append(card);
    }
    state.textContent = items.children.length ? `Showing ${items.children.length} ${profile === "alex-de" ? "Germany" : "Austria"} opportunities.` :
      "No currently confirmed recommendations in this stored sample. See source counts and unknown conditions below.";
    if (page.nextOffset !== null) { offset = page.nextOffset; more.hidden = false; }
  } catch (error) {
    state.textContent = `${country} vacancies could not be loaded. ${error.message}`;
    retry.hidden = false;
  }
}
function codes(values) {
  const entries = Object.entries(values || {});
  return entries.length ? entries.map(([code, count]) => `${code.replaceAll("_", " ").toLowerCase()}: ${count}`).join("; ") : "none recorded";
}
async function loadYield() {
  yieldState.textContent = "Loading source counts…";
  yieldMore.hidden = true;
  yieldRetry.hidden = true;
  try {
    const query = new URLSearchParams({offset:String(yieldOffset),limit:"20"});
    const result = await fetch("/yield?" + query, {headers:{Authorization:"Bearer " + token},cache:"no-store"});
    if (!result.ok) throw Error("Source counts are temporarily unavailable. Use Retry source counts.");
    const data = await result.json();
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
      `All stored observations as of ${data.rows[0].asOf}. Policy ${data.policyVersion}; country evidence ${data.countryEvidencePolicy}. Cross-source overlap: ${data.rows[0].crossSourceOverlap} vacancies. Counts by profile and source must not be added as unique vacancies.` :
      "No active sources or registered country profiles in this view. Zero here does not describe all vacancies on a platform or in a country.";
    if (data.nextOffset !== null) { yieldOffset = data.nextOffset; yieldMore.hidden = false; }
  } catch (error) { yieldState.textContent = error.message; yieldRetry.hidden = false; }
}
document.querySelector("#signin").addEventListener("click", () => signIn().catch(() => state.textContent = "Sign-in unavailable."));
document.querySelector("#signout").addEventListener("click", () => {
  token = undefined;
  const url = new URL(config.domain + "/logout");
  url.search = new URLSearchParams({client_id:config.clientId,logout_uri:config.redirect});
  location.assign(url);
});
document.querySelectorAll("[data-profile]").forEach(button => button.addEventListener("click", () => {
  profile = button.dataset.profile; offset = 0; load();
}));
more.addEventListener("click", load);
retry.addEventListener("click", load);
yieldMore.addEventListener("click", loadYield);
yieldRetry.addEventListener("click", loadYield);
const code = new URL(location.href).searchParams.get("code");
if (code) exchangeCode(code, new URL(location.href).searchParams.get("state"))
  .catch(error => state.textContent = error.message);
