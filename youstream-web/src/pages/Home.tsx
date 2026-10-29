import { For, Show } from 'solid-js';
import { A } from '@solidjs/router';
import { useInfiniteQuery } from '@tanstack/solid-query';
import { listFeed } from '../api/videos';
import { useAuth } from '../auth/AuthProvider';
import VideoCard from '../components/VideoCard';

export default function Home() {
  const auth = useAuth();
  const feed = useInfiniteQuery(() => ({
    queryKey: ['feed'],
    queryFn: ({ pageParam }) => listFeed(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  }));
  const videos = () => feed.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <section class="mx-auto max-w-7xl space-y-6">
      <Show when={feed.isError}>
        <p role="alert" class="text-red-600">
          Could not load videos.
        </p>
      </Show>

      <Show
        when={!feed.isPending && videos().length === 0}
        fallback={
          <div class="grid gap-x-4 gap-y-8 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            <For each={videos()}>{(v) => <VideoCard video={v} />}</For>
          </div>
        }
      >
        <div class="mt-16 text-center space-y-3">
          <h1 class="text-2xl font-bold">No public videos yet</h1>
          <Show
            when={auth.user()}
            fallback={
              <p class="text-neutral-500">
                <A href="/login" class="text-red-600 hover:underline">Sign in</A> to upload the first one.
              </p>
            }
          >
            <A href="/upload" class="inline-block rounded-md bg-red-600 px-4 py-2 font-medium text-white hover:bg-red-700">
              Upload a video
            </A>
          </Show>
        </div>
      </Show>

      <Show when={feed.hasNextPage}>
        <div class="text-center">
          <button
            onClick={() => feed.fetchNextPage()}
            disabled={feed.isFetchingNextPage}
            class="rounded-full border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm font-medium"
          >
            {feed.isFetchingNextPage ? 'Loading…' : 'Show more'}
          </button>
        </div>
      </Show>
    </section>
  );
}
