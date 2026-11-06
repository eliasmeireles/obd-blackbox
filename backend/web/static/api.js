import { t } from './i18n.js';
export class APIError extends Error {
  constructor(status) { super(status === 401 ? t('session_expired') : t('load_failed')); this.status = status; }
}
export async function request(path, options = {}) {
  const response = await fetch(`/api/v1${path}`, { credentials: 'same-origin', cache: 'no-store', ...options });
  if (!response.ok) {
    if (response.status === 401 && path !== '/auth/login') window.dispatchEvent(new Event('auth-expired'));
    throw new APIError(response.status);
  }
  if (response.status === 204) return null;
  const body = await response.text();
  return body ? JSON.parse(body) : null;
}
export function post(path, body) {
  return request(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
}
export const sessionPath = (id) => `/sessions/${encodeURIComponent(id)}`;

export function del(path) { return request(path, { method: 'DELETE' }); }
