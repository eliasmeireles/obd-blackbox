import { t, locale } from './i18n.js';
const numbers = new Map();
export const $ = (id) => document.getElementById(id);
export function number(value, digits = 0) {
  if (!Number.isFinite(value)) return t('unavailable');
  const key = `${locale()}:${digits}`;
  if (!numbers.has(key)) numbers.set(key, new Intl.NumberFormat(locale(), { maximumFractionDigits: digits, minimumFractionDigits: digits }));
  return numbers.get(key).format(value);
}
export function date(value, full = false) {
  if (!value || !Number.isFinite(new Date(value).getTime())) return t('unavailable');
  return new Intl.DateTimeFormat(locale(), full ? { dateStyle: 'short', timeStyle: 'medium' } : { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(value));
}
export function duration(ms) {
  const seconds = Math.max(0, Math.floor(ms / 1000));
  if (seconds < 60) return `${number(seconds)} ${t('unit_seconds')}`;
  if (seconds < 3600) return `${number(Math.floor(seconds / 60))} ${t('unit_minutes')}`;
  return `${number(Math.floor(seconds / 3600))} ${t('unit_hours')} ${number(Math.floor(seconds % 3600 / 60))} ${t('unit_minutes')}`;
}
export const source = value => t({ sim: 'source_sim', obd: 'source_obd' }[value] || 'unknown_source');
export function empty(target, message) {
  const p = document.createElement('p'); p.className = 'empty'; p.textContent = message; target.replaceChildren(p);
}
export function eventName(event) { return event.automatic ? t('automatic_event') : (event.label || t('event')); }
export function eventRows(target, events) {
  if (!events.length) return empty(target, t('no_events'));
  target.replaceChildren(...events.map(event => {
    const row = document.createElement('div'); row.className = 'event-row';
    const label = document.createElement('span'); label.className = 'event-title'; label.textContent = eventName(event);
    if (event.automatic) { const tag = document.createElement('span'); tag.className = 'event-tag'; tag.textContent = t('automatic_tag'); label.append(tag); }
    const time = document.createElement('time'); time.dateTime = new Date(event.t).toISOString(); time.textContent = date(event.t, true);
    row.append(label, time); return row;
  }));
}
