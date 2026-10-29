import { createSignal, For, Match, Show, Switch } from 'solid-js';
import { A, useNavigate, useParams } from '@solidjs/router';
import { useQuery, useQueryClient } from '@tanstack/solid-query';
import { deletePlaylist, getPlaylist, move, removeFromPlaylist, reorderPlaylist, updatePlaylist } from '../api/playlists';
import VisibilitySelect, { VISIBILITY_LABELS } from '../components/VisibilitySelect';
import { countOf, formatDuration } from '../util/format';

export default function PlaylistPage() {
  const params = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const playlist = useQuery(() => ({ queryKey: ['playlist', params.id], queryFn: () => getPlaylist(params.id), retry: false }));
  const [editing, setEditing] = createSignal(false);
  const [title, setTitle] = createSignal('');
  const [description, setDescription] = createSignal('');
  const [error, setError] = createSignal<string | null>(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['playlist', params.id] });
  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Something went wrong');
    }
  };

  return (
    <section class="mx-auto max-w-5xl space-y-6">
      <Switch>
        <Match when={playlist.isError}>
          <div class="mt-16 text-center space-y-2">
            <h1 class="text-2xl font-bold">Playlist unavailable</h1>
            <p class="text-neutral-500">It may be private, or it may have been deleted.</p>
          </div>
        </Match>
        <Match when={playlist.data}>
          {(p) => (
            <>
              <header class="space-y-2">
                <Show
                  when={editing()}
                  fallback={
                    <>
                      <h1 class="text-2xl font-bold">{p().title}</h1>
                      <p class="text-sm text-neutral-500">
                        <A href={`/c/${p().owner?.handle}`} class="hover:underline">
                          {p().owner?.displayName}
                        </A>{' '}
                        · {countOf(p().itemCount, 'video')} · {VISIBILITY_LABELS[p().visibility]}
                      </p>
                      <Show when={p().description}>
                        <p class="whitespace-pre-wrap text-sm">{p().description}</p>
                      </Show>
                    </>
                  }
                >
                  <form
                    class="space-y-2"
                    onSubmit={(e) => {
                      e.preventDefault();
                      void run(() => updatePlaylist(p().publicId, { title: title().trim(), description: description() }));
                      setEditing(false);
                    }}
                  >
                    <input
                      class="w-full rounded-md border border-neutral-300 dark:border-neutral-700 bg-transparent px-3 py-2 text-lg font-semibold"
                      required
                      maxLength={150}
                      value={title()}
                      onInput={(e) => setTitle(e.currentTarget.value)}
                    />
                    <textarea
                      class="w-full rounded-md border border-neutral-300 dark:border-neutral-700 bg-transparent px-3 py-2 text-sm"
                      maxLength={5000}
                      value={description()}
                      onInput={(e) => setDescription(e.currentTarget.value)}
                    />
                    <div class="flex gap-2 text-sm">
                      <button type="submit" class="rounded bg-red-600 px-3 py-1 text-white">
                        Save
                      </button>
                      <button type="button" class="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-1" onClick={() => setEditing(false)}>
                        Cancel
                      </button>
                    </div>
                  </form>
                </Show>

                <div class="flex flex-wrap items-center gap-3 text-sm">
                  <Show when={p().items?.length}>
                    <A
                      href={`/watch/${p().items![0].publicId}?list=${p().publicId}`}
                      class="rounded-full bg-neutral-900 px-4 py-2 font-medium text-white dark:bg-white dark:text-neutral-900"
                    >
                      ▶ Play all
                    </A>
                  </Show>
                  <Show when={p().canEdit}>
                    <VisibilitySelect value={p().visibility} onChange={(visibility) => void run(() => updatePlaylist(p().publicId, { visibility }))} />
                    <Show when={!editing()}>
                      <button
                        class="text-neutral-500 hover:text-neutral-900 dark:hover:text-white"
                        onClick={() => {
                          setTitle(p().title);
                          setDescription(p().description);
                          setEditing(true);
                        }}
                      >
                        Edit
                      </button>
                    </Show>
                    <button
                      class="text-red-600 hover:underline"
                      onClick={async () => {
                        if (!confirm(`Delete the playlist "${p().title}"? The videos are not deleted.`)) return;
                        await deletePlaylist(p().publicId);
                        void queryClient.invalidateQueries({ queryKey: ['my-playlists'] });
                        navigate('/playlists');
                      }}
                    >
                      Delete playlist
                    </button>
                  </Show>
                </div>
                <Show when={error()}>
                  <p role="alert" class="text-sm text-red-600">
                    {error()}
                  </p>
                </Show>
              </header>

              <Show when={p().items?.length} fallback={<p class="text-neutral-500">No videos in this playlist yet.</p>}>
                <ol class="divide-y divide-neutral-200 dark:divide-neutral-800">
                  <For each={p().items}>
                    {(v, i) => (
                      <li class="flex items-center gap-3 py-2">
                        <span class="w-6 text-right text-sm text-neutral-500">{i() + 1}</span>
                        <A href={`/watch/${v.publicId}?list=${p().publicId}`} class="flex min-w-0 flex-1 items-center gap-3">
                          <div class="relative aspect-video w-32 shrink-0 overflow-hidden rounded-lg bg-neutral-200 dark:bg-neutral-800">
                            <Show when={v.posterUrl}>{(src) => <img src={src()} alt="" class="h-full w-full object-cover" />}</Show>
                            <span class="absolute bottom-1 right-1 rounded bg-black/80 px-1 text-xs text-white">{formatDuration(v.durationMs)}</span>
                          </div>
                          <div class="min-w-0">
                            <p class="truncate font-medium">{v.title}</p>
                            <p class="text-sm text-neutral-500">{v.channel?.displayName}</p>
                          </div>
                        </A>
                        <Show when={p().canEdit}>
                          <div class="flex shrink-0 gap-1 text-sm">
                            <button
                              aria-label="Move up"
                              disabled={i() === 0}
                              class="rounded px-2 py-1 hover:bg-neutral-200 dark:hover:bg-neutral-800 disabled:opacity-30"
                              onClick={() => void run(() => reorderPlaylist(p().publicId, move(p().items!, i(), -1).map((x) => x.publicId)))}
                            >
                              ↑
                            </button>
                            <button
                              aria-label="Move down"
                              disabled={i() === p().items!.length - 1}
                              class="rounded px-2 py-1 hover:bg-neutral-200 dark:hover:bg-neutral-800 disabled:opacity-30"
                              onClick={() => void run(() => reorderPlaylist(p().publicId, move(p().items!, i(), 1).map((x) => x.publicId)))}
                            >
                              ↓
                            </button>
                            <button
                              class="rounded px-2 py-1 text-red-600 hover:bg-neutral-200 dark:hover:bg-neutral-800"
                              onClick={() => void run(() => removeFromPlaylist(p().publicId, v.publicId))}
                            >
                              Remove
                            </button>
                          </div>
                        </Show>
                      </li>
                    )}
                  </For>
                </ol>
              </Show>
            </>
          )}
        </Match>
      </Switch>
    </section>
  );
}
