import { describe, expect, it } from 'vitest';
import { nextReaction } from './social';

describe('nextReaction', () => {
  it('toggles the clicked reaction and switches between like and dislike', () => {
    expect(nextReaction(0, 1)).toBe(1);
    expect(nextReaction(1, 1)).toBe(0);
    expect(nextReaction(1, -1)).toBe(-1);
    expect(nextReaction(-1, -1)).toBe(0);
    expect(nextReaction(-1, 1)).toBe(1);
  });
});
