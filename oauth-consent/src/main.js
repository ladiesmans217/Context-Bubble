import { createClient } from "@supabase/supabase-js";
import "./style.css";

const url = import.meta.env.VITE_SUPABASE_URL;
const publishableKey = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY;
const authorizationId = new URLSearchParams(location.search).get("authorization_id");
const status = document.querySelector("#status");
const details = document.querySelector("#details");
const signIn = document.querySelector("#sign-in");

if (!url || !publishableKey || !authorizationId) {
  fail(!authorizationId ? "This authorization link is missing or expired." : "The consent page is not configured.");
} else {
  const supabase = createClient(url, publishableKey, {
    auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true },
  });
  await showAuthorization(supabase);
}

async function showAuthorization(supabase) {
  const { data: claims } = await supabase.auth.getClaims();
  if (!claims?.claims) {
    status.textContent = "Sign in to review this request. Your approval is never automatic.";
    signIn.hidden = false;
    signIn.onclick = async () => {
      signIn.disabled = true;
      const redirectTo = `${location.origin}${location.pathname}?authorization_id=${encodeURIComponent(authorizationId)}`;
      const { error } = await supabase.auth.signInWithOAuth({ provider: "google", options: { redirectTo } });
      if (error) fail(error.message);
    };
    return;
  }

  const { data: authorization, error } = await supabase.auth.oauth.getAuthorizationDetails(authorizationId);
  if (error || !authorization) return fail(error?.message ?? "This authorization request is invalid.");
  if (!("authorization_id" in authorization)) {
    location.assign(authorization.redirect_url);
    return;
  }
  document.querySelector("#client-name").textContent = authorization.client?.name || authorization.client?.client_id || "MCP client";
  document.querySelector("#scopes").textContent = authorization.scope || "openid profile email";
  status.textContent = "This client is asking to connect to your approved Context Bubble memory.";
  details.hidden = false;

  document.querySelector("#approve").onclick = () => decide(supabase, true);
  document.querySelector("#deny").onclick = () => decide(supabase, false);
}

async function decide(supabase, approve) {
  setButtonsDisabled(true);
  status.textContent = approve ? "Approving connection…" : "Denying connection…";
  const result = approve
    ? await supabase.auth.oauth.approveAuthorization(authorizationId)
    : await supabase.auth.oauth.denyAuthorization(authorizationId);
  if (result.error || !result.data?.redirect_url) {
    setButtonsDisabled(false);
    return fail(result.error?.message ?? "The authorization decision could not be completed.");
  }
  location.assign(result.data.redirect_url);
}

function setButtonsDisabled(disabled) {
  document.querySelector("#approve").disabled = disabled;
  document.querySelector("#deny").disabled = disabled;
}

function fail(message) {
  document.querySelector("#title").textContent = "Connection unavailable";
  status.textContent = message;
  details.hidden = true;
  signIn.hidden = true;
}
