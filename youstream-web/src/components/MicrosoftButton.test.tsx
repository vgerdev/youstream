import { describe, expect, it } from 'vitest';
import { render } from '@solidjs/testing-library';
import MicrosoftButton from './MicrosoftButton';

describe('MicrosoftButton', () => {
  it('is an external link so the SPA router lets the browser load the server route', () => {
    const { getByRole } = render(() => (
      <MicrosoftButton label="Sign in with Microsoft" href="/api/auth/microsoft/login?returnTo=%2F" />
    ));
    const link = getByRole('link', { name: 'Sign in with Microsoft' });

    expect(link.getAttribute('href')).toBe('/api/auth/microsoft/login?returnTo=%2F');
    expect(link.getAttribute('rel')).toBe('external');
  });
});
