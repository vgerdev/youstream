import { createSignal, For, Match, Show, Switch } from 'solid-js';
import { A, useParams } from '@solidjs/router';
import { useInfiniteQuery, useQuery } from '@tanstack/solid-query';
import { getChannel, listChannelVideos } from '../api/social';
import Avatar from '../components/Avatar';
import SubscribeButton from '../components/SubscribeButton';
import VideoCard from '../components/VideoCard';
import { countOf } from '../util/format';

export default function Channel() {
  const params = useParams<{ handle: string }>();
  const channel = useQuery(() => ({ queryKey: ['channel', params.handle], queryFn: () => getChannel(params.handle), retry: false }));
  const videos = useInfiniteQuery(() => ({
    queryKey: ['channel-videos', params.handle],
    queryFn: ({ pageParam }) => listChannelVideos(params.handle, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
    enabled: channel.isSuccess,
  }));
  const [subscription, setSubscription] = createSignal<{ subscribed: boolean; count: number } | null>(null);
  const list = () => videos.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <section class="mx-auto max-w-7xl space-y-8">
      <Switch>
        <Match when={channel.isError}>
          <div class="mt-16 text-center space-y-2">
            <h1 class="text-2xl font-bold">Channel not found</h1>
            <A href="/" class="text-red-600 hover:underline">
              Back to home
            </A>
          </div>
        </Match>
        <Match when={channel.data}>
          {(c) => (
            <>
              <header class="flex flex-wrap items-center gap-6 border-b border-neutral-200 dark:border-neutral-800 pb-6">
                <Avatar name={c().displayName} size="lg" />
                <div class="min-w-0 flex-1 space-y-1">
                  <h1 class="text-3xl font-bold">{c().displayName}</h1>
                  <p class="text-sm text-neutral-500">
                    @{c().handle} · {countOf(subscription()?.count ?? c().subscriberCount, 'subscriber')} ·{' '}
                    {countOf(c().videoCount, 'video')}
                  </p>
                  <Show when={c().bio}>
                    <p class="max-w-2xl whitespace-pre-wrap text-sm">{c().bio}</p>
                  </Show>
                </div>
                <Show
                  when={!c().mine}
                  fallback={
                    <A href="/account" class="rounded-full bg-neutral-200 dark:bg-neutral-800 px-4 py-2 text-sm font-medium">
                      Customize channel
                    </A>
                  }
                >
                  <SubscribeButton
                    handle={c().handle}
                    subscribed={subscription()?.subscribed ?? c().subscribed}
                    onChange={(subscribed, count) => setSubscription({ subscribed, count })}
                  />
                </Show>
              </header>

              <Show
                when={list().length > 0 || videos.isPending}
                fallback={<p class="text-neutral-500">This channel has no public videos yet.</p>}
              >
                <div class="grid gap-x-4 gap-y-8 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
                  <For each={list()}>{(v) => <VideoCard video={v} />}</For>
                </div>
              </Show>
              <Show when={videos.hasNextPage}>
                <div class="text-center">
                  <button
                    class="rounded-full border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm font-medium"
                    onClick={() => videos.fetchNextPage()}
                  >
                    Show more
                  </button>
                </div>
              </Show>
            </>
          )}
        </Match>
      </Switch>
    </section>
  );
}
