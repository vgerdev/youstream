import { ApiError } from '../api/client';
import { createUpload, getUpload, putChunk, type UploadStatus } from '../api/videos';

export interface UploadProgress {
  sent: number;
  total: number;
  /** true when this run continued an earlier, interrupted upload */
  resumed: boolean;
}

const storageKey = (file: File) => `youstream.upload:${file.name}:${file.size}:${file.lastModified}`;

function remember(file: File, id: string | null) {
  try {
    if (id) localStorage.setItem(storageKey(file), id);
    else localStorage.removeItem(storageKey(file));
  } catch {
    // storage unavailable (private mode): uploads still work, they just cannot resume after a reload
  }
}

function recall(file: File): string | null {
  try {
    return localStorage.getItem(storageKey(file));
  } catch {
    return null;
  }
}

const sleep = (ms: number, signal?: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    const t = setTimeout(resolve, ms);
    signal?.addEventListener('abort', () => {
      clearTimeout(t);
      reject(new DOMException('Aborted', 'AbortError'));
    });
  });

/** Network errors and 5xx are worth retrying; 4xx (except 409) are not. */
const retryable = (e: unknown) => !(e instanceof ApiError) || e.status >= 500;

/**
 * Uploads a file in chunks, resuming a previous attempt for the same file if the server still has it.
 * Resolves to the upload id, ready for {@link completeUpload}.
 */
export async function uploadFile(
  file: File,
  onProgress: (p: UploadProgress) => void,
  signal?: AbortSignal,
): Promise<string> {
  let status: UploadStatus | null = null;
  let resumed = false;
  const previous = recall(file);
  if (previous) {
    try {
      status = await getUpload(previous);
      resumed = status.receivedBytes > 0;
    } catch {
      remember(file, null);
    }
  }
  status ??= await createUpload(file.name, file.size);
  remember(file, status.id);

  let offset = status.receivedBytes;
  onProgress({ sent: offset, total: file.size, resumed });
  let failures = 0;
  while (offset < file.size) {
    const chunk = file.slice(offset, Math.min(file.size, offset + status.chunkBytes));
    try {
      const next = await putChunk(status.id, chunk, offset, file.size, signal);
      offset = next.receivedBytes;
      failures = 0;
      onProgress({ sent: offset, total: file.size, resumed });
    } catch (e) {
      if (signal?.aborted || !retryable(e) || ++failures > 5) throw e;
      await sleep(1000 * 2 ** failures, signal);
    }
  }
  return status.id;
}

/** Forget the resume marker once the upload has been completed or abandoned. */
export const forgetUpload = (file: File) => remember(file, null);
