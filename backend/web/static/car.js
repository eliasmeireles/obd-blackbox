import { t, locale } from './i18n.js';
import { request, post } from './api.js';
import { $, date, empty } from './format.js';
import { describeDTC } from './dtc.js';
import { signal, measured, valueText } from './values.js';
import { deviceQuery } from './devices.js';
const reasons = () => ({ connect: t('reason_connect'), manual: t('reason_manual'), before_clear: t('reason_before'), after_clear: t('reason_after') });
const monitors = () => ({ misfire: t('monitor_misfire'), fuel_system: t('fuel_system'), components: t('monitor_components'), catalyst: t('monitor_catalyst'), heated_catalyst: t('monitor_heated_catalyst'), evap: t('monitor_evap'), secondary_air: t('monitor_air'), ac_refrigerant: t('monitor_ac'), o2_sensor: t('monitor_o2'), o2_heater: t('monitor_o2_heater'), egr: t('monitor_egr') });
const fuelSystems = () => ({ open_loop_cold: t('fuel_cold'), closed_loop: t('fuel_closed'), open_loop_load: t('fuel_load'), open_loop_fault: t('fuel_fault'), closed_loop_fault: t('fuel_closed_fault') });
function node(tag, text, className = '') {
  const element = document.createElement(tag); element.textContent = text; element.className = className; return element;
}
function table(title, headers, rows) {
  const card = node('article', '', 'card diagnostics-card'); card.append(node('h3', title));
  if (!rows.length) { empty(card, t('no_reading_info', { title })); return card; }
  const element = document.createElement('table'), head = document.createElement('thead'), body = document.createElement('tbody'), heading = document.createElement('tr');
  headers.forEach(text => heading.append(node('th', text))); head.append(heading);
  rows.forEach(values => { const row = document.createElement('tr'); values.forEach(text => row.append(node('td', text))); body.append(row); });
  element.append(head, body); card.append(element); return card;
}
const valueRows = values => Object.entries(values || {}).map(([key, value]) => [signal(key)[0], measured(key, value)]);
export function createCar() {
  let selectedSource = 'obd', generation = 0, snapshots = [], device = null, pending = false, active = false, revision = 0;
  const dialog = $('clear-dialog');
  function buttons() {
    $('read-diagnostics').disabled = pending || !device;
    $('clear-dtcs').disabled = pending || !device;
    $('clear-submit').disabled = pending || !device;
  }

  function show(entry) {
    const data = entry.data || {};
    $('car-time').textContent = `${date(entry.t, true)} · ${reasons()[data.reason] || valueText(data.reason)}`;
    $('car-mil').textContent = data.mil_on === true ? t('mil_on') : data.mil_on === false ? t('mil_off') : t('mil_unknown');
    $('car-mil').className = data.mil_on === true ? 'alert-banner critical' : 'alert-banner';
    $('car-count').textContent = t('dtc_count', { count: valueText(data.dtc_count) });
    const codeRows = [];
    for (const [kind, label] of [['stored', t('stored')], ['pending', t('pending')], ['permanent', t('permanent')]]) {
      for (const code of Array.isArray(data.dtcs?.[kind]) ? data.dtcs[kind] : []) codeRows.push([label, valueText(code), describeDTC(String(code))]);
    }
    const codes = table(t('trouble_codes'), [t('type'), t('code'), t('description')], codeRows);
    if (!codeRows.length) { codes.replaceChildren(node('h3', t('trouble_codes')), node('p', data.dtcs ? t('no_codes') : t('codes_unavailable'), 'muted')); }
    const freeze = table(t('freeze_frame'), [t('signal'), t('value')], valueRows(data.freeze_frame?.values));
    freeze.prepend(node('p', t('freeze_reading', { time: date(entry.t, true), code: valueText(data.freeze_frame?.dtc) }), 'muted small'));
    const readiness = table(t('readiness'), [t('monitor'), t('available_question'), t('complete_question')], (Array.isArray(data.monitors) ? data.monitors : []).map(monitor => [Object.hasOwn(monitors(), monitor.name) ? monitors()[monitor.name] : valueText(monitor.name), valueText(monitor.available), valueText(monitor.complete)]));
    const identification = table(t('identification'), [t('field'), t('value')], [[t('vin'), data.vin], [t('calibration'), data.calibration_id], [t('ecu_name'), data.ecu_name], [t('ecu_id'), data.ecu_id], [t('protocol'), data.protocol], [t('adapter'), data.adapter], [t('supported_pids'), Array.isArray(data.supported_pids) ? data.supported_pids.join(', ') : data.supported_pids], [t('fuel_system'), Object.hasOwn(fuelSystems(), data.fuel_system) ? fuelSystems()[data.fuel_system] : data.fuel_system]].map(([key, value]) => [key, valueText(value)]));
    $('car-detail').replaceChildren(codes, freeze, readiness, identification, table(t('other_readings'), [t('signal'), t('value')], valueRows(data.values)));
    $('car-summary').hidden = false;
    for (const [index, button] of [...$('car-history').children].entries()) button.setAttribute('aria-pressed', String(snapshots[index] === entry));
  }
  async function load() {
    const current = ++generation;
    try {
      const result = await request(deviceQuery(`/diagnostics?source=${selectedSource}&limit=200`));
      if (!active || current !== generation) return;
      snapshots = result?.diagnostics || [];
      $('car-history').replaceChildren(); $('car-detail').replaceChildren(); $('car-summary').hidden = true;
      if (!snapshots.length) { empty($('car-detail'), t('no_diagnostics')); return; }
      for (const entry of snapshots) {
        const codes = ['stored', 'pending', 'permanent'].flatMap(kind => Array.isArray(entry.data?.dtcs?.[kind]) ? entry.data.dtcs[kind] : []).join(', ');
        const button = node('button', `${date(entry.t, true)} · ${reasons()[entry.data?.reason] || valueText(entry.data?.reason)} · ${codes || t('no_reported_codes')}`, 'quiet');
        button.onclick = () => show(entry); $('car-history').append(button);
      }
      show(snapshots[0]); $('car-error').textContent = '';
    } catch (error) { if (active && current === generation) $('car-error').textContent = error.message; }
  }
  async function command(name) {
    if (pending || !device || !active) return;
    const current = revision;
    pending = true; buttons(); $('command-result').textContent = t('command_wait');
    try {
      const result = await post('/commands', { device: device.name, name });
      if (active && current === revision) { $('command-result').className = result.ok ? '' : 'error'; $('command-result').textContent = `${result.ok ? t('command_done') : t('command_failed')}: ${result.message || t('no_app_message')}`; }
    } catch (error) {
      if (active && current === revision) { $('command-result').className = 'error'; $('command-result').textContent = error.status === 409 ? t('command_offline') : error.status === 504 ? t('command_timeout') : error.message; }
    } finally { pending = false; buttons(); if (active) load(); }
  }
  $('read-diagnostics').onclick = () => command('read_diagnostics');
  $('clear-dtcs').onclick = () => { if (device && !pending) dialog.showModal(); };
  $('clear-cancel').onclick = () => dialog.close();
  $('clear-form').onsubmit = event => { event.preventDefault(); dialog.close(); command('clear_dtcs'); };
  $('refresh-car').onclick = load;
  return {
    load,
    setDevices(registry, selected) {
      device = selected === null ? null : registry.find(item => item.name === selected && item.connected) || null;
      $('device-status').textContent = device ? t('device_connected_since', { time: new Intl.DateTimeFormat(locale(), { hour: '2-digit', minute: '2-digit' }).format(new Date(device.connected_since)) }) : selected === null && registry.length > 1 ? t('select_device_command') : t('app_offline');
      if (!device) dialog.close();
      buttons();
    },
    start(source) { active = true; revision++; selectedSource = source; load(); },
    setSource(source) { generation++; revision++; selectedSource = source; snapshots = []; dialog.close(); $('car-summary').hidden = true; $('car-detail').replaceChildren(); $('car-history').replaceChildren(); $('command-result').textContent = ''; if (active) load(); },
    stop() { active = false; generation++; revision++; device = null; buttons(); dialog.close(); $('car-summary').hidden = true; $('command-result').textContent = ''; $('car-detail').replaceChildren(); $('car-history').replaceChildren(); }
  };
}
