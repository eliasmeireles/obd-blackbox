import { t, hasTranslation } from './i18n.js';
import { number } from './format.js';
export const signal = key => hasTranslation('signal_' + key) ? [t('signal_' + key), t('unit_' + key)] : [key, ''];
// Estimates and coarse readings would suggest a precision they do not have with two decimals.
const DECIMALS = { fuel_liters_est: 1, fuel_range_km_est: 0, fuel_rate_lph_est: 1, timing_deg: 1, cmd_throttle_pct: 0 };
export function valueText(value, key) {
  if (value === null || value === undefined) return t('unavailable');
  if (typeof value === 'number') return number(value, DECIMALS[key] ?? (Number.isInteger(value) ? 0 : 2));
  if (typeof value === 'boolean') return t(value ? 'yes' : 'no');
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}
export function measured(key, value) {
  const [, unit] = signal(key);
  return `${valueText(value, key)}${value != null && unit ? ` ${unit}` : ''}`;
}
