import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api, loginWithLdap, refreshSession, setAccessToken } from './client';

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const session = (token: string) => ({
  accessToken: token,
  expiresAt: '2030-01-01T00:00:00Z',
  user: { id: 1, username: 'alice' },
});

describe('api client', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    setAccessToken(null);
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => vi.unstubAllGlobals());

  it('returns parsed JSON', async () => {
    fetchMock.mockResolvedValue(json({ status: 'UP' }));
    await expect(api('/health')).resolves.toEqual({ status: 'UP' });
    expect(fetchMock).toHaveBeenCalledWith('/api/health', expect.objectContaining({ method: 'GET' }));
  });

  it('throws ApiError with the server error code', async () => {
    fetchMock.mockResolvedValue(json({ error: 'invalid_credentials', message: 'nope' }, 401));
    const err = await api('/auth/login', { method: 'POST', body: {} }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 401, code: 'invalid_credentials', message: 'nope' });
  });

  it('sends the access token after login', async () => {
    fetchMock.mockResolvedValueOnce(json(session('t1'))).mockResolvedValueOnce(json({ id: 1 }));
    await loginWithLdap('alice', 'pw');
    await api('/me');
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe('Bearer t1');
  });

  it('refreshes once on 401 and retries with the new token', async () => {
    setAccessToken('expired');
    fetchMock
      .mockResolvedValueOnce(json({ error: 'invalid_token' }, 401))
      .mockResolvedValueOnce(json(session('fresh')))
      .mockResolvedValueOnce(json({ id: 1 }));

    await expect(api('/me')).resolves.toEqual({ id: 1 });
    expect(fetchMock.mock.calls[1][0]).toBe('/api/auth/refresh');
    expect(fetchMock.mock.calls[2][1].headers.Authorization).toBe('Bearer fresh');
  });

  it('shares one refresh between concurrent callers', async () => {
    fetchMock.mockResolvedValue(json(session('s')));
    const [a, b] = await Promise.all([refreshSession(), refreshSession()]);
    expect(a).toEqual(b);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('resolves null when the refresh cookie is missing', async () => {
    fetchMock.mockResolvedValue(json({ error: 'invalid_token' }, 401));
    await expect(refreshSession()).resolves.toBeNull();
  });
});
