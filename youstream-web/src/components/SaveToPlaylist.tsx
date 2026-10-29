import { createSignal, For, Show } from 'solid-js';
import { useQuery, useQueryClient } from '@tanstack/solid-query';
import { addToPlaylist, createPlaylist, listMyPlaylists, removeFromPlaylist } from '../api/playlists';
import type { Visibility } from '../api/videos';
import Modal from './Modal';
import VisibilitySelect from './VisibilitySelect';

/** Checkbox list of the user's playlists for one video, plus "new playlist". */
export default function SaveToPlaylist(props: { videoId: string; open: boolean; onClose: () => void }) {
  const queryClient = useQueryClient();
  const playlists = useQuery(() => ({
    queryKey: ['my-playlists', props.videoId],
    queryFn: () => listMyPlaylists(props.videoId),
    enabled: props.open,
  }));
  const [title, setTitle] = createSignal('');
  const [visibility, setVisibility] = createSignal<Visibility>('PRIVATE');
  const [error, setError] = createSignal<string | null>(null);

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['my-playlists'] });
    void queryClient.invalidateQueries({ queryKey: ['playlist'] });
  };

  const toggle = async (playlistId: string, checked: boolean) => {
    setError(null);
    try {
      if (checked) await addToPlaylist(playlistId, props.videoId);
      else await removeFromPlaylist(playlistId, props.videoId);
      refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not update the playlist');
    }
  };

  const create = async (e: SubmitEvent) => {
    e.preventDefault();
    if (!title().trim()) return;
    setError(null);
    try {
      await createPlaylist(title().trim(), visibility(), props.videoId);
      setTitle('');
      refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create the playlist');
    }
  };

  return (
    <Modal open={props.open} title="Save to playlist" onClose={props.onClose}>
      <Show when={playlists.data} fallback={<p class="text-sm text-neutral-500">Loading…</p>}>
        {(lists) => (
          <Show when={lists().length > 0} fallback={<p class="text-sm text-neutral-500">You have no playlists yet.</p>}>
            <ul class="max-h-64 space-y-1 overflow-y-auto">
              <For each={lists()}>
                {(p) => (
                  <li>
                    <label class="flex items-center gap-3 rounded px-2 py-1.5 hover:bg-neutral-100 dark:hover:bg-neutral-800">
                      <input
                        type="checkbox"
                        checked={p.containsVideo ?? false}
                        onChange={(e) => void toggle(p.publicId, e.currentTarget.checked)}
                      />
                      <span class="flex-1 truncate">{p.title}</span>
                      <span class="text-xs text-neutral-500">{p.visibility.toLowerCase()}</span>
                    </label>
                  </li>
                )}
              </For>
            </ul>
          </Show>
        )}
      </Show>
      <form class="flex gap-2 border-t border-neutral-200 dark:border-neutral-800 pt-4" onSubmit={create}>
        <input
          class="min-w-0 flex-1 rounded-md border border-neutral-300 dark:border-neutral-700 bg-transparent px-3 py-1.5 text-sm"
          placeholder="New playlist"
          maxLength={150}
          value={title()}
          onInput={(e) => setTitle(e.currentTarget.value)}
        />
        <VisibilitySelect value={visibility()} onChange={setVisibility} class="text-sm" />
        <button type="submit" class="rounded-md bg-red-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-red-700">
          Create
        </button>
      </form>
      <Show when={error()}>
        <p role="alert" class="text-sm text-red-600">
          {error()}
        </p>
      </Show>
    </Modal>
  );
}
