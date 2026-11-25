import { api } from './api.ts';
import { publishSessionChange, withSessionLock } from './sessionCoordinator.ts';
export { ApiError as AuthError } from './api.ts';
export async function submitAuth(action: 'login' | 'sign-up' | 'email-verifications/verify' | 'email-verifications/resend', body: Record<string, string>) {
  if (action === 'login') {
    await withSessionLock(async () => {
      await api(`/api/v1/auth/${action}`, { method: 'POST', body, retryAuth: false });
      publishSessionChange('login');
    });
  } else await api(`/api/v1/auth/${action}`, { method: 'POST', body, retryAuth: false });
}
