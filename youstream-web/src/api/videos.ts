import { api, apiResponse } from './client';

export type Visibility = 'PUBLIC' | 'UNLISTED' | 'PRIVATE';
export type VideoStatus = 'UPLOADING' | 'UPLOADED' | 'PROCESSING' | 'READY' | 'FAILED';

export interface Video {
  publicId: string;
  title: string;
  description: string;
  visibility: Visibility;
  status: VideoStatus;
  durationMs: number | null;
  width: number | null;
  height: number | null;
  viewCount: number;
  likeCount: number;
  dislikeCount: number;
  commentCount: number;
  createdAt: string;
  publishedAt: string | null;
  channel: {
    handle: string;
    displayName: string;
    /** watch page only */
    subscriberCount?: number | null;
    /** watch page only; null when anonymous or your own channel */
    subscribed?: boolean | null;
  } | null;
  posterUrl: string | null;
  playbackUrl: string | null;
  canEdit: boolean;
  /** watch page only: 1, -1, 0; null when anonymous */
  myReaction?: number | null;
  // owner only
  originalFilename?: string | null;
  sizeBytes?: number | null;
  progress?: number | null;
  error?: string | null;
}

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export interface VideoUpdate {
  title?: string;
  description?: string;
  visibility?: Visibility;
}

export interface UploadStatus {
  id: string;
  filename: string;
  totalBytes: number;
  receivedBytes: number;
  chunkBytes: number;
}

const cursorQuery = (cursor?: string | null) => (cursor ? `?cursor=${encodeURIComponent(cursor)}` : '');

export const listFeed = (cursor?: string | null) => api<Page<Video>>(`/videos${cursorQuery(cursor)}`);
export const listMyVideos = (cursor?: string | null) => api<Page<Video>>(`/me/videos${cursorQuery(cursor)}`);
export const getVideo = (publicId: string) => api<Video>(`/videos/${encodeURIComponent(publicId)}`);
export const updateVideo = (publicId: string, update: VideoUpdate) =>
  api<Video>(`/videos/${encodeURIComponent(publicId)}`, { method: 'PATCH', body: update });
export const deleteVideo = (publicId: string) =>
  api<void>(`/videos/${encodeURIComponent(publicId)}`, { method: 'DELETE' });

export const createUpload = (filename: string, size: number) =>
  api<UploadStatus>('/uploads', { method: 'POST', body: { filename, size } });
export const getUpload = (id: string) => api<UploadStatus>(`/uploads/${id}`);
export const completeUpload = (id: string, meta: VideoUpdate) =>
  api<Video>(`/uploads/${id}/complete`, { method: 'POST', body: meta });
export const abortUpload = (id: string) => api<void>(`/uploads/${id}`, { method: 'DELETE' });

/** Sends one chunk; a 409 means the server expects a different offset and is returned, not thrown. */
export async function putChunk(id: string, chunk: Blob, start: number, total: number, signal?: AbortSignal) {
  const res = await apiResponse(`/uploads/${id}`, {
    method: 'PUT',
    raw: chunk,
    headers: { 'Content-Range': `bytes ${start}-${start + chunk.size - 1}/${total}` },
    signal,
    accept: [409],
  });
  return (await res.json()) as UploadStatus;
}

export const getStorageUsage = () => api<{ usedBytes: number; quotaBytes: number }>('/me/storage');
