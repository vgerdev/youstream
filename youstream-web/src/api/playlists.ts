import { api } from './client';
import type { Channel } from './social';
import type { Page, Video, Visibility } from './videos';

export interface Playlist {
  publicId: string;
  title: string;
  description: string;
  visibility: Visibility;
  itemCount: number;
  owner: { handle: string; displayName: string } | null;
  thumbnailUrl: string | null;
  createdAt: string;
  updatedAt: string;
  canEdit: boolean;
  /** detail view only */
  items?: Video[];
  /** with ?contains= */
  containsVideo?: boolean | null;
}

export type SearchType = 'video' | 'channel' | 'playlist';
export type SearchResult = Video | Channel | Playlist;

const enc = encodeURIComponent;

export const getPlaylist = (publicId: string) => api<Playlist>(`/playlists/${enc(publicId)}`);
export const createPlaylist = (title: string, visibility: Visibility = 'PRIVATE', videoId?: string) =>
  api<Playlist>('/playlists', { method: 'POST', body: { title, visibility, videoId } });
export const updatePlaylist = (publicId: string, update: { title?: string; description?: string; visibility?: Visibility }) =>
  api<Playlist>(`/playlists/${enc(publicId)}`, { method: 'PATCH', body: update });
export const deletePlaylist = (publicId: string) => api<void>(`/playlists/${enc(publicId)}`, { method: 'DELETE' });
export const addToPlaylist = (publicId: string, videoId: string) =>
  api<Playlist>(`/playlists/${enc(publicId)}/items`, { method: 'POST', body: { videoId } });
export const removeFromPlaylist = (publicId: string, videoId: string) =>
  api<Playlist>(`/playlists/${enc(publicId)}/items/${enc(videoId)}`, { method: 'DELETE' });
export const reorderPlaylist = (publicId: string, videoIds: string[]) =>
  api<Playlist>(`/playlists/${enc(publicId)}/items`, { method: 'PUT', body: { videoIds } });
export const listMyPlaylists = (containsVideo?: string) =>
  api<Playlist[]>(`/me/playlists${containsVideo ? `?contains=${enc(containsVideo)}` : ''}`);
export const listChannelPlaylists = (handle: string) => api<Playlist[]>(`/channels/${enc(handle)}/playlists`);

export function search<T extends SearchResult>(q: string, type: SearchType, cursor?: string | null) {
  const params = new URLSearchParams({ q, type });
  if (cursor) params.set('cursor', cursor);
  return api<Page<T>>(`/search?${params}`);
}

/** Moves one entry up (-1) or down (+1); returns a new array. */
export function move<T>(items: T[], index: number, delta: -1 | 1): T[] {
  const target = index + delta;
  if (target < 0 || target >= items.length) return items;
  const copy = [...items];
  [copy[index], copy[target]] = [copy[target], copy[index]];
  return copy;
}

/** The video after {@code current} in a playlist, or undefined at the end. */
export function nextInPlaylist(items: Video[], current: string): Video | undefined {
  const i = items.findIndex((v) => v.publicId === current);
  return i >= 0 ? items[i + 1] : undefined;
}
