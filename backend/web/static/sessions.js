import { t } from './i18n.js';
import { request, sessionPath } from './api.js';
import { $, date, duration, number, source, empty, eventName } from './format.js';
import { createCharts } from './charts.js';
import { createDeletion } from './delete.js';
import { deviceQuery } from './devices.js';
export function createSessions(onDeleted) {
  const charts = createCharts();
  let selectedSource = 'obd';
  const deletion = createDeletion((source, id) => {
    if (!onDeleted()) return;
    if (source === selectedSource) {
      if (!id || selected?.id === id) clearDetail();
      load();
    }
  });
  function clearDetail() {
    version++; selection++; selected = null; events = []; charts.clear(); $('session-detail').hidden = true;
  }
  let selected, events = [], version = 0, listing = 0, selection = 0;
  const fullRange = () => [selected.started_at, Math.max(selected.started_at + 1, selected.last_sample_at || selected.started_at)];
  async function loadRange(from, to) {
    const current = ++version, id = selected.id;
    $('detail-status').textContent = t('loading_signals'); charts.clear();
    $('detail-range').textContent = `${date(from, true)} — ${date(to, true)}`;
    const params = new URLSearchParams({ from, to, limit: 50000 });
    if (to - from > 1200000 || selected.sample_count > 5000) params.set('step', Math.max(200, Math.ceil((to - from) / 4000)));
    try {
      const result = await request(`${sessionPath(id)}/samples?${params}`);
      if (current !== version) return;
      const samples = result?.samples || [];
      charts.render(samples, events, from, to);
      $('detail-status').textContent = t('samples_shown', { count: number(samples.length), resolution: params.has('step') ? t('sample_resolution', { seconds: number(Number(params.get('step')) / 1000, 1), unit: t('unit_seconds') }) : '', hint: samples.length === 50000 ? t('sample_limit') : t('select_event') });
    } catch (error) { if (current === version) $('detail-status').textContent = error.message; }
  }
  function renderEvents() {
    if (!events.length) return empty($('session-events'), t('no_session_events'));
    $('session-events').replaceChildren(...events.map(event => {
      const button = document.createElement('button'); button.className = 'quiet';
      button.textContent = `${date(event.t)} · ${eventName(event)}`;
      button.title = t('event_zoom');
      button.onclick = () => { const [start, end] = fullRange(); loadRange(Math.max(start, event.t - 60000), Math.min(end, event.t + 15000)); };
      return button;
    }));
  }
  async function select(session, button) {
    selected = session; const current = ++selection; version++; charts.clear(); events = [];
    document.querySelectorAll('.session-row').forEach(row => row.setAttribute('aria-pressed', String(row === button)));
    $('session-detail').hidden = false; $('detail-title').textContent = `${t('session_title', { source: source(session.source) })} · ${session.device || t('no_device')}${session.vehicle ? ` · ${session.vehicle}` : ''}`;
    $('export-csv').href = `/api/v1${sessionPath(session.id)}/export.csv`;
    $('session-events').replaceChildren(); $('detail-status').textContent = t('loading_events');
    try { const result = await request(`${sessionPath(session.id)}/events`); if (current !== selection) return; events = result?.events || []; }
    catch (error) { if (current !== selection) return; $('session-events').textContent = error.message; await loadRange(...fullRange()); return; }
    renderEvents(); await loadRange(...fullRange());
  }
  function row(session) {
    const button = document.createElement('button'); button.className = 'session-row'; button.setAttribute('aria-pressed', 'false');
    const values = [[t('start'), date(session.started_at, true)], [t('duration'), duration((session.last_sample_at || session.started_at) - session.started_at)], [t('source'), source(session.source)], [t('samples'), number(session.sample_count)], [t('events'), number(session.event_count)], [t('device'), session.device || t('no_device')], ...(session.vehicle ? [[t('vehicle'), session.vehicle]] : [])];
    for (const [label, value] of values) {
      const span = document.createElement('span'), small = document.createElement('small'); small.textContent = label; span.append(small, value); button.append(span);
    }
    button.onclick = () => select(session, button);
    const remove = document.createElement('button'); remove.className = 'danger quiet'; remove.textContent = t('delete');
    remove.setAttribute('aria-label', t('delete_session_label', { time: date(session.started_at, true) }));
    remove.onclick = () => deletion.open(selectedSource, session);
    const wrapper = document.createElement('div'); wrapper.className = 'session-item'; wrapper.append(button, remove); return wrapper;
  }
  async function load() {
    const current = ++listing; $('refresh-sessions').disabled = true;
    empty($('session-list'), t('loading_sessions'));
    try {
      const result = await request(deviceQuery(`/sessions?limit=50&source=${selectedSource}`)); if (current !== listing) return;
      const sessions = (result?.sessions || []).filter(session => session.source === selectedSource);
      if (sessions.length) $('session-list').replaceChildren(...sessions.map(row));
      else empty($('session-list'), t('no_sessions'));
    } catch (error) { if (current === listing) empty($('session-list'), error.message); }
    finally { if (current === listing) { $('refresh-sessions').disabled = false; } }
  }
  $('delete-all').onclick = () => deletion.open(selectedSource);
  $('refresh-sessions').onclick = load;
  $('reset-zoom').onclick = () => { if (selected) loadRange(...fullRange()); };
  function clear() {
    clearDetail(); deletion.close(); listing++;
    $('session-list').replaceChildren(); $('delete-message').textContent = ''; $('refresh-sessions').disabled = false;
  }
  return {
    load, clear,
    refreshLanguage() { clearDetail(); deletion.close(); $('delete-all').textContent = t(selectedSource === 'sim' ? 'delete_sim' : 'delete_real'); if (!$('sessions-view').hidden) load(); },
    setSource(source) {
      clear(); selectedSource = source;
      $('delete-all').textContent = source === 'sim' ? t('delete_sim') : t('delete_real');
    }
  };
}
