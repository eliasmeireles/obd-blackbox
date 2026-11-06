import { t } from './i18n.js';
import { request } from './api.js';
import { deviceQuery } from './devices.js';
import { $, date, duration, number, source, eventRows } from './format.js';
import { initGauge, renderGauge } from './gauge.js';
import { initTiles, renderTiles } from './tiles.js';
const alertDefinitions = () => ({
  LOW_VOLTAGE: [t('low_voltage'), false], OVERHEAT: [t('overheat'), true],
  IDLE_DROP: [t('idle_drop'), true], NO_THROTTLE_RESPONSE: [t('no_throttle'), true],
  LEAN_MIXTURE: [t('lean'), false], RICH_MIXTURE: [t('rich'), false]
});
const STALE_MS = 5000;

export function createLive() {
  let active = false, stream, retry, clock, latest = null, lag = null, shownStale = false, backoff = 1000, generation = 0, selectedSource = 'obd', streamMessage = 'loading_telemetry', streamValues = {};
  initGauge(); initTiles();
  const current = (id) => active && generation === id;
  const age = () => (latest ? Math.max(0, Date.now() - latest.t) : null);
  // Old values must not look current: after STALE_MS without samples the panel is cleared until data returns.
  const isStale = () => latest !== null && age() >= STALE_MS;
  function streamStatus(key, values = {}) { streamMessage = key; streamValues = values; $('stream-status').textContent = t(key, values); }
  function status() {
    const elapsed = age(), stale = isStale();
    if (stale !== shownStale) { render(); return; }
    $('live-status').textContent = elapsed === null ? t('waiting') : stale ? t('stale_status', { duration: duration(elapsed) }) : t('live_status');
    $('status-dot').classList.toggle('active', elapsed !== null && !stale);
    const delay = lag !== null && !stale ? t('sample_delay', { seconds: number(lag / 1000, 1), unit: t('unit_seconds') }) : '';
    $('last-time').textContent = latest ? t('last_sample', { time: date(latest.t), delay }) : t('no_samples');
    $('stale-banner').hidden = !stale;
    $('stale-banner').textContent = stale
      ? t('stale_banner', { duration: duration(elapsed), time: date(latest.t) })
      : '';
  }
  function render() {
    shownStale = isStale();
    const v = shownStale ? {} : latest?.v || {}; renderGauge(v); renderTiles(v); status();
    $('source').textContent = source(selectedSource);
    $('live-empty').hidden = Boolean(latest);
    $('live-empty').textContent = selectedSource === 'obd' ? t('no_live_obd') : t('no_live_sim');
    const alerts = alertDefinitions();
    const items = (shownStale ? [] : latest?.alerts || []).filter(id => alerts[id]).sort((a, b) => Number(alerts[b][1]) - Number(alerts[a][1]));
    $('alert-banner').hidden = !items.length;
    $('alert-banner').className = `alert-banner ${items.length && alerts[items[0]][1] ? 'critical' : ''}`;
    $('alert-banner').textContent = items.length ? `${alerts[items[0]][0]}${items.length > 1 ? ` · +${number(items.length - 1)}` : ''}` : '';
  }
  async function events(id) {
    try { const result = await request(deviceQuery(`/events?limit=20&source=${selectedSource}`)); if (current(id)) eventRows($('recent-events'), result?.events || []); }
    catch (error) { if (current(id)) $('recent-events').textContent = error.message; }
  }
  function receive(data, id) {
    if (!current(id) || !data.sample || data.source !== selectedSource) return;
    if (latest && data.sample.t < latest.t) return;
    latest = data.sample;
    // Time between the phone reading the car and this page receiving it (phone and browser clocks are NTP-synced).
    const now = Date.now() - latest.t;
    lag = now >= 0 && now < 60000 ? (lag === null ? now : lag * 0.7 + now * 0.3) : null;
    render(); if (data.sample.event) events(id);
  }
  function connect(id) {
    if (!current(id)) return;
    const connection = new EventSource(deviceQuery(`/api/v1/live/stream?source=${selectedSource}`));
    stream = connection;
    connection.onopen = () => { if (current(id)) { backoff = 1000; streamStatus('stream_connected'); } };
    connection.addEventListener('sample', event => {
      if (!current(id)) return;
      try { receive(JSON.parse(event.data), id); } catch { streamStatus('invalid_sample'); }
    });
    connection.onerror = () => {
      connection.close(); if (!current(id)) return;
      streamStatus('stream_retry', { duration: duration(backoff) });
      request('/auth/me').catch(() => {});
      retry = setTimeout(() => connect(id), backoff); backoff = Math.min(backoff * 2, 30000);
    };
  }
  return {
    refresh() { initTiles(); render(); streamStatus(streamMessage, streamValues); if (active) events(generation); },
    async start(source = 'obd') {
      if (active) return;
      selectedSource = source; active = true; const id = ++generation; latest = null; lag = null; backoff = 1000; render();
      $('recent-events').replaceChildren();
      streamStatus('loading_telemetry'); events(id); clock = setInterval(status, 1000);
      try { const data = await request(deviceQuery(`/live?source=${selectedSource}`)); if (data) { receive(data, id); lag = null; } }
      catch (error) { if (current(id)) $('stream-status').textContent = error.message; }
      connect(id);
    },
    stop() { active = false; generation++; stream?.close(); clearTimeout(retry); clearInterval(clock); }
  };
}
