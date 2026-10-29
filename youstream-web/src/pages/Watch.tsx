import { createSignal, For, Match, Show, Switch } from 'solid-js';
import { A, useNavigate, useParams, useSearchParams } from '@solidjs/router';
import { useQuery } from '@tanstack/solid-query';
import { ApiError } from '../api/client';
import { getPlaylist, nextInPlaylist } from '../api/playlists';
import { recordView } from '../api/social';
import { getVideo } from '../api/videos';
import { useAuth } from '../auth/AuthProvider';
import Avatar from '../components/Avatar';
import CommentSection from '../components/CommentSection';
import LikeBar from '../components/LikeBar';
import Player from '../components/Player';
import SaveToPlaylist from '../components/SaveToPlaylist';
import ShareDialog from '../components/ShareDialog';
import SubscribeButton from '../components/SubscribeButton';
import { VISIBILITY_LABELS } from '../components/VisibilitySelect';
import { countOf, formatDuration, timeAgo } from '../util/format';

/** Keyed by video id: per-video state (likes, views, subscription) starts fresh when a playlist moves on. */
export default function Watch() {
  const params = useParams<{ id: string }>();
  return (
    <Show when={params.id} keyed>
      {(id) => <WatchVideo id={id} />}
    </Show>
  );
}

function PlaylistPanel(props: { listId: string; current: string }) {
  const playlist = useQuery(() => ({ queryKey: ['playlist', props.listId], queryFn: () => getPlaylist(props.listId), retry: false }));
  return (
    <Show when={playlist.data}>
      {(p) => (
        <aside class="overflow-hidden rounded-xl border border-neutral-200 dark:border-neutral-800">
          <header class="bg-neutral-100 dark:bg-neutral-900 px-4 py-3">
            <A href={`/playlist/${p().publicId}`} class="font-semibold hover:underline">
              {p().title}
            </A>
            <p class="text-xs text-neutral-500">
              {p().owner?.displayName} · {(p().items?.findIndex((v) => v.publicId === props.current) ?? -1) + 1} / {p().items?.length}
            </p>
          </header>
          <ol class="max-h-96 overflow-y-auto">
            <For each={p().items}>
              {(v, i) => (
                <li>
                  <A
                    href={`/watch/${v.publicId}?list=${p().publicId}`}
                    class={`flex items-center gap-2 px-3 py-2 text-sm hover:bg-neutral-100 dark:hover:bg-neutral-900 ${
                      v.publicId === props.current ? 'bg-neutral-200 dark:bg-neutral-800' : ''
                    }`}
                  >
                    <span class="w-5 text-right text-xs text-neutral-500">{v.publicId === props.current ? '▶' : i() + 1}</span>
                    <div class="relative aspect-video w-24 shrink-0 overflow-hidden rounded bg-neutral-200 dark:bg-neutral-800">
                      <Show when={v.posterUrl}>{(src) => <img src={src()} alt="" class="h-full w-full object-cover" />}</Show>
                      <span class="absolute bottom-0.5 right-0.5 rounded bg-black/80 px-1 text-[10px] text-white">{formatDuration(v.durationMs)}</span>
                    </div>
                    <div class="min-w-0">
                      <p class="line-clamp-2 font-medium">{v.title}</p>
                      <p class="truncate text-xs text-neutral-500">{v.channel?.displayName}</p>
                    </div>
                  </A>
                </li>
              )}
            </For>
          </ol>
        </aside>
      )}
    </Show>
  );
}

function WatchVideo(props: { id: string }) {
  const auth = useAuth();
  const navigate = useNavigate();
  const [search] = useSearchParams<{ t?: string; list?: string }>();
  const video = useQuery(() => ({
    queryKey: ['video', props.id],
    queryFn: () => getVideo(props.id),
    retry: (count, e) => !(e instanceof ApiError && e.status === 404) && count < 2,
    // keep checking while the owner waits for processing
    refetchInterval: (q) => (q.state.data && q.state.data.status !== 'READY' && q.state.data.status !== 'FAILED' ? 3000 : false),
  }));
  const playlist = useQuery(() => ({
    queryKey: ['playlist', search.list],
    queryFn: () => getPlaylist(search.list!),
    enabled: !!search.list,
    retry: false,
  }));
  const [views, setViews] = createSignal<number | null>(null);
  const [subscription, setSubscription] = createSignal<{ subscribed: boolean; count: number } | null>(null);
  const [sharing, setSharing] = createSignal(false);
  const [saving, setSaving] = createSignal(false);
  let element: HTMLVideoElement | undefined;

  const playNext = () => {
    const next = playlist.data?.items ? nextInPlaylist(playlist.data.items, props.id) : undefined;
    if (next) navigate(`/watch/${next.publicId}?list=${search.list}`);
  };

  const pill = 'rounded-full bg-neutral-200 dark:bg-neutral-800 px-4 py-2 text-sm font-medium hover:bg-neutral-300 dark:hover:bg-neutral-700';

  return (
    <section class={`mx-auto space-y-4 ${search.list ? 'max-w-7xl' : 'max-w-5xl'}`}>
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
            <div class={search.list ? 'grid gap-6 lg:grid-cols-[1fr_22rem]' : ''}>
              <div class="min-w-0 space-y-4">
                <Show
                  when={v().playbackUrl}
                  keyed
                  fallback={
                    <div class="grid aspect-video place-items-center rounded-xl bg-neutral-200 dark:bg-neutral-800 text-neutral-500">
                      {v().status === 'FAILED' ? 'Processing failed.' : `Processing… ${Math.round((v().progress ?? 0) * 100)}%`}
                    </div>
                  }
                >
                  {(src) => (
                    <Player
                      src={src}
                      poster={v().posterUrl}
                      startAt={Number(search.t) || 0}
                      autoplay={!!search.list}
                      onElement={(el) => (element = el)}
                      onEnded={playNext}
                      onWatched={() => recordView(props.id).then((r) => setViews(r.viewCount), () => undefined)}
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
                            <p class="text-xs text-neutral-500">{countOf(subscription()?.count ?? ch().subscriberCount ?? 0, 'subscriber')}</p>
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
                    <span class="rounded-full bg-neutral-200 dark:bg-neutral-800 px-3 py-1 text-sm">{VISIBILITY_LABELS[v().visibility]}</span>
                  </Show>
                  <Show when={v().status === 'READY'}>
                    <LikeBar publicId={v().publicId} likes={v().likeCount} dislikes={v().dislikeCount} mine={v().myReaction} />
                  </Show>
                  <button class={pill} onClick={() => setSharing(true)}>
                    Share
                  </button>
                  <Show when={auth.user() && v().status === 'READY'}>
                    <button class={pill} onClick={() => setSaving(true)}>
                      Save
                    </button>
                  </Show>
                  <Show when={v().canEdit}>
                    <A href="/studio" class={pill}>
                      Edit in Studio
                    </A>
                  </Show>
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
              </div>

              <Show when={search.list}>{(list) => <PlaylistPanel listId={list()} current={props.id} />}</Show>

              <ShareDialog video={v()} currentTime={() => element?.currentTime ?? 0} open={sharing()} onClose={() => setSharing(false)} />
              <SaveToPlaylist videoId={v().publicId} open={saving()} onClose={() => setSaving(false)} />
            </div>
          )}
        </Match>
        <Match when={video.isPending}>
          <div class="aspect-video animate-pulse rounded-xl bg-neutral-200 dark:bg-neutral-800" />
        </Match>
      </Switch>
    </section>
  );
}
