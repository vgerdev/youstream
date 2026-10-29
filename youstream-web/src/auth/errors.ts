import { ApiError } from '../api/client';

const MESSAGES: Record<string, string> = {
  invalid_credentials: 'Wrong username or password.',
  forbidden: 'Your account is not allowed to use YouStream.',
  unavailable: 'The sign-in service is unavailable. Try again in a moment.',
  too_many_requests: 'Too many failed attempts. Wait a few minutes and try again.',
  conflict: 'That account is already linked to a different YouStream user.',
  session_expired: 'The sign-in took too long. Please try again.',
  cancelled: 'Sign-in with Microsoft was cancelled.',
  microsoft_error: 'Microsoft could not complete the sign-in.',
  invalid_token: 'Your session has expired. Please sign in again.',
};

export function authErrorMessage(error: unknown): string {
  const code = error instanceof ApiError ? error.code : typeof error === 'string' ? error : '';
  return MESSAGES[code] ?? (error instanceof Error ? error.message : 'Sign-in failed.');
}
