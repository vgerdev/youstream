import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setAccessToken } from '../api/client';
import { forgetUpload, uploadFile } from './uploader';

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const status = (receivedBytes: number) => ({ id: 'u1', filename: 'a.mp4', totalBytes: 10, receivedBytes, chunkBytes: 4 });

describe('uploadFile', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  const file = new File(['0123456789'], 'a.mp4', { lastModified: 1 });

  beforeEach(() => {
    setAccessToken('t');
    forgetUpload(file);
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => vi.unstubAllGlobals());

  it('sends the file in chunks with Content-Range', async () => {
    fetchMock
      .mockResolvedValueOnce(json(status(0), 201))
      .mockResolvedValueOnce(json(status(4)))
      .mockResolvedValueOnce(json(status(8)))
      .mockResolvedValueOnce(json(status(10)));
    const progress: number[] = [];

    await expect(uploadFile(file, (p) => progress.push(p.sent))).resolves.toBe('u1');

    const ranges = fetchMock.mock.calls.slice(1).map((c) => c[1].headers['Content-Range']);
    expect(ranges).toEqual(['bytes 0-3/10', 'bytes 4-7/10', 'bytes 8-9/10']);
    expect(progress).toEqual([0, 4, 8, 10]);
  });

  it('resumes from the offset the server already has', async () => {
    fetchMock.mockResolvedValueOnce(json(status(0), 201)).mockRejectedValueOnce(new TypeError('network down'));
    const controller = new AbortController();
    const first = uploadFile(file, () => undefined, controller.signal);
    // give up during the retry wait, like closing the tab
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    controller.abort();
    await expect(first).rejects.toThrow();

    fetchMock.mockReset();
    fetchMock.mockResolvedValueOnce(json(status(8))).mockResolvedValueOnce(json(status(10)));
    const progress: { sent: number; resumed: boolean }[] = [];
    await uploadFile(file, (p) => progress.push(p));

    expect(fetchMock.mock.calls[0][0]).toBe('/api/uploads/u1');
    expect(fetchMock.mock.calls[1][1].headers['Content-Range']).toBe('bytes 8-9/10');
    expect(progress[0]).toEqual({ sent: 8, total: 10, resumed: true });
  });

  it('follows the server offset on 409', async () => {
    fetchMock
      .mockResolvedValueOnce(json(status(0), 201))
      .mockResolvedValueOnce(json(status(8), 409))
      .mockResolvedValueOnce(json(status(10)));

    await uploadFile(file, () => undefined);

    expect(fetchMock.mock.calls[2][1].headers['Content-Range']).toBe('bytes 8-9/10');
  });
});
