import { createSignal, Match, Show, Switch } from 'solid-js';
import { A, useParams, useSearchParams } from '@solidjs/router';
import { useQuery } from '@tanstack/solid-query';
import { ApiError } from '../api/client';
import { getVideo } from '../api/videos';
import Player from '../components/Player';
import { VISIBILITY_LABELS } from '../components/VisibilitySelect';
import { formatCount, timeAgo } from '../util/format';

export default function Watch() {
  const params = useParams<{ id: string }>();
  const [search] = useSearchParams<{ t?: string }>();
  const video = useQuery(() => ({
    queryKey: ['video', params.id],
    queryFn: () => getVideo(params.id),
    retry: (count, e) => !(e instanceof ApiError && e.status === 404) && count < 2,
    // keep checking while the owner waits for processing
    refetchInterval: (q) => (q.state.data && q.state.data.status !== 'READY' && q.state.data.status !== 'FAILED' ? 3000 : false),
  }));
  const [copied, setCopied] = createSignal(false);

  const share = async () => {
    await navigator.clipboard.writeText(`${location.origin}/watch/${params.id}`);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <section class="mx-auto max-w-5xl space-y-4">
      <Switch>
        <Match when={video.isError}>
          <div class="mt-16 text-center space-y-2">
            <h1 class="text-2xl font-bold">Video unavailable</h1>
            <p class="text-neutral-500">It may be private, or it may have been deleted.</p>
            <A href="/" class="text-red-600 hover:underline">
              Back to home
            </A>
          </div>
        </Match>
        <Match when={video.data}>
          {(v) => (
            <>
              <Show
                when={v().playbackUrl}
                keyed
                fallback={
                  <div class="grid aspect-video place-items-center rounded-xl bg-neutral-200 dark:bg-neutral-800 text-neutral-500">
                    {v().status === 'FAILED'
                      ? 'Processing failed.'
                      : `Processing… ${Math.round((v().progress ?? 0) * 100)}%`}
                  </div>
                }
              >
                {(src) => <Player src={src} poster={v().posterUrl} startAt={Number(search.t) || 0} />}
              </Show>

              <h1 class="text-xl font-bold">{v().title}</h1>

              <div class="flex flex-wrap items-center gap-3">
                <div class="flex items-center gap-2">
                  <span class="grid h-10 w-10 place-items-center rounded-full bg-red-600 font-semibold text-white">
                    {v().channel?.displayName.charAt(0).toUpperCase()}
                  </span>
                  <div>
                    <p class="font-semibold">{v().channel?.displayName}</p>
                    <p class="text-xs text-neutral-500">@{v().channel?.handle}</p>
                  </div>
                </div>
                <div class="flex-1" />
                <Show when={v().visibility !== 'PUBLIC'}>
                  <span class="rounded-full bg-neutral-200 dark:bg-neutral-800 px-3 py-1 text-sm">
                    {VISIBILITY_LABELS[v().visibility]}
                  </span>
                </Show>
                <Show when={v().canEdit}>
                  <A href="/studio" class="rounded-full bg-neutral-200 dark:bg-neutral-800 px-4 py-2 text-sm font-medium">
                    Edit in Studio
                  </A>
                </Show>
                <button
                  onClick={share}
                  class="rounded-full bg-neutral-200 dark:bg-neutral-800 px-4 py-2 text-sm font-medium hover:bg-neutral-300 dark:hover:bg-neutral-700"
                >
                  {copied() ? 'Link copied' : 'Share'}
                </button>
              </div>

              <div class="rounded-xl bg-neutral-100 dark:bg-neutral-900 p-3 text-sm">
                <p class="font-semibold">
                  {formatCount(v().viewCount)} views · {timeAgo(v().publishedAt ?? v().createdAt)}
                </p>
                <p class="mt-1 whitespace-pre-wrap">{v().description}</p>
              </div>
            </>
          )}
        </Match>
        <Match when={video.isPending}>
          <div class="aspect-video animate-pulse rounded-xl bg-neutral-200 dark:bg-neutral-800" />
        </Match>
      </Switch>
    </section>
  );
}
