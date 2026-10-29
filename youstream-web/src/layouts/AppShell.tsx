import { createEffect, createSignal, Show, type ParentProps } from 'solid-js';
import { A, useLocation, useNavigate, useSearchParams } from '@solidjs/router';
import { useAuth } from '../auth/AuthProvider';

export default function AppShell(props: ParentProps) {
  const auth = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [params] = useSearchParams<{ q?: string }>();
  const [query, setQuery] = createSignal('');
  // keep the box in step with /search?q=...
  createEffect(() => {
    if (location.pathname === '/search') setQuery(params.q ?? '');
  });

  const signOut = async () => {
    await auth.logout();
    navigate('/');
  };

  const submitSearch = (e: SubmitEvent) => {
    e.preventDefault();
    if (query().trim()) navigate(`/search?q=${encodeURIComponent(query().trim())}`);
  };

  return (
    <Show when={!location.pathname.startsWith('/embed/')} fallback={props.children}>
    <div class="min-h-screen flex flex-col">
      <header class="h-14 px-4 flex items-center gap-4 border-b border-neutral-200 dark:border-neutral-800">
        <A href="/" class="flex items-center gap-1 font-bold text-lg">
          <span class="inline-block w-7 h-5 rounded bg-red-600 text-white text-xs leading-5 text-center">▶</span>
          YouStream
        </A>
        <form role="search" class="mx-auto flex w-full max-w-xl" onSubmit={submitSearch}>
          <input
            type="search"
            aria-label="Search"
            placeholder="Search"
            class="min-w-0 flex-1 rounded-l-full border border-neutral-300 dark:border-neutral-700 bg-transparent px-4 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-red-600"
            value={query()}
            onInput={(e) => setQuery(e.currentTarget.value)}
          />
          <button type="submit" aria-label="Search" class="rounded-r-full border border-l-0 border-neutral-300 dark:border-neutral-700 bg-neutral-100 dark:bg-neutral-900 px-4 text-sm">
            🔍
          </button>
        </form>
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
                <A
                  href="/upload"
                  class="rounded-full border border-neutral-300 dark:border-neutral-700 px-3 py-1 font-medium hover:bg-neutral-100 dark:hover:bg-neutral-800"
                >
                  + Upload
                </A>
                <A href="/subscriptions" class="hidden text-neutral-500 hover:text-neutral-900 dark:hover:text-white md:inline">
                  Subscriptions
                </A>
                <A href="/playlists" class="hidden text-neutral-500 hover:text-neutral-900 dark:hover:text-white md:inline">
                  Playlists
                </A>
                <A href="/studio" class="text-neutral-500 hover:text-neutral-900 dark:hover:text-white">
                  Studio
                </A>
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
    </Show>
  );
}
