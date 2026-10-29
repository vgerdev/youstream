import { createSignal, For, onCleanup, onMount, Show } from 'solid-js';
import type Hls from 'hls.js';

interface Level {
  index: number;
  height: number;
}

/**
 * HLS player: hls.js (adaptive bitrate + quality menu) where Media Source Extensions exist, native HLS otherwise
 * (Safari/iOS).
 */
export default function Player(props: {
  src: string;
  poster?: string | null;
  startAt?: number;
  /** Fired once, after a few seconds of actual playback (used to count a view). */
  onWatched?: () => void;
  onEnded?: () => void;
  /** Gives the caller the <video> element (e.g. to read the current time for sharing). */
  onElement?: (video: HTMLVideoElement) => void;
  autoplay?: boolean;
  /** Fill the parent (embed page) instead of a rounded 16:9 box, with no quality menu below. */
  fill?: boolean;
}) {
  let video!: HTMLVideoElement;
  let hls: Hls | undefined;
  const [levels, setLevels] = createSignal<Level[]>([]);
  const [level, setLevel] = createSignal(-1);
  const [error, setError] = createSignal<string | null>(null);

  onMount(async () => {
    const { default: HlsJs } = await import('hls.js');
    if (HlsJs.isSupported()) {
      hls = new HlsJs({ capLevelToPlayerSize: true });
      hls.on(HlsJs.Events.MANIFEST_PARSED, (_e, data) => {
        setLevels(data.levels.map((l, index) => ({ index, height: l.height })).sort((a, b) => b.height - a.height));
      });
      hls.on(HlsJs.Events.ERROR, (_e, data) => {
        if (data.fatal) setError('This video could not be played.');
      });
      hls.loadSource(props.src);
      hls.attachMedia(video);
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = props.src;
    } else {
      setError('Your browser cannot play this video.');
    }
    if (props.startAt) video.currentTime = props.startAt;
    props.onElement?.(video);
    if (props.onEnded) video.addEventListener('ended', props.onEnded);
    // browsers allow autoplay with sound after a user gesture (e.g. clicking through a playlist)
    if (props.autoplay) void video.play().catch(() => undefined);

    // a view = 3 s of playback, or half of a shorter video
    let played = 0;
    let last = 0;
    let fired = false;
    video.addEventListener('timeupdate', () => {
      const t = video.currentTime;
      if (!video.paused && t > last && t - last < 1.5) played += t - last;
      last = t;
      const needed = Math.min(3, (video.duration || 6) / 2);
      if (!fired && played >= needed) {
        fired = true;
        props.onWatched?.();
      }
    });
  });

  onCleanup(() => hls?.destroy());

  const chooseLevel = (value: number) => {
    setLevel(value);
    if (hls) hls.currentLevel = value; // -1 = automatic
  };

  return (
    <div class={props.fill ? 'h-full' : 'space-y-2'}>
      <div class={props.fill ? 'relative h-full bg-black' : 'relative aspect-video overflow-hidden rounded-xl bg-black'}>
        <video ref={video} class="h-full w-full" controls playsinline poster={props.poster ?? undefined} />
        <Show when={error()}>
          <p class="absolute inset-0 grid place-items-center text-white">{error()}</p>
        </Show>
      </div>
      <Show when={!props.fill && levels().length > 1}>
        <label class="flex items-center justify-end gap-2 text-sm text-neutral-500">
          Quality
          <select
            class="rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1"
            value={level()}
            onChange={(e) => chooseLevel(Number(e.currentTarget.value))}
          >
            <option value={-1}>Auto</option>
            <For each={levels()}>{(l) => <option value={l.index}>{l.height}p</option>}</For>
          </select>
        </label>
      </Show>
    </div>
  );
}
