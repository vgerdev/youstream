export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export type IdentityProvider = 'LDAP' | 'ENTRA';

export interface User {
  id: number;
  username: string;
  handle: string;
  displayName: string;
  email: string | null;
  bio: string;
  admin: boolean;
  subscriberCount: number;
  createdAt: string;
  identities: IdentityProvider[];
}

export interface Session {
  accessToken: string;
  expiresAt: string;
  user: User;
}

export interface AuthMethods {
  ldap: boolean;
  microsoft: boolean;
}

export interface Health {
  status: string;
  database: string;
}

// The access token lives only in memory; the refresh token is an HttpOnly cookie the browser sends to /api/auth.
let accessToken: string | null = null;
let refreshing: Promise<Session | null> | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

async function toError(res: Response): Promise<ApiError> {
  let code = 'error';
  let message = `${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: string; message?: string };
    code = body.error ?? code;
    message = body.message ?? message;
  } catch {
    // not JSON
  }
  return new ApiError(res.status, code, message);
}

/** Exchanges the refresh cookie for a new access token; resolves to null when not signed in. */
export function refreshSession(): Promise<Session | null> {
  refreshing ??= (async () => {
    try {
      const res = await fetch('/api/auth/refresh', { method: 'POST', credentials: 'same-origin' });
      if (!res.ok) {
        setAccessToken(null);
        return null;
      }
      const session = (await res.json()) as Session;
      setAccessToken(session.accessToken);
      return session;
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

interface RequestOptions {
  method?: string;
  body?: unknown;
}

/** JSON request to /api; on 401 with an expired access token it refreshes once and retries. */
export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const send = () => {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
    return fetch(`/api${path}`, {
      method: options.method ?? 'GET',
      headers,
      credentials: 'same-origin',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  };

  let res = await send();
  if (res.status === 401 && accessToken) {
    if (await refreshSession()) {
      res = await send();
    }
  }
  if (!res.ok) throw await toError(res);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const getHealth = () => api<Health>('/health');
export const getAuthMethods = () => api<AuthMethods>('/auth/methods');
export const getMe = () => api<User>('/me');

export async function loginWithLdap(username: string, password: string): Promise<Session> {
  const session = await api<Session>('/auth/login', { method: 'POST', body: { username, password } });
  setAccessToken(session.accessToken);
  return session;
}

export async function logout(): Promise<void> {
  try {
    await api<void>('/auth/logout', { method: 'POST' });
  } finally {
    setAccessToken(null);
  }
}

export const microsoftLoginUrl = (returnTo: string) =>
  `/api/auth/microsoft/login?returnTo=${encodeURIComponent(returnTo)}`;

export const linkLdap = (username: string, password: string) =>
  api<User>('/me/identities/ldap', { method: 'POST', body: { username, password } });

export const startMicrosoftLink = () => api<{ url: string }>('/me/identities/microsoft', { method: 'POST' });
