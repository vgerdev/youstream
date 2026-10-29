import { createSignal, Show } from 'solid-js';
import type { Video } from '../api/videos';
import { formatDuration } from '../util/format';
import Modal from './Modal';

function CopyField(props: { label: string; value: string; multiline?: boolean }) {
  const [copied, setCopied] = createSignal(false);
  const copy = async () => {
    await navigator.clipboard.writeText(props.value);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };
  const field = 'w-full rounded-md border border-neutral-300 dark:border-neutral-700 bg-neutral-50 dark:bg-neutral-950 px-3 py-2 font-mono text-xs';
  return (
    <div class="space-y-1">
      <span class="text-sm font-medium">{props.label}</span>
      <div class="flex gap-2">
        <Show when={props.multiline} fallback={<input class={field} readonly value={props.value} onFocus={(e) => e.currentTarget.select()} />}>
          <textarea class={`${field} min-h-20`} readonly value={props.value} onFocus={(e) => e.currentTarget.select()} />
        </Show>
        <button class="shrink-0 self-start rounded-md bg-red-600 px-3 py-2 text-sm font-medium text-white hover:bg-red-700" onClick={copy}>
          {copied() ? 'Copied' : 'Copy'}
        </button>
      </div>
    </div>
  );
}

/** Link (optionally at the current time) and embed snippet. */
export default function ShareDialog(props: { video: Video; currentTime: () => number; open: boolean; onClose: () => void }) {
  const [atTime, setAtTime] = createSignal(false);
  const seconds = () => Math.floor(props.currentTime());
  const link = () => `${location.origin}/watch/${props.video.publicId}${atTime() && seconds() > 0 ? `?t=${seconds()}` : ''}`;
  const embed = () =>
    `<iframe width="560" height="315" src="${location.origin}/embed/${props.video.publicId}${atTime() && seconds() > 0 ? `?t=${seconds()}` : ''}" ` +
    `title="${props.video.title.replace(/"/g, '&quot;')}" frameborder="0" allow="autoplay; fullscreen; picture-in-picture" allowfullscreen></iframe>`;

  return (
    <Modal open={props.open} title="Share" onClose={props.onClose}>
      <CopyField label="Link" value={link()} />
      <label class="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={atTime()} onChange={(e) => setAtTime(e.currentTarget.checked)} />
        Start at {formatDuration(seconds() * 1000)}
      </label>
      <Show
        when={props.video.visibility !== 'PRIVATE'}
        fallback={<p class="text-sm text-neutral-500">Private videos can only be watched by you, so they cannot be embedded.</p>}
      >
        <CopyField label="Embed" value={embed()} multiline />
      </Show>
    </Modal>
  );
}
