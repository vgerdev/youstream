import { describe, expect, it } from 'vitest';
import { countOf, formatDuration } from './format';

describe('format', () => {
  it('pluralizes counts', () => {
    expect(countOf(0, 'view')).toBe('0 views');
    expect(countOf(1, 'view')).toBe('1 view');
    expect(countOf(2, 'reply', 'replies')).toBe('2 replies');
  });

  it('formats durations', () => {
    expect(formatDuration(75_000)).toBe('1:15');
    expect(formatDuration(3_725_000)).toBe('1:02:05');
    expect(formatDuration(null)).toBe('');
  });
});
