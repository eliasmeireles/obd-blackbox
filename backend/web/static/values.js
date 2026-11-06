import { t, hasTranslation } from './i18n.js';
import { number } from './format.js';
export const signal = key => hasTranslation('signal_' + key) ? [t('signal_' + key), t('unit_' + key)] : [key, ''];
export function valueText(value) {
  if (value === null || value === undefined) return t('unavailable');
  if (typeof value === 'number') return number(value, Number.isInteger(value) ? 0 : 2);
  if (typeof value === 'boolean') return t(value ? 'yes' : 'no');
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}
export function measured(key, value) {
  const [, unit] = signal(key);
  return `${valueText(value)}${value != null && unit ? ` ${unit}` : ''}`;
}
