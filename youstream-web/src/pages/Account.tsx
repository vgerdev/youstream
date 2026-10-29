import { createSignal, For, Show } from 'solid-js';
import { useSearchParams } from '@solidjs/router';
import { useQuery } from '@tanstack/solid-query';
import { getAuthMethods, linkLdap, startMicrosoftLink, type IdentityProvider } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import { authErrorMessage } from '../auth/errors';
import LdapLoginForm from '../components/LdapLoginForm';
import MicrosoftButton from '../components/MicrosoftButton';
import SftpSection from '../components/SftpSection';

const PROVIDER_NAMES: Record<IdentityProvider, string> = {
  LDAP: 'Directory account (LDAP)',
  ENTRA: 'Microsoft account',
};

export default function Account() {
  const auth = useAuth();
  const [params] = useSearchParams<{ error?: string }>();
  const methods = useQuery(() => ({ queryKey: ['auth-methods'], queryFn: getAuthMethods }));
  const [linkError, setLinkError] = createSignal<string | null>(null);

  const has = (p: IdentityProvider) => auth.user()?.identities.includes(p) ?? false;

  const linkMicrosoft = async (e: MouseEvent) => {
    e.preventDefault();
    setLinkError(null);
    try {
      window.location.assign((await startMicrosoftLink()).url);
    } catch (err) {
      setLinkError(authErrorMessage(err));
    }
  };

  return (
    <Show when={auth.user()}>
      {(user) => (
        <section class="mx-auto mt-8 max-w-xl space-y-8">
          <header class="space-y-1">
            <h1 class="text-2xl font-bold">{user().displayName}</h1>
            <p class="text-neutral-500">
              @{user().handle} · SFTP username <span class="font-mono">{user().username}</span>
              <Show when={user().admin}>
                <span class="ml-2 rounded bg-red-600 px-1.5 py-0.5 text-xs text-white">admin</span>
              </Show>
            </p>
            <Show when={user().email}>
              <p class="text-neutral-500">{user().email}</p>
            </Show>
          </header>

          <section class="space-y-3">
            <h2 class="text-lg font-semibold">Sign-in methods</h2>
            <ul class="space-y-1">
              <For each={user().identities}>
                {(p) => (
                  <li class="flex items-center gap-2">
                    <span class="text-green-600">✓</span>
                    {PROVIDER_NAMES[p]}
                  </li>
                )}
              </For>
            </ul>

            <Show when={params.error || linkError()}>
              <p role="alert" class="text-sm text-red-600">
                {linkError() ?? authErrorMessage(params.error)}
              </p>
            </Show>

            <Show when={methods.data?.microsoft && !has('ENTRA')}>
              <div class="max-w-xs">
                <MicrosoftButton label="Link a Microsoft account" onClick={linkMicrosoft} />
              </div>
            </Show>

            <Show when={methods.data?.ldap && !has('LDAP')}>
              <div class="max-w-xs space-y-2 pt-2">
                <p class="text-sm text-neutral-500">
                  Link a directory account to upload over SFTP with your directory password.
                </p>
                <LdapLoginForm
                  submitLabel="Link directory account"
                  onSubmit={async (u, p) => auth.setUser(await linkLdap(u, p))}
                />
              </div>
            </Show>
          </section>

          <SftpSection />
        </section>
      )}
    </Show>
  );
}
