import { api, type User } from './client';
import type { Page, Video } from './videos';

export interface CommentAuthor {
  handle: string;
  displayName: string;
}

export interface Comment {
  id: number;
  parentId: number | null;
  /** null when deleted */
  body: string | null;
  deleted: boolean;
  author: CommentAuthor | null;
  createdAt: string;
  editedAt: string | null;
  replyCount: number;
  canEdit: boolean;
  canDelete: boolean;
}

export interface ReactionState {
  myReaction: number;
  likeCount: number;
  dislikeCount: number;
}

export interface Channel {
  handle: string;
  displayName: string;
  bio: string;
  subscriberCount: number;
  videoCount: number;
  createdAt: string;
  /** null for anonymous callers and your own channel */
  subscribed: boolean | null;
  mine: boolean;
}

export interface Subscription {
  subscribed: boolean;
  subscriberCount: number;
}

const enc = encodeURIComponent;
const cursorParam = (cursor?: string | null) => (cursor ? `cursor=${enc(cursor)}` : '');

export const listComments = (publicId: string, cursor?: string | null) =>
  api<Page<Comment>>(`/videos/${enc(publicId)}/comments?${cursorParam(cursor)}`);
export const listReplies = (publicId: string, parentId: number, cursor?: string | null) =>
  api<Page<Comment>>(`/videos/${enc(publicId)}/comments?parent=${parentId}&${cursorParam(cursor)}`);
export const postComment = (publicId: string, body: string, parentId?: number) =>
  api<Comment>(`/videos/${enc(publicId)}/comments`, { method: 'POST', body: { body, parentId } });
export const editComment = (id: number, body: string) => api<Comment>(`/comments/${id}`, { method: 'PATCH', body: { body } });
export const deleteComment = (id: number) => api<void>(`/comments/${id}`, { method: 'DELETE' });

export const react = (publicId: string, value: -1 | 0 | 1) =>
  api<ReactionState>(`/videos/${enc(publicId)}/reaction`, { method: 'PUT', body: { value } });
export const recordView = (publicId: string) =>
  api<{ viewCount: number }>(`/videos/${enc(publicId)}/views`, { method: 'POST' });

export const getChannel = (handle: string) => api<Channel>(`/channels/${enc(handle)}`);
export const listChannelVideos = (handle: string, cursor?: string | null) =>
  api<Page<Video>>(`/channels/${enc(handle)}/videos?${cursorParam(cursor)}`);
export const subscribe = (handle: string) => api<Subscription>(`/channels/${enc(handle)}/subscription`, { method: 'PUT' });
export const unsubscribe = (handle: string) =>
  api<Subscription>(`/channels/${enc(handle)}/subscription`, { method: 'DELETE' });
export const listSubscriptionFeed = (cursor?: string | null) => api<Page<Video>>(`/feed/subscriptions?${cursorParam(cursor)}`);
export const listMySubscriptions = () => api<Channel[]>('/me/subscriptions');

export const updateProfile = (update: { handle?: string; displayName?: string; bio?: string }) =>
  api<User>('/me', { method: 'PATCH', body: update });

/** Clicking the active reaction clears it; clicking the other one switches. */
export const nextReaction = (current: number, clicked: -1 | 1): -1 | 0 | 1 => (current === clicked ? 0 : clicked);
