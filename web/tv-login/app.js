"use strict";
// Only the public project key belongs here. Auth tokens remain in memory.
const API = "https://athgklmjipltuskdfvov.supabase.co";
const KEY = "sb_publishable_hgibv-HY_9aguZ-xB6KpGg_tzTjWdeR";
const $ = (id) => document.getElementById(id);
const code = new URLSearchParams(location.search).get("code") || "";
let token = null, signup = false, busy = false, expiresAt = 0;
const validCode = /^[a-f0-9]{48}$/.test(code);
// Confirmation-email redirects may contain credentials; never retain them.
history.replaceState(null, "", location.pathname);
function status(message, error = false) {
  $("status").textContent = message;
  $("status").classList.toggle("error", error);
}
function controls(disabled) {
  busy = disabled;
  for (const id of ["submit", "mode", "approve", "cancel"]) $(id).disabled = disabled;
}
function friendly(error) {
  const message = error.message || "";
  if (/invalid login credentials/i.test(message)) return "Adresse e-mail ou mot de passe incorrect.";
  if (/email not confirmed/i.test(message)) return "Confirmez votre adresse avec le lien reçu par e-mail, puis reconnectez-vous.";
  if (/MFA/i.test(message)) return "La connexion QR ne prend pas encore en charge les comptes avec double authentification.";
  if (/expired|already used|invalid.*code/i.test(message)) return "Ce QR code a expiré ou a déjà été utilisé. Générez-en un nouveau sur la TV.";
  if (/rate|too many|429/i.test(message)) return "Trop de tentatives. Patientez quelques minutes.";
  if (/password/i.test(message)) return "Le mot de passe doit contenir au moins 8 caractères et respecter les exigences du service.";
  if (/fetch|network|timeout/i.test(message)) return "Connexion interrompue. Vérifiez Internet et réessayez.";
  return "Impossible de terminer. Réessayez ou générez un nouveau QR code sur la TV.";
}
async function request(path, body, authenticated = false) {
  const headers = { apikey: KEY, "Content-Type": "application/json" };
  if (authenticated) headers.Authorization = "Bearer " + token;
  const response = await fetch(API + path, {
    method: "POST", headers, body: JSON.stringify(body),
    cache: "no-store", credentials: "omit", signal: AbortSignal.timeout(15000),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.msg || data.message || data.error_description || data.error || String(response.status));
  return data;
}
$("mode").addEventListener("click", () => {
  signup = !signup;
  $("eligibility").hidden = !signup;
  $("adult").required = signup;
  $("terms").required = signup;
  $("password").minLength = signup ? 8 : 1;
  $("password").autocomplete = signup ? "new-password" : "current-password";
  $("submit").textContent = signup ? "Créer mon compte" : "Se connecter";
  $("mode").textContent = signup ? "J’ai déjà un compte" : "Créer un compte";
});
$("login").addEventListener("submit", async (event) => {
  event.preventDefault();
  if (busy || !validCode) return;
  controls(true);
  status(signup ? "Création du compte…" : "Connexion…");
  try {
    const email = $("email").value.trim();
    const password = $("password").value;
    if (signup) {
      await request("/auth/v1/signup?redirect_to=" + encodeURIComponent("https://titoftotof.github.io/Tuvora/tv-login/"), {
        email, password, data: { adult_confirmed: true, terms_version: "2026-09-27" },
      });
      $("password").value = "";
      status("Consultez votre boîte e-mail pour confirmer votre adresse. Revenez ensuite scanner le QR code de la TV.");
      return;
    }
    const session = await request("/auth/v1/token?grant_type=password", { email, password });
    token = session.access_token;
    $("password").value = "";
    const requests = await request("/rest/v1/rpc/get_tv_login_request", { p_code: code }, true);
    if (!requests.length) throw new Error("QR login expired or already used");
    expiresAt = Date.parse(requests[0].expires_at);
    $("device").textContent = requests[0].device_name;
    $("account").textContent = "Compte : " + (session.user.email || email);
    $("login").hidden = true;
    $("confirmation").hidden = false;
    status("Confirmez pour autoriser cette TV à utiliser votre compte.");
  } catch (error) {
    token = null;
    status(friendly(error), true);
  } finally { controls(false); }
});
$("approve").addEventListener("click", async () => {
  if (busy || !token) return;
  if (Date.now() >= expiresAt) { status("Ce QR code a expiré. Générez-en un nouveau sur la TV.", true); return; }
  controls(true);
  try {
    await request("/rest/v1/rpc/approve_tv_login_session", { p_code: code }, true);
    token = null;
    $("confirmation").hidden = true;
    status("TV autorisée ! La connexion se termine sur votre téléviseur. Vous pouvez fermer cette page.");
  } catch (error) { status(friendly(error), true); }
  finally { controls(false); }
});
$("cancel").addEventListener("click", () => {
  token = null;
  $("confirmation").hidden = true;
  $("login").hidden = false;
  status("Demande annulée sur ce téléphone. La TV n’a pas été autorisée.");
});
if (validCode) {
  $("login").hidden = false;
  controls(false);
  status("Connectez-vous pour autoriser votre TV.");
} else {
  status("Scannez le QR code affiché par Tuvora sur votre TV pour commencer.");
}
