import { t } from './i18n.js';
import { $, date, number, eventName, empty } from './format.js';
const color = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
const colors = ['var(--chart-primary)', 'var(--chart-secondary)'];
const chartDefinitions = () => [
  [t('speed_rpm'), [['speed_kmh', t('signal_speed_kmh'), t('unit_speed_kmh')], ['rpm', t('signal_rpm'), t('unit_rpm')]]],
  [t('signal_voltage_v'), [['voltage_v', t('battery'), t('unit_voltage')]]],
  [t('fuel_trims'), [['stft_pct', t('label_stft'), t('unit_percent')], ['ltft_pct', t('label_ltft'), t('unit_percent')]]],
  [t('lambda'), [['o2_b1s1_v', t('lambda_short'), t('unit_voltage')]]],
  [t('signal_map_kpa'), [['map_kpa', t('label_map'), t('unit_pressure')]]],
  [t('pedal_throttle'), [['pedal_pct', t('pedal'), t('unit_percent')], ['throttle_pct', t('signal_throttle_pct'), t('unit_percent')]]]
];
function bounds(samples, key, shared) {
  const values = samples.flatMap(sample => shared.map(signal => sample.v?.[signal])).filter(Number.isFinite);
  if (!values.length) return null;
  let low = 0, high = values[0];
  for (const value of values) { low = Math.min(low, value); high = Math.max(high, value); }
  if (low === high) high = low + 1;
  return { low, high, key };
}
function drawGrid(ctx, width, height, from, to) {
  ctx.font = '11px system-ui'; ctx.strokeStyle = color('--line'); ctx.fillStyle = color('--muted');
  for (let i = 0; i < 5; i++) {
    const y = 34 + (height - 66) * i / 4;
    ctx.beginPath(); ctx.moveTo(12, y); ctx.lineTo(width - 12, y); ctx.stroke();
  }
  [0, .5, 1].forEach((fraction, index) => {
    ctx.textAlign = ['left', 'center', 'right'][index];
    ctx.fillText(date(from + (to - from) * fraction), 12 + (width - 24) * fraction, height - 8);
  });
}
function drawSeries(ctx, samples, key, scale, color, x, y) {
  ctx.strokeStyle = color; ctx.lineWidth = 1.8; ctx.beginPath(); let started = false;
  for (const sample of samples) {
    const value = sample.v?.[key];
    if (!Number.isFinite(value)) { started = false; continue; }
    if (started) ctx.lineTo(x(sample.t), y(value, scale));
    else { ctx.moveTo(x(sample.t), y(value, scale)); started = true; }
  }
  ctx.stroke();
  if (samples.length === 1 && Number.isFinite(samples[0].v?.[key])) {
    ctx.fillStyle = color; ctx.beginPath(); ctx.arc(x(samples[0].t), y(samples[0].v[key], scale), 3, 0, 2 * Math.PI); ctx.fill();
  }
}
function drawMarkers(ctx, events, from, to, x, height) {
  ctx.lineWidth = 1; ctx.strokeStyle = color('--critical'); ctx.fillStyle = color('--critical'); ctx.setLineDash([3, 4]);
  ctx.textAlign = 'left'; let lastLabel = -200;
  for (const event of events) {
    if (event.t < from || event.t > to) continue;
    const position = x(event.t);
    ctx.beginPath(); ctx.moveTo(position, 28); ctx.lineTo(position, height - 28); ctx.stroke();
    if (position - lastLabel > 120) {
      ctx.fillText(eventName(event).slice(0, 24), Math.min(position + 3, Math.max(12, x(to) - 155)), 17); lastLabel = position;
    }
  }
  ctx.setLineDash([]);
}
export function createCharts() {
  let observer, redraw = () => {};
  window.addEventListener('theme-change', () => redraw());
  return {
    clear() { observer?.disconnect(); redraw = () => {}; $('charts').replaceChildren(); },
    render(samples, events, from, to) {
      observer?.disconnect(); redraw = () => {}; $('charts').replaceChildren();
      if (!samples.length) return empty($('charts'), t('no_interval_samples'));
      const redraws = [];
      for (const [title, signals] of chartDefinitions()) {
        const card = document.createElement('section'); card.className = 'card chart-card';
        const heading = document.createElement('h3'); heading.textContent = title;
        const legend = document.createElement('div'); legend.className = 'chart-legend';
        const canvas = document.createElement('canvas'); canvas.setAttribute('role', 'img'); canvas.setAttribute('aria-label', t('chart_range', { title, from: date(from, true), to: date(to, true) }));
        const common = title !== t('speed_rpm');
        const scales = signals.map(([key]) => bounds(samples, key, common ? signals.map(s => s[0]) : [key]));
        signals.forEach(([key, name, unit], i) => {
          const item = document.createElement('span'), dot = document.createElement('i'); dot.className = 'legend-dot'; dot.style.background = colors[i];
          const scale = scales[i]; item.append(dot, `${name} · ${scale ? t('legend_range', { low: number(scale.low, 1), high: number(scale.high, 1), unit }) : t('no_data')}`); legend.append(item);
        });
        card.append(heading, legend, canvas); $('charts').append(card);
        redraws.push(() => {
          const width = canvas.clientWidth, height = 190, ratio = window.devicePixelRatio || 1;
          canvas.width = width * ratio; canvas.height = height * ratio;
          const ctx = canvas.getContext('2d'); if (!ctx || !width) return;
          ctx.scale(ratio, ratio); drawGrid(ctx, width, height, from, to);
          const x = t => 12 + (t - from) / Math.max(1, to - from) * (width - 24);
          const y = (value, scale) => height - 32 - (value - scale.low) / (scale.high - scale.low) * (height - 66);
          signals.forEach(([key], i) => { if (scales[i]) drawSeries(ctx, samples, key, scales[i], color(i ? '--chart-secondary' : '--chart-primary'), x, y); });
          drawMarkers(ctx, events, from, to, x, height);
          if (scales.every(scale => !scale)) { ctx.fillStyle = color('--muted'); ctx.textAlign = 'center'; ctx.fillText(t('signal_unavailable'), width / 2, height / 2); }
        });
      }
      redraw = () => redraws.forEach(draw => draw());
      observer = new ResizeObserver(redraw); observer.observe($('charts')); redraw();
    }
  };
}
