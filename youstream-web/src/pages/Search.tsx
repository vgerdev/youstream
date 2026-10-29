import { For, Match, Show, Switch } from 'solid-js';
import { A, useSearchParams } from '@solidjs/router';
import { useInfiniteQuery } from '@tanstack/solid-query';
import { search, type Playlist, type SearchType } from '../api/playlists';
import type { Channel } from '../api/social';
import type { Video } from '../api/videos';
import Avatar from '../components/Avatar';
import PlaylistCard from '../components/PlaylistCard';
import VideoCard from '../components/VideoCard';
import { countOf } from '../util/format';

const TABS: { type: SearchType; label: string }[] = [
  { type: 'video', label: 'Videos' },
  { type: 'channel', label: 'Channels' },
  { type: 'playlist', label: 'Playlists' },
];

export default function Search() {
  const [params, setParams] = useSearchParams<{ q?: string; type?: SearchType }>();
  const q = () => (params.q ?? '').trim();
  const type = () => params.type ?? 'video';
  const results = useInfiniteQuery(() => ({
    queryKey: ['search', type(), q()],
    queryFn: ({ pageParam }) => search(q(), type(), pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
    enabled: q().length > 0,
  }));
  const items = () => results.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <section class="mx-auto max-w-7xl space-y-6">
      <div class="flex gap-2 border-b border-neutral-200 dark:border-neutral-800">
        <For each={TABS}>
          {(tab) => (
            <button
              class={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${
                type() === tab.type ? 'border-red-600 text-neutral-900 dark:text-white' : 'border-transparent text-neutral-500'
              }`}
              onClick={() => setParams({ type: tab.type })}
            >
              {tab.label}
            </button>
          )}
        </For>
      </div>

      <Show when={q()} fallback={<p class="text-neutral-500">Type something in the search box.</p>}>
        <Show when={!results.isPending && items().length === 0}>
          <p class="text-neutral-500">No results for “{q()}”.</p>
        </Show>
        <Switch>
          <Match when={type() === 'video'}>
            <div class="grid gap-x-4 gap-y-8 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              <For each={items() as Video[]}>{(v) => <VideoCard video={v} />}</For>
            </div>
          </Match>
          <Match when={type() === 'channel'}>
            <ul class="space-y-4">
              <For each={items() as Channel[]}>
                {(c) => (
                  <li>
                    <A href={`/c/${c.handle}`} class="flex items-center gap-4 rounded-xl p-2 hover:bg-neutral-100 dark:hover:bg-neutral-900">
                      <Avatar name={c.displayName} size="lg" />
                      <div class="min-w-0">
                        <p class="font-semibold">{c.displayName}</p>
                        <p class="text-sm text-neutral-500">
                          @{c.handle} · {countOf(c.subscriberCount, 'subscriber')} · {countOf(c.videoCount, 'video')}
                        </p>
                        <p class="line-clamp-1 text-sm text-neutral-500">{c.bio}</p>
                      </div>
                    </A>
                  </li>
                )}
              </For>
            </ul>
          </Match>
          <Match when={type() === 'playlist'}>
            <div class="grid gap-x-4 gap-y-8 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              <For each={items() as Playlist[]}>{(p) => <PlaylistCard playlist={p} />}</For>
            </div>
          </Match>
        </Switch>
        <Show when={results.hasNextPage}>
          <div class="text-center">
            <button
              class="rounded-full border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm font-medium"
              onClick={() => results.fetchNextPage()}
            >
              More results
            </button>
          </div>
        </Show>
      </Show>
    </section>
  );
}
