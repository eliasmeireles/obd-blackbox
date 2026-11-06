import { setLanguage, translatePage, readPreference, savePreference } from './i18n.js';
const colorScheme = window.matchMedia('(prefers-color-scheme: dark)');
let theme = readPreference('obd-theme', ['auto', 'dark', 'light'], 'auto');
function applyTheme() {
  document.documentElement.dataset.theme = theme === 'auto' ? (colorScheme.matches ? 'dark' : 'light') : theme;
  window.dispatchEvent(new Event('theme-change'));
}
export function initPreferences() {
  applyTheme(); translatePage();
  const language = document.getElementById('language-select');
  language.value = readPreference('obd-language', ['auto', 'pt-BR', 'en'], 'auto');
  document.getElementById('theme-select').value = theme;
  language.onchange = () => {
    savePreference('obd-language', language.value); setLanguage(language.value); translatePage();
    window.dispatchEvent(new Event('language-change'));
  };
  document.getElementById('theme-select').onchange = event => {
    theme = event.target.value; savePreference('obd-theme', theme); applyTheme();
  };
  colorScheme.addEventListener('change', () => { if (theme === 'auto') applyTheme(); });
  window.addEventListener('languagechange', () => {
    if (language.value !== 'auto') return;
    setLanguage('auto'); translatePage(); window.dispatchEvent(new Event('language-change'));
  });
  document.addEventListener('click', event => {
    const menu = document.getElementById('settings');
    if (!menu.contains(event.target)) menu.open = false;
  });
  document.addEventListener('keydown', event => { if (event.key === 'Escape') document.getElementById('settings').open = false; });
}
