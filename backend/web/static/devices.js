import { request } from './api.js';
import { $ } from './format.js';
import { t, locale, savePreference } from './i18n.js';
let selected = null;
try { const stored = JSON.parse(localStorage.getItem('obd-device')); if (stored === null || typeof stored === 'string') selected = stored; } catch { /* Use all devices when storage is unavailable. */ }
export function deviceQuery(path, device = selected) {
  if (device === null) return path;
  return `${path}${path.includes('?') ? '&' : '?'}${new URLSearchParams({ device })}`;
}
export const selectedDevice = () => selected;
export function createDevices(onChange, onRegistry) {
  let timer, active = false, generation = 0, devices = [];
  const select = $('device-select');
  function render() {
    const options = devices.map((device, index) => {
      const seen = device.last_seen ? t('last_data', { time: new Intl.DateTimeFormat(locale(), { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(device.last_seen)) }) : t('no_device_data');
      const option = document.createElement('option'); option.value = String(index);
      option.textContent = `${device.connected ? '● ' : '○ '}${device.name || t('no_device')} · ${seen}`;
      return option;
    });
    if (devices.length > 1) { const all = document.createElement('option'); all.value = 'all'; all.textContent = t('all'); options.unshift(all); }
    select.replaceChildren(...options);
    select.value = selected === null ? 'all' : String(devices.findIndex(device => device.name === selected));
    $('device-selector').hidden = devices.length < 2;
    onRegistry(devices, selected);
  }
  function choose(value) {
    if (selected === value) return;
    selected = value; savePreference('obd-device', JSON.stringify(selected));
    onChange();
  }
  async function load() {
    const current = generation;
    try {
      const result = await request('/devices');
      if (!active || current !== generation) return;
      devices = (result?.devices || []).filter(device => typeof device.name === 'string');
      if (devices.length === 1) choose(devices[0].name);
      else if (selected !== null && !devices.some(device => device.name === selected)) choose(null);
      $('devices-error').textContent = ''; render();
    } catch {
      if (active && current === generation) { $('devices-error').textContent = t('device_fetch_failed'); onRegistry([], selected); }
    } finally { if (active && current === generation) timer = setTimeout(load, 10000); }
  }
  select.onchange = () => { choose(select.value === 'all' ? null : devices[Number(select.value)].name); render(); };
  window.addEventListener('language-change', render);
  return {
    start() { if (active) return; active = true; generation++; return load(); },
    stop() { active = false; generation++; clearTimeout(timer); devices = []; render(); }
  };
}
