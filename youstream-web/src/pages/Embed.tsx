import { Match, Switch } from 'solid-js';
import { useParams, useSearchParams } from '@solidjs/router';
import { useQuery } from '@tanstack/solid-query';
import { recordView } from '../api/social';
import { getVideo } from '../api/videos';
import Player from '../components/Player';

/** Bare player for other sites' iframes (/embed/:id). The only page that may be framed. */
export default function Embed() {
  const params = useParams<{ id: string }>();
  const [search] = useSearchParams<{ t?: string }>();
  const video = useQuery(() => ({ queryKey: ['video', params.id], queryFn: () => getVideo(params.id), retry: false }));

  return (
    <div class="h-screen w-screen bg-black">
      <Switch fallback={<div class="grid h-full place-items-center text-neutral-400">Loading…</div>}>
        <Match when={video.isError || (video.data && !video.data.playbackUrl)}>
          <div class="grid h-full place-items-center text-center text-neutral-300">
            <p>
              This video is unavailable.
              <br />
              <a href={`/watch/${params.id}`} target="_blank" rel="noopener" class="text-red-500 hover:underline">
                Try watching it on YouStream
              </a>
            </p>
          </div>
        </Match>
        <Match when={video.data?.playbackUrl}>
          {(src) => (
            <div class="relative h-full">
              <Player
                src={src()}
                poster={video.data?.posterUrl}
                startAt={Number(search.t) || 0}
                fill
                onWatched={() => void recordView(params.id).catch(() => undefined)}
              />
              <a
                href={`/watch/${params.id}`}
                target="_blank"
                rel="noopener"
                class="absolute left-3 top-3 max-w-[70%] truncate rounded bg-black/60 px-2 py-1 text-sm text-white hover:underline"
              >
                {video.data?.title} · YouStream
              </a>
            </div>
          )}
        </Match>
      </Switch>
    </div>
  );
}
