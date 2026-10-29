import { For, Show } from 'solid-js';
import { A } from '@solidjs/router';
import { useInfiniteQuery, useQuery } from '@tanstack/solid-query';
import { listMySubscriptions, listSubscriptionFeed } from '../api/social';
import Avatar from '../components/Avatar';
import VideoCard from '../components/VideoCard';

export default function Subscriptions() {
  const channels = useQuery(() => ({ queryKey: ['subscriptions'], queryFn: listMySubscriptions }));
  const feed = useInfiniteQuery(() => ({
    queryKey: ['subscription-feed'],
    queryFn: ({ pageParam }) => listSubscriptionFeed(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  }));
  const videos = () => feed.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <section class="mx-auto max-w-7xl space-y-6">
      <h1 class="text-2xl font-bold">Subscriptions</h1>

      <Show
        when={channels.data?.length}
        fallback={
          <p class="text-neutral-500">
            You are not subscribed to any channel yet. Find something on the <A href="/" class="text-red-600 hover:underline">home page</A>.
          </p>
        }
      >
        <ul class="flex gap-4 overflow-x-auto pb-2">
          <For each={channels.data}>
            {(c) => (
              <li>
                <A href={`/c/${c.handle}`} class="flex w-20 flex-col items-center gap-1 text-center text-xs">
                  <Avatar name={c.displayName} size="lg" />
                  <span class="line-clamp-2">{c.displayName}</span>
                </A>
              </li>
            )}
          </For>
        </ul>

        <div class="grid gap-x-4 gap-y-8 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          <For each={videos()}>{(v) => <VideoCard video={v} />}</For>
        </div>
        <Show when={!feed.isPending && videos().length === 0}>
          <p class="text-neutral-500">No public videos from your subscriptions yet.</p>
        </Show>
        <Show when={feed.hasNextPage}>
          <div class="text-center">
            <button
              class="rounded-full border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm font-medium"
              onClick={() => feed.fetchNextPage()}
            >
              Show more
            </button>
          </div>
        </Show>
      </Show>
    </section>
  );
}
