import { onCleanup, onMount, Show, type ParentProps } from 'solid-js';
import { Portal } from 'solid-js/web';

/** Accessible dialog: closes on Escape and backdrop click. */
export default function Modal(props: ParentProps<{ open: boolean; title: string; onClose: () => void }>) {
  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape') props.onClose();
  };
  onMount(() => document.addEventListener('keydown', onKey));
  onCleanup(() => document.removeEventListener('keydown', onKey));

  return (
    <Show when={props.open}>
      <Portal>
        <div class="fixed inset-0 z-50 grid place-items-center bg-black/50 p-4" onClick={props.onClose}>
          <div
            role="dialog"
            aria-modal="true"
            aria-label={props.title}
            class="w-full max-w-md space-y-4 rounded-xl bg-white dark:bg-neutral-900 p-5 text-neutral-900 dark:text-neutral-100 shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div class="flex items-center justify-between">
              <h2 class="text-lg font-semibold">{props.title}</h2>
              <button aria-label="Close" class="text-xl text-neutral-500 hover:text-neutral-900 dark:hover:text-white" onClick={props.onClose}>
                ×
              </button>
            </div>
            {props.children}
          </div>
        </div>
      </Portal>
    </Show>
  );
}
