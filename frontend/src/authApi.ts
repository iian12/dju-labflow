import { api } from './api.ts';
export { ApiError as AuthError } from './api.ts';
export async function submitAuth(action: 'login' | 'sign-up' | 'email-verifications/verify' | 'email-verifications/resend', body: Record<string, string>) {
  await api(`/api/v1/auth/${action}`, { method: 'POST', body, retryAuth: false });
}
