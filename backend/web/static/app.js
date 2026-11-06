import { request, post } from './api.js';
import { $ } from './format.js';
import { t } from './i18n.js';
import { initPreferences } from './preferences.js';
import { createLive } from './live.js';
import { createSessions } from './sessions.js';
import { createSource } from './source.js';
import { createCar } from './car.js';
import { createLogs } from './logs.js';
import { createDevices } from './devices.js';
initPreferences();
let authenticated = false, ready = false, methods = null, loginError = '', activeTab = 'live';
const car = createCar(), logs = createLogs(), live = createLive();
const sessions = createSessions(() => {
  if (authenticated) { live.stop(); live.start(sources.value); }
  return authenticated;
});
function refreshSelection() {
  sessions.setSource(sources.value); car.setSource(sources.value); logs.reset();
  if (!authenticated || !ready) return;
  live.stop(); live.start(sources.value);
  if (activeTab === 'sessions') sessions.load();
}
const sources = createSource(refreshSelection);
const devices = createDevices(refreshSelection, (registry, selected) => car.setDevices(registry, selected));
sessions.setSource(sources.value);
const loginErrors = { unavailable: 'login_unavailable', denied: 'login_denied', expired: 'login_expired', forbidden: 'login_forbidden', failed: 'login_failed' };
function renderLogin() {
  $('login-form').hidden = !methods?.methods?.includes('api_key');
  $('login-button').hidden = !methods?.methods?.includes('oidc');
  $('login-button').textContent = methods?.label || t('oidc_login');
  if (methods?.login_url) {
    const target = new URL(methods.login_url, location.href);
    if (['https:', 'http:'].includes(target.protocol)) $('login-button').href = target.href;
    else $('login-button').hidden = true;
  }
  $('login-error').textContent = loginError ? t(loginError) : '';
}
function showLogin(error = '') {
  authenticated = false; ready = false;
  live.stop(); sessions.clear(); car.stop(); logs.stop(); devices.stop();
  const errorCode = new URLSearchParams(location.search).get('error');
  loginError = error || (Object.hasOwn(loginErrors, errorCode) ? loginErrors[errorCode] : '');
  $('dashboard').hidden = true; $('login').hidden = false; $('boot-status').hidden = true; $('logout').hidden = true;
  $('login-settings').append($('settings')); renderLogin();
  if (!$('login-form').hidden) $('api-key').focus();
}
function tab(name) {
  activeTab = name;
  for (const id of ['live', 'sessions', 'car', 'logs']) {
    $(id + '-view').hidden = id !== name;
    $(id + '-tab').classList.toggle('quiet', id !== name);
    if (id === name) $(id + '-tab').setAttribute('aria-current', 'page');
    else $(id + '-tab').removeAttribute('aria-current');
  }
  if (name === 'sessions') sessions.load();
  if (name === 'car') car.load();
  if (name === 'logs') logs.start(); else logs.stop();
}
async function showDashboard() {
  authenticated = true; ready = false;
  $('boot-status').hidden = true; $('login').hidden = true; $('dashboard').hidden = false; $('logout').hidden = false;
  $('dashboard-settings').append($('settings'));
  if (location.pathname === '/login' || location.search) history.replaceState(null, '', '/');
  $('app-message').textContent = ''; tab('live'); car.start(sources.value);
  await devices.start();
  if (!authenticated) return;
  ready = true; live.start(sources.value);
}
for (const id of ['live', 'sessions', 'car', 'logs']) $(id + '-tab').onclick = () => tab(id);
window.addEventListener('auth-expired', () => { if (authenticated) showLogin('session_expired'); });
window.addEventListener('language-change', () => {
  renderLogin();
  if (!authenticated) return;
  live.refresh(); sessions.refreshLanguage(); car.load(); logs.render();
});
$('login-form').onsubmit = async event => {
  event.preventDefault(); $('login-submit').disabled = true; loginError = ''; renderLogin();
  try { await post('/auth/login', { key: $('api-key').value }); $('api-key').value = ''; await showDashboard(); }
  catch (error) { loginError = error.status === 401 ? 'invalid_key' : 'login_failed'; renderLogin(); }
  finally { $('login-submit').disabled = false; }
};
$('logout').onclick = async () => {
  $('logout').disabled = true;
  try {
    const out = await post('/auth/logout'); showLogin();
    if (out?.logoutUrl) { const target = new URL(out.logoutUrl, location.href); if (['https:', 'http:'].includes(target.protocol)) location.href = target.href; }
  } catch (error) { if (authenticated) $('app-message').textContent = error.message; }
  finally { $('logout').disabled = false; }
};
async function boot() {
  try {
    methods = await request('/auth/methods');
    try { await request('/auth/me'); await showDashboard(); }
    catch (error) { if (error.status === 401) showLogin(); else throw error; }
  } catch {
    $('boot-status').textContent = t('connection_failed') + ' ';
    const retry = document.createElement('button'); retry.className = 'quiet'; retry.textContent = t('retry');
    retry.onclick = () => { $('boot-status').textContent = t('connecting'); boot(); }; $('boot-status').append(retry);
  }
}
boot();
