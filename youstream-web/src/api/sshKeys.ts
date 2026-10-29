import { api } from './client';

export interface SshKey {
  id: number;
  name: string;
  keyType: string;
  fingerprint: string;
  createdAt: string;
  lastUsedAt: string | null;
}

export interface SftpInfo {
  enabled: boolean;
  host: string | null;
  port: number;
  username: string;
  passwordLogin: boolean;
  hostKeyFingerprint: string | null;
  maxFileBytes: number;
}

export const listSshKeys = () => api<SshKey[]>('/me/ssh-keys');
export const addSshKey = (publicKey: string, name?: string) =>
  api<SshKey>('/me/ssh-keys', { method: 'POST', body: { publicKey, name: name || undefined } });
export const deleteSshKey = (id: number) => api<void>(`/me/ssh-keys/${id}`, { method: 'DELETE' });
export const getSftpInfo = () => api<SftpInfo>('/me/sftp');
