import { t } from './i18n.js';
import { $, number } from './format.js';
import { signal, valueText } from './values.js';
const primary = () => [
  ['consumption', t('consumption'), t('unit_fuel_rate'), 1], ['coolant_c', t('engine'), t('unit_temperature'), 0], ['voltage_v', t('battery'), t('unit_voltage'), 1],
  ['load_pct', t('load'), t('unit_percent'), 0], ['throttle_pct', t('signal_throttle_pct'), t('unit_percent'), 0], ['pedal_pct', t('pedal'), t('unit_percent'), 0]
];
const secondary = () => [
  ['map_kpa', t('signal_map_kpa'), t('unit_pressure'), 0], ['stft_pct', t('short_trim'), t('unit_percent'), 1],
  ['ltft_pct', t('long_trim'), t('unit_percent'), 1], ['o2_b1s1_v', t('lambda'), t('unit_voltage'), 2],
  ['intake_c', t('signal_intake_c'), t('unit_temperature'), 0], ['fuel_level_pct', t('fuel'), t('unit_percent'), 0]
];
const elements = new Map();
const extras = new Map();
const rendered = new Set(['speed_kmh', 'rpm', ...primary().filter(tile => tile[0] !== 'consumption').map(tile => tile[0]), ...secondary().map(tile => tile[0])]);
function createTile([key, label, unit], compact, registry = elements) {
  const card = document.createElement('article'); card.className = `card ${compact ? 'compact' : 'tile'}`;
  const heading = document.createElement('h3'); heading.textContent = label;
  const value = document.createElement('span'); value.className = 'tile-value';
  const suffix = document.createElement('span'); suffix.className = 'unit'; suffix.textContent = unit;
  card.append(heading, value, suffix); registry.set(key, { card, value, suffix, compact }); return card;
}
export function initTiles() {
  extras.clear(); $('extra-tiles').replaceChildren();
  $('main-tiles').replaceChildren(...primary().map(tile => createTile(tile, false)));
  $('secondary-tiles').replaceChildren(...secondary().map(tile => createTile(tile, true)));
}
export function renderTiles(v) {
  const extra = $('extra-tiles');
  for (const key of [...extras.keys()]) {
    if (!Object.hasOwn(v, key)) { extras.get(key).card.remove(); extras.delete(key); }
  }
  for (const [key, value] of Object.entries(v)) {
    if (rendered.has(key)) continue;
    if (!extras.has(key)) { const [label, unit] = signal(key); extra.append(createTile([key, label, unit], true, extras)); }
    extras.get(key).value.textContent = valueText(value, key);
  }
  extra.hidden = !extra.children.length;
  const distance = v.speed_kmh >= 3 && v.fuel_rate_lph_est > 0;
  const consumption = distance ? v.speed_kmh / v.fuel_rate_lph_est : v.fuel_rate_lph_est;
  for (const [key, , unit, digits] of [...primary(), ...secondary()]) {
    const tile = elements.get(key);
    tile.value.textContent = number(key === 'consumption' ? consumption : v[key], digits);
    tile.suffix.textContent = key === 'consumption' && distance ? t('unit_consumption') : unit;
    let severity = '';
    if (key === 'coolant_c' && v[key] > 100) severity = v[key] > 105 ? 'critical' : 'warning';
    if (key === 'voltage_v' && Number.isFinite(v[key]) && v.rpm >= 300 && v[key] < 13) severity = v[key] < 12 ? 'critical' : 'warning';
    tile.card.className = `card ${tile.compact ? 'compact' : 'tile'} ${severity}`;
    tile.card.setAttribute('aria-label', `${tile.card.querySelector('h3').textContent}: ${tile.value.textContent} ${tile.suffix.textContent}${severity === 'critical' ? t('critical_suffix') : severity ? t('warning_suffix') : ''}`);
  }
}
