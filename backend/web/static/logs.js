import { t, locale } from './i18n.js';
import { request } from './api.js';
import { $, number } from './format.js';
import { deviceQuery } from './devices.js';
function logTime(value) {
  const time = new Date(value);
  if (!Number.isFinite(time.getTime())) return t('unavailable');
  const clock = time.toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3 });
  return time.toDateString() === new Date().toDateString() ? clock : `${time.toLocaleDateString(locale())} ${clock}`;
}
export function createLogs() {
  let active = false, timer, generation = 0, entries = [], updatedAt = null;
  function render() {
    const container = $('logs-scroll'), position = container.scrollTop, pagePosition = window.scrollY;
    const query = $('logs-search').value.toLocaleLowerCase(locale());
    const filtered = entries.filter(entry => [entry.message, entry.tag, entry.device, entry.level].some(value => String(value || '').toLocaleLowerCase(locale()).includes(query)));
    $('logs-body').replaceChildren(...filtered.map(entry => {
      const row = document.createElement('tr');
      for (const [index, value] of [logTime(entry.t), { debug: t('debug'), info: t('info'), warn: t('warn'), error: t('error') }[entry.level] || entry.level, entry.tag, entry.message, entry.device || t('no_device')].entries()) {
        const cell = document.createElement('td'); cell.textContent = value;
        if (index === 1) cell.className = ['debug', 'info', 'warn', 'error'].includes(entry.level) ? `log-level ${entry.level}` : 'log-level';
        if (index === 3) cell.className = 'log-message';
        row.append(cell);
      }
      return row;
    }));
    $('logs-empty').hidden = filtered.length > 0;
    $('logs-empty').textContent = t('no_logs');
    if (updatedAt) $('logs-status').textContent = t('logs_updated', { time: logTime(updatedAt), count: number(1000) });
    container.scrollTop = position; window.scrollTo({ top: pagePosition, behavior: 'instant' });
  }
  function schedule() {
    clearTimeout(timer);
    if (active && $('logs-auto').checked) timer = setTimeout(load, 5000);
  }
  async function load() {
    clearTimeout(timer); const current = ++generation;
    const params = new URLSearchParams({ limit: 1000, order: 'desc', since: Date.now() - Number($('logs-period').value) });
    for (const key of ['level', 'tag']) if ($('logs-' + key).value) params.set(key, $('logs-' + key).value);
    try {
      const result = await request(deviceQuery(`/logs?${params}`));
      if (!active || current !== generation) return;
      entries = result?.logs || []; updatedAt = Date.now(); render();
    } catch (error) { if (active && current === generation) $('logs-status').textContent = error.message; }
    finally { if (active && current === generation) schedule(); }
  }
  for (const key of ['level', 'tag', 'period']) $('logs-' + key).onchange = () => { if (active) load(); };
  $('logs-search').oninput = render;
  $('logs-auto').onchange = schedule;
  $('refresh-logs').onclick = load;
  return {
    load, render,
    reset() { generation++; entries = []; updatedAt = null; $('logs-body').replaceChildren(); $('logs-status').textContent = ''; if (active) load(); },
    start() { if (active) return; active = true; load(); },
    stop() { active = false; generation++; clearTimeout(timer); entries = []; updatedAt = null; $('logs-status').textContent = ''; $('logs-body').replaceChildren(); }
  };
}
