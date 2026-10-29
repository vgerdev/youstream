import { describe, expect, it } from 'vitest';
import { move, nextInPlaylist } from './playlists';
import type { Video } from './videos';

const v = (publicId: string) => ({ publicId }) as Video;

describe('playlist helpers', () => {
  it('moves items up and down within bounds', () => {
    expect(move(['a', 'b', 'c'], 1, -1)).toEqual(['b', 'a', 'c']);
    expect(move(['a', 'b', 'c'], 1, 1)).toEqual(['a', 'c', 'b']);
    expect(move(['a', 'b', 'c'], 0, -1)).toEqual(['a', 'b', 'c']);
    expect(move(['a', 'b', 'c'], 2, 1)).toEqual(['a', 'b', 'c']);
  });

  it('finds the next video', () => {
    const items = [v('a'), v('b')];
    expect(nextInPlaylist(items, 'a')?.publicId).toBe('b');
    expect(nextInPlaylist(items, 'b')).toBeUndefined();
    expect(nextInPlaylist(items, 'zzz')).toBeUndefined();
  });
});
