import { Show } from 'solid-js';
import { A } from '@solidjs/router';
import type { Video } from '../api/videos';
import { countOf, formatDuration, timeAgo } from '../util/format';

export default function VideoCard(props: { video: Video }) {
  const v = () => props.video;
  return (
    <A href={`/watch/${v().publicId}`} class="group block space-y-2">
      <div class="relative aspect-video overflow-hidden rounded-xl bg-neutral-200 dark:bg-neutral-800">
        <Show when={v().posterUrl}>
          {(src) => (
            <img
              src={src()}
              alt=""
              loading="lazy"
              class="h-full w-full object-cover transition-transform group-hover:scale-105"
            />
          )}
        </Show>
        <Show when={v().durationMs}>
          <span class="absolute bottom-1.5 right-1.5 rounded bg-black/80 px-1 text-xs font-medium text-white">
            {formatDuration(v().durationMs)}
          </span>
        </Show>
      </div>
      <div>
        <h3 class="line-clamp-2 font-semibold leading-snug">{v().title}</h3>
        <p class="text-sm text-neutral-500">{v().channel?.displayName}</p>
        <p class="text-sm text-neutral-500">
          {countOf(v().viewCount, 'view')} · {timeAgo(v().publishedAt)}
        </p>
      </div>
    </A>
  );
}
