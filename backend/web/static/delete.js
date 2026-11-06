import { t } from './i18n.js';
import { del, sessionPath } from './api.js';
import { $, date, number } from './format.js';
import { deviceQuery, selectedDevice } from './devices.js';
export function createDeletion(onDeleted) {
  const dialog = $('delete-dialog');
  let pending = false, action, revision = 0;
  function update() { $('delete-submit').disabled = pending || (action?.bulk && $('delete-word').value !== t('confirmation_word')); }
  function close() { revision++; dialog.close(); action = null; }
  function open(source, session) {
    if (pending) return;
    const bulk = !session;
    action = { source, session, bulk, device: selectedDevice() };
    $('delete-title').textContent = bulk ? (source === 'sim' ? t('delete_sim') : t('delete_real')) : t('delete_session');
    $('delete-description').textContent = bulk
      ? t('bulk_delete_description', { source: t(source === 'sim' ? 'simulation' : 'real_data'), device: action.device === null ? t('all') : action.device || t('no_device') })
      : t('single_delete_description', { time: date(session.started_at, true), samples: number(session.sample_count), events: number(session.event_count) });
    $('delete-confirmation').hidden = !bulk;
    $('delete-word').value = ''; $('delete-error').textContent = ''; update();
    dialog.showModal();
  }
  $('delete-word').oninput = update;
  $('delete-cancel').onclick = () => { if (!pending) close(); };
  dialog.addEventListener('cancel', event => { event.preventDefault(); if (!pending) close(); });
  $('delete-form').onsubmit = async event => {
    event.preventDefault();
    if (!action || pending || (action.bulk && $('delete-word').value !== t('confirmation_word'))) return;
    const target = action, current = revision;
    pending = true; update(); $('delete-cancel').disabled = true;
    $('delete-error').textContent = ''; $('delete-message').textContent = '';
    try {
      const result = await del(deviceQuery(target.bulk ? `/sessions?source=${target.source}` : sessionPath(target.session.id), target.device));
      if (current === revision) {
        close();
        $('delete-message').className = '';
        $('delete-message').textContent = t('sessions_deleted', { count: number(result.deleted) });
      }
      onDeleted(target.source, target.session?.id);
    } catch (error) {
      if (current === revision) {
        const message = error.status === 403 ? t('delete_forbidden') : t('delete_failed');
        $('delete-error').textContent = message;
        $('delete-message').className = 'error'; $('delete-message').textContent = message;
      }
    } finally { pending = false; $('delete-cancel').disabled = false; update(); }
  };
  return { open, close };
}
