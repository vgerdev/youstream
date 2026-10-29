import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiGet } from './client';

describe('apiGet', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('returns parsed JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"status":"UP"}', { status: 200 })));
    await expect(apiGet('/health')).resolves.toEqual({ status: 'UP' });
    expect(fetch).toHaveBeenCalledWith('/api/health', expect.anything());
  });

  it('throws ApiError on non-2xx', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 404 })));
    await expect(apiGet('/missing')).rejects.toBeInstanceOf(ApiError);
  });
});
