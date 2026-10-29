import { createSignal, onCleanup, Show } from 'solid-js';
import { useNavigate } from '@solidjs/router';
import { useQueryClient } from '@tanstack/solid-query';
import { ApiError } from '../api/client';
import { abortUpload, completeUpload, type Visibility } from '../api/videos';
import VisibilitySelect, { VISIBILITY_HINTS } from '../components/VisibilitySelect';
import { forgetUpload, uploadFile } from '../upload/uploader';
import { formatBytes } from '../util/format';

type Phase = 'pick' | 'uploading' | 'uploaded' | 'saving';

const titleFromFile = (name: string) => name.replace(/\.[^.]+$/, '').replace(/_/g, ' ').trim().slice(0, 100);

export default function Upload() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [file, setFile] = createSignal<File | null>(null);
  const [phase, setPhase] = createSignal<Phase>('pick');
  const [sent, setSent] = createSignal(0);
  const [resumed, setResumed] = createSignal(false);
  const [error, setError] = createSignal<string | null>(null);
  const [dragging, setDragging] = createSignal(false);
  const [uploadId, setUploadId] = createSignal<string | null>(null);
  const [title, setTitle] = createSignal('');
  const [description, setDescription] = createSignal('');
  const [visibility, setVisibility] = createSignal<Visibility>('PRIVATE');
  let controller: AbortController | undefined;

  onCleanup(() => controller?.abort());

  const start = async (f: File) => {
    setFile(f);
    setTitle(titleFromFile(f.name));
    setError(null);
    setPhase('uploading');
    controller = new AbortController();
    try {
      const id = await uploadFile(
        f,
        (p) => {
          setSent(p.sent);
          setResumed(p.resumed);
        },
        controller.signal,
      );
      setUploadId(id);
      setPhase('uploaded');
    } catch (e) {
      if (controller.signal.aborted) return;
      setError(e instanceof Error ? e.message : 'Upload failed');
      setPhase('pick');
    }
  };

  const save = async (e: SubmitEvent) => {
    e.preventDefault();
    const id = uploadId();
    const f = file();
    if (!id || !f) return;
    setPhase('saving');
    setError(null);
    try {
      await completeUpload(id, { title: title().trim(), description: description(), visibility: visibility() });
      forgetUpload(f);
      await queryClient.invalidateQueries({ queryKey: ['my-videos'] });
      navigate('/studio');
    } catch (err) {
      setPhase('uploaded');
      if (err instanceof ApiError && err.code === 'invalid_media') {
        forgetUpload(f);
        setError(`That file isn't a video we can process (${err.message}).`);
        setPhase('pick');
      } else {
        setError(err instanceof Error ? err.message : 'Could not save the video');
      }
    }
  };

  const cancel = async () => {
    controller?.abort();
    const id = uploadId();
    const f = file();
    if (f) forgetUpload(f);
    if (id) await abortUpload(id).catch(() => undefined);
    setFile(null);
    setUploadId(null);
    setSent(0);
    setPhase('pick');
  };

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragging(false);
    const f = e.dataTransfer?.files[0];
    if (f) void start(f);
  };

  const percent = () => (file() ? Math.floor((sent() / file()!.size) * 100) : 0);
  const input =
    'w-full rounded-md border border-neutral-300 dark:border-neutral-700 bg-transparent px-3 py-2 ' +
    'focus:outline-none focus:ring-2 focus:ring-red-600';

  return (
    <section class="mx-auto mt-6 max-w-2xl space-y-6">
      <h1 class="text-2xl font-bold">Upload a video</h1>

      <Show when={error()}>
        <p role="alert" class="rounded-md bg-red-50 dark:bg-red-950 px-3 py-2 text-sm text-red-700 dark:text-red-300">
          {error()}
        </p>
      </Show>

      <Show when={phase() === 'pick'}>
        <label
          class={`flex cursor-pointer flex-col items-center gap-3 rounded-xl border-2 border-dashed px-6 py-16 text-center transition-colors ${
            dragging() ? 'border-red-600 bg-red-50 dark:bg-red-950/30' : 'border-neutral-300 dark:border-neutral-700'
          }`}
          onDragOver={(e) => {
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={onDrop}
        >
          <span class="text-4xl" aria-hidden="true">
            ⬆
          </span>
          <span class="font-medium">Drag and drop a video file, or click to choose one</span>
          <span class="text-sm text-neutral-500">MP4, MOV, MKV, WebM… up to 10 GB</span>
          <input
            type="file"
            accept="video/*"
            class="sr-only"
            onChange={(e) => {
              const f = e.currentTarget.files?.[0];
              if (f) void start(f);
            }}
          />
        </label>
      </Show>

      <Show when={phase() !== 'pick' && file()}>
        {(f) => (
          <div class="space-y-6">
            <div class="space-y-2">
              <div class="flex justify-between text-sm">
                <span class="truncate font-medium">{f().name}</span>
                <span class="text-neutral-500">
                  {formatBytes(sent())} / {formatBytes(f().size)}
                </span>
              </div>
              <div class="h-2 overflow-hidden rounded-full bg-neutral-200 dark:bg-neutral-800">
                <div class="h-full bg-red-600 transition-[width]" style={{ width: `${percent()}%` }} />
              </div>
              <p class="text-sm text-neutral-500">
                {phase() === 'uploading'
                  ? `${resumed() ? 'Resuming upload' : 'Uploading'}… ${percent()}% — you can fill in the details meanwhile`
                  : 'Upload complete. Save to start processing.'}
              </p>
            </div>

            <form class="space-y-4" onSubmit={save}>
              <label class="block space-y-1">
                <span class="text-sm font-medium">Title</span>
                <input
                  class={input}
                  required
                  maxLength={100}
                  value={title()}
                  onInput={(e) => setTitle(e.currentTarget.value)}
                />
              </label>
              <label class="block space-y-1">
                <span class="text-sm font-medium">Description</span>
                <textarea
                  class={`${input} min-h-28`}
                  maxLength={5000}
                  value={description()}
                  onInput={(e) => setDescription(e.currentTarget.value)}
                />
              </label>
              <label class="flex items-center gap-3">
                <span class="text-sm font-medium">Visibility</span>
                <VisibilitySelect value={visibility()} onChange={setVisibility} />
                <span class="text-sm text-neutral-500">{VISIBILITY_HINTS[visibility()]}</span>
              </label>
              <div class="flex gap-3">
                <button
                  type="submit"
                  disabled={phase() !== 'uploaded'}
                  class="rounded-md bg-red-600 px-4 py-2 font-medium text-white hover:bg-red-700 disabled:opacity-50"
                >
                  {phase() === 'saving' ? 'Saving…' : 'Save'}
                </button>
                <button
                  type="button"
                  onClick={cancel}
                  class="rounded-md border border-neutral-300 dark:border-neutral-700 px-4 py-2 font-medium"
                >
                  Cancel
                </button>
              </div>
            </form>
          </div>
        )}
      </Show>
    </section>
  );
}
