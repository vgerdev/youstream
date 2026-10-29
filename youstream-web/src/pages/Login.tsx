import { createEffect, Show } from 'solid-js';
import { useNavigate, useSearchParams } from '@solidjs/router';
import { useQuery } from '@tanstack/solid-query';
import { getAuthMethods, microsoftLoginUrl } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import { authErrorMessage } from '../auth/errors';
import LdapLoginForm from '../components/LdapLoginForm';
import MicrosoftButton from '../components/MicrosoftButton';

/** Only same-site paths, never "//host" or absolute URLs. */
function safeReturnTo(value: string | undefined): string {
  return value && value.startsWith('/') && !value.startsWith('//') && !value.startsWith('/\\') ? value : '/';
}

export default function Login() {
  const auth = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams<{ returnTo?: string; error?: string }>();
  const methods = useQuery(() => ({ queryKey: ['auth-methods'], queryFn: getAuthMethods }));
  // read once: navigating away clears ?returnTo, which must not re-trigger the redirect below
  const returnTo = safeReturnTo(params.returnTo);

  // leaves the page once signed in: after the form succeeds, or when already signed in (back button)
  let left = false;
  createEffect(() => {
    if (!left && !auth.loading() && auth.user()) {
      left = true;
      navigate(returnTo, { replace: true });
    }
  });

  return (
    <section class="mx-auto mt-12 max-w-sm space-y-6">
      <h1 class="text-2xl font-bold text-center">Sign in to YouStream</h1>

      <Show when={params.error}>
        <p role="alert" class="rounded-md bg-red-50 dark:bg-red-950 px-3 py-2 text-sm text-red-700 dark:text-red-300">
          {authErrorMessage(params.error)}
        </p>
      </Show>

      <Show when={methods.data} fallback={<p class="text-center text-neutral-500">Loading…</p>}>
        {(m) => (
          <div class="space-y-6">
            <Show when={m().microsoft}>
              <MicrosoftButton label="Sign in with Microsoft" href={microsoftLoginUrl(returnTo)} />
            </Show>

            <Show when={m().microsoft && m().ldap}>
              <div class="flex items-center gap-3 text-xs uppercase text-neutral-500">
                <span class="h-px flex-1 bg-neutral-200 dark:bg-neutral-800" />
                or use your directory account
                <span class="h-px flex-1 bg-neutral-200 dark:bg-neutral-800" />
              </div>
            </Show>

            <Show when={m().ldap}>
              <LdapLoginForm submitLabel="Sign in" onSubmit={(u, p) => auth.loginWithLdap(u, p)} />
            </Show>

            <Show when={!m().ldap && !m().microsoft}>
              <p class="text-center text-neutral-500">No sign-in method is configured on this server.</p>
            </Show>
          </div>
        )}
      </Show>
    </section>
  );
}
