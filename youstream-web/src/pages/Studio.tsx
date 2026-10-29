import { createSignal, For, Match, Show, Switch } from 'solid-js';
import { A } from '@solidjs/router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/solid-query';
import { deleteVideo, listMyVideos, updateVideo, type Video, type VideoUpdate } from '../api/videos';
import VisibilitySelect from '../components/VisibilitySelect';
import { formatBytes, formatDuration, timeAgo } from '../util/format';

const busy = (v: Video) => v.status === 'UPLOADED' || v.status === 'PROCESSING';

export default function Studio() {
  const queryClient = useQueryClient();
  const videos = useQuery(() => ({
    queryKey: ['my-videos'],
    queryFn: () => listMyVideos(),
    // poll while anything is still being processed
    refetchInterval: (query) => (query.state.data?.items.some(busy) ? 3000 : false),
  }));
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['my-videos'] });

  const update = useMutation(() => ({
    mutationFn: (args: { id: string; update: VideoUpdate }) => updateVideo(args.id, args.update),
    onSuccess: refresh,
  }));
  const remove = useMutation(() => ({ mutationFn: deleteVideo, onSuccess: refresh }));

  return (
    <section class="mx-auto mt-6 max-w-5xl space-y-6">
      <header class="flex items-center justify-between">
        <h1 class="text-2xl font-bold">Your videos</h1>
        <A href="/upload" class="rounded-md bg-red-600 px-4 py-2 font-medium text-white hover:bg-red-700">
          Upload
        </A>
      </header>

      <Show when={update.error || remove.error}>
        <p role="alert" class="text-sm text-red-600">
          {(update.error ?? remove.error)?.message}
        </p>
      </Show>

      <Show when={videos.data} fallback={<p class="text-neutral-500">Loading…</p>}>
        {(page) => (
          <Show
            when={page().items.length > 0}
            fallback={
              <p class="text-neutral-500">
                No videos yet. <A href="/upload" class="text-red-600 hover:underline">Upload your first one</A>.
              </p>
            }
          >
            <ul class="divide-y divide-neutral-200 dark:divide-neutral-800">
              <For each={page().items}>
                {(v) => (
                  <StudioRow
                    video={v}
                    onUpdate={(u) => update.mutate({ id: v.publicId, update: u })}
                    onDelete={() => {
                      if (confirm(`Delete "${v.title}"? This cannot be undone.`)) remove.mutate(v.publicId);
                    }}
                  />
                )}
              </For>
            </ul>
          </Show>
        )}
      </Show>
    </section>
  );
}

function StudioRow(props: { video: Video; onUpdate: (u: VideoUpdate) => void; onDelete: () => void }) {
  const v = () => props.video;
  const [editing, setEditing] = createSignal(false);
  const [title, setTitle] = createSignal('');
  const [description, setDescription] = createSignal('');

  const edit = () => {
    setTitle(v().title);
    setDescription(v().description);
    setEditing(true);
  };

  return (
    <li class="flex gap-4 py-4">
      <A href={`/watch/${v().publicId}`} class="relative aspect-video w-40 shrink-0 overflow-hidden rounded-lg bg-neutral-200 dark:bg-neutral-800">
        <Show when={v().posterUrl}>{(src) => <img src={src()} alt="" class="h-full w-full object-cover" />}</Show>
        <Show when={v().durationMs}>
          <span class="absolute bottom-1 right-1 rounded bg-black/80 px-1 text-xs text-white">
            {formatDuration(v().durationMs)}
          </span>
        </Show>
      </A>

      <div class="min-w-0 flex-1 space-y-1">
        <Show
          when={editing()}
          fallback={
            <>
              <h2 class="truncate font-semibold">{v().title}</h2>
              <p class="line-clamp-1 text-sm text-neutral-500">{v().description || 'No description'}</p>
            </>
          }
        >
          <form
            class="space-y-2"
            onSubmit={(e) => {
              e.preventDefault();
              props.onUpdate({ title: title().trim(), description: description() });
              setEditing(false);
            }}
          >
            <input
              class="w-full rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1"
              required
              maxLength={100}
              value={title()}
              onInput={(e) => setTitle(e.currentTarget.value)}
            />
            <textarea
              class="w-full rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1"
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

        <div class="text-sm">
          <Switch>
            <Match when={v().status === 'READY'}>
              <span class="text-green-600">Ready</span>
              <span class="text-neutral-500"> · published {timeAgo(v().publishedAt)}</span>
            </Match>
            <Match when={busy(v())}>
              <div class="flex items-center gap-2">
                <span class="text-amber-600">{v().status === 'PROCESSING' ? 'Processing' : 'Queued'}</span>
                <div class="h-1.5 w-40 overflow-hidden rounded-full bg-neutral-200 dark:bg-neutral-800">
                  <div class="h-full bg-amber-500 transition-[width]" style={{ width: `${Math.round((v().progress ?? 0) * 100)}%` }} />
                </div>
                <span class="text-neutral-500">{Math.round((v().progress ?? 0) * 100)}%</span>
              </div>
              <Show when={v().error}>
                <p class="text-xs text-neutral-500">Retrying after error: {v().error}</p>
              </Show>
            </Match>
            <Match when={v().status === 'FAILED'}>
              <span class="text-red-600">Processing failed</span>
              <span class="text-neutral-500"> · {v().error}</span>
            </Match>
          </Switch>
        </div>
        <p class="text-xs text-neutral-500">
          {v().originalFilename} · {formatBytes(v().sizeBytes)} · uploaded {timeAgo(v().createdAt)}
        </p>
      </div>

      <div class="flex shrink-0 flex-col items-end gap-2 text-sm">
        <VisibilitySelect value={v().visibility} onChange={(visibility) => props.onUpdate({ visibility })} />
        <div class="flex gap-3">
          <Show when={!editing()}>
            <button class="text-neutral-500 hover:text-neutral-900 dark:hover:text-white" onClick={edit}>
              Edit
            </button>
          </Show>
          <button class="text-red-600 hover:underline" onClick={props.onDelete}>
            Delete
          </button>
        </div>
      </div>
    </li>
  );
}
