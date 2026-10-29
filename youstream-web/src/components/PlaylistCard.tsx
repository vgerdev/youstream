import { Show } from 'solid-js';
import { A } from '@solidjs/router';
import type { Playlist } from '../api/playlists';
import { VISIBILITY_LABELS } from './VisibilitySelect';
import { countOf } from '../util/format';

export default function PlaylistCard(props: { playlist: Playlist }) {
  const p = () => props.playlist;
  return (
    <A href={`/playlist/${p().publicId}`} class="group block space-y-2">
      <div class="relative aspect-video overflow-hidden rounded-xl bg-neutral-200 dark:bg-neutral-800">
        <Show when={p().thumbnailUrl}>
          {(src) => <img src={src()} alt="" loading="lazy" class="h-full w-full object-cover transition-transform group-hover:scale-105" />}
        </Show>
        <span class="absolute inset-y-0 right-0 grid w-1/3 place-items-center bg-black/70 text-sm font-medium text-white">
          <span class="text-center">
            {p().itemCount}
            <br />
            ▶︎ list
          </span>
        </span>
      </div>
      <div>
        <h3 class="line-clamp-2 font-semibold leading-snug">{p().title}</h3>
        <p class="text-sm text-neutral-500">
          {p().owner?.displayName} · {countOf(p().itemCount, 'video')}
          <Show when={p().visibility !== 'PUBLIC'}> · {VISIBILITY_LABELS[p().visibility]}</Show>
        </p>
      </div>
    </A>
  );
}
