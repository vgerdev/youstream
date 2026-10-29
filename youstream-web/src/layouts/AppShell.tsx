import { Show, type ParentProps } from 'solid-js';
import { A, useLocation, useNavigate } from '@solidjs/router';
import { useAuth } from '../auth/AuthProvider';

export default function AppShell(props: ParentProps) {
  const auth = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const signOut = async () => {
    await auth.logout();
    navigate('/');
  };

  return (
    <div class="min-h-screen flex flex-col">
      <header class="h-14 px-4 flex items-center gap-4 border-b border-neutral-200 dark:border-neutral-800">
        <A href="/" class="flex items-center gap-1 font-bold text-lg">
          <span class="inline-block w-7 h-5 rounded bg-red-600 text-white text-xs leading-5 text-center">▶</span>
          YouStream
        </A>
        <div class="flex-1" />
        <Show when={!auth.loading()}>
          <Show
            when={auth.user()}
            fallback={
              <Show when={location.pathname !== '/login'}>
                <A
                  href={`/login?returnTo=${encodeURIComponent(location.pathname)}`}
                  class="rounded-full border border-neutral-300 dark:border-neutral-700 px-3 py-1 text-sm font-medium hover:bg-neutral-100 dark:hover:bg-neutral-800"
                >
                  Sign in
                </A>
              </Show>
            }
          >
            {(user) => (
              <div class="flex items-center gap-3 text-sm">
                <A href="/account" class="flex items-center gap-2 hover:underline">
                  <span class="grid h-8 w-8 place-items-center rounded-full bg-red-600 font-semibold text-white">
                    {user().displayName.charAt(0).toUpperCase()}
                  </span>
                  <span class="hidden sm:inline">{user().displayName}</span>
                </A>
                <button onClick={signOut} class="text-neutral-500 hover:text-neutral-900 dark:hover:text-white">
                  Sign out
                </button>
              </div>
            )}
          </Show>
        </Show>
      </header>
      <main class="flex-1 p-4">{props.children}</main>
    </div>
  );
}
