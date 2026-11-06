import { t, hasTranslation } from './i18n.js';
export function describeDTC(value) {
  const code = value.toUpperCase();
  if (hasTranslation('dtc_' + code)) return t('dtc_' + code);
  const key = ['dtc_family_' + code.slice(0, 3), 'dtc_family_' + code[0]].find(hasTranslation);
  return t('dtc_fallback', { family: t(key || 'dtc_unknown') });
}
