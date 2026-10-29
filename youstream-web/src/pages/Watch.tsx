import { createSignal, Match, Show, Switch } from 'solid-js';
import { A, useParams, useSearchParams } from '@solidjs/router';
import { useQuery } from '@tanstack/solid-query';
import { ApiError } from '../api/client';
import { recordView } from '../api/social';
import { getVideo } from '../api/videos';
import Avatar from '../components/Avatar';
import CommentSection from '../components/CommentSection';
import LikeBar from '../components/LikeBar';
import Player from '../components/Player';
import SubscribeButton from '../components/SubscribeButton';
import { VISIBILITY_LABELS } from '../components/VisibilitySelect';
import { countOf, timeAgo } from '../util/format';

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
  const [views, setViews] = createSignal<number | null>(null);
  const [subscription, setSubscription] = createSignal<{ subscribed: boolean; count: number } | null>(null);

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
                {(src) => (
                  <Player
                    src={src}
                    poster={v().posterUrl}
                    startAt={Number(search.t) || 0}
                    onWatched={() => recordView(params.id).then((r) => setViews(r.viewCount), () => undefined)}
                  />
                )}
              </Show>

              <h1 class="text-xl font-bold">{v().title}</h1>

              <div class="flex flex-wrap items-center gap-3">
                <Show when={v().channel}>
                  {(ch) => (
                    <div class="flex items-center gap-3">
                      <A href={`/c/${ch().handle}`} class="flex items-center gap-2">
                        <Avatar name={ch().displayName} />
                        <div>
                          <p class="font-semibold hover:underline">{ch().displayName}</p>
                          <p class="text-xs text-neutral-500">
                            {countOf(subscription()?.count ?? ch().subscriberCount ?? 0, 'subscriber')}
                          </p>
                        </div>
                      </A>
                      <SubscribeButton
                        handle={ch().handle}
                        subscribed={subscription()?.subscribed ?? ch().subscribed}
                        mine={v().canEdit && ch().subscribed == null}
                        onChange={(subscribed, count) => setSubscription({ subscribed, count })}
                      />
                    </div>
                  )}
                </Show>
                <div class="flex-1" />
                <Show when={v().visibility !== 'PUBLIC'}>
                  <span class="rounded-full bg-neutral-200 dark:bg-neutral-800 px-3 py-1 text-sm">
                    {VISIBILITY_LABELS[v().visibility]}
                  </span>
                </Show>
                <Show when={v().status === 'READY'}>
                  <LikeBar publicId={v().publicId} likes={v().likeCount} dislikes={v().dislikeCount} mine={v().myReaction} />
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
                  {countOf(views() ?? v().viewCount, 'view')} · {timeAgo(v().publishedAt ?? v().createdAt)}
                </p>
                <p class="mt-1 whitespace-pre-wrap">{v().description}</p>
              </div>

              <Show when={v().status === 'READY'}>
                <CommentSection publicId={v().publicId} count={v().commentCount} />
              </Show>
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
