import { createSignal, For, Show } from 'solid-js';
import { useNavigate } from '@solidjs/router';
import { useQuery } from '@tanstack/solid-query';
import { createPlaylist, listMyPlaylists } from '../api/playlists';
import type { Visibility } from '../api/videos';
import PlaylistCard from '../components/PlaylistCard';
import VisibilitySelect from '../components/VisibilitySelect';

/** The signed-in user's playlists. */
export default function Playlists() {
  const navigate = useNavigate();
  const playlists = useQuery(() => ({ queryKey: ['my-playlists'], queryFn: () => listMyPlaylists() }));
  const [title, setTitle] = createSignal('');
  const [visibility, setVisibility] = createSignal<Visibility>('PRIVATE');

  const create = async (e: SubmitEvent) => {
    e.preventDefault();
    if (!title().trim()) return;
    const p = await createPlaylist(title().trim(), visibility());
    navigate(`/playlist/${p.publicId}`);
  };

  return (
    <section class="mx-auto max-w-7xl space-y-6">
      <header class="flex flex-wrap items-center justify-between gap-4">
        <h1 class="text-2xl font-bold">Your playlists</h1>
        <form class="flex gap-2" onSubmit={create}>
          <input
            class="rounded-md border border-neutral-300 dark:border-neutral-700 bg-transparent px-3 py-1.5 text-sm"
            placeholder="New playlist name"
            maxLength={150}
            value={title()}
            onInput={(e) => setTitle(e.currentTarget.value)}
          />
          <VisibilitySelect value={visibility()} onChange={setVisibility} class="text-sm" />
          <button type="submit" class="rounded-md bg-red-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-red-700">
            Create
          </button>
        </form>
      </header>
      <Show
        when={playlists.data?.length}
        fallback={<p class="text-neutral-500">No playlists yet. Use “Save” on any video, or create one above.</p>}
      >
        <div class="grid gap-x-4 gap-y-8 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          <For each={playlists.data}>{(p) => <PlaylistCard playlist={p} />}</For>
        </div>
      </Show>
    </section>
  );
}
