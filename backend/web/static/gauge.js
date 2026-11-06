import { $, number } from './format.js';
import { t } from './i18n.js';
function point(value) {
  const angle = (135 + value / 7000 * 270) * Math.PI / 180;
  return [160 + 116 * Math.cos(angle), 145 + 116 * Math.sin(angle)];
}
function arc(from, to) {
  const a = point(from), b = point(to);
  return `M ${a.join(' ')} A 116 116 0 ${to - from > 4666 ? 1 : 0} 1 ${b.join(' ')}`;
}
export function initGauge() {
  $('gauge-track').setAttribute('d', arc(0, 7000));
  $('gauge-red').setAttribute('d', arc(6000, 7000));
}
export function renderGauge(v) {
  const rpm = Math.max(0, Math.min(7000, v.rpm || 0));
  $('gauge-fill').setAttribute('d', rpm ? arc(0, rpm) : '');
  $('speed').textContent = number(v.speed_kmh);
  $('rpm').textContent = `${number(v.rpm)} ${t('unit_rpm')}`;
  $('gauge-title').textContent = `${number(v.speed_kmh)} ${t('unit_speed')}, ${number(v.rpm)} ${t('unit_rpm')}`;
}
