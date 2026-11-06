import { $ } from './format.js';
const KEY = 'tm-source';
export function createSource(onChange) {
  let value = 'obd';
  try { if (localStorage.getItem(KEY) === 'sim') value = 'sim'; } catch { /* Storage may be unavailable. */ }
  function render() {
    for (const source of ['obd', 'sim']) $('source-' + source).setAttribute('aria-pressed', String(value === source));
    $('simulation-banner').hidden = value !== 'sim';
  }
  for (const source of ['obd', 'sim']) $('source-' + source).onclick = () => {
    if (value === source) return;
    value = source;
    try { localStorage.setItem(KEY, value); } catch { /* Keep the selection for this page. */ }
    render(); onChange(value);
  };
  render();
  return { get value() { return value; } };
}
