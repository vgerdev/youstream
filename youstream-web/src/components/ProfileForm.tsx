import { createSignal, Show } from 'solid-js';
import { A } from '@solidjs/router';
import { updateProfile } from '../api/social';
import { useAuth } from '../auth/AuthProvider';

/** Account page: channel handle, display name and bio. */
export default function ProfileForm() {
  const auth = useAuth();
  const user = () => auth.user()!;
  const [handle, setHandle] = createSignal(user().handle);
  const [displayName, setDisplayName] = createSignal(user().displayName);
  const [bio, setBio] = createSignal(user().bio);
  const [message, setMessage] = createSignal<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = createSignal(false);

  const save = async (e: SubmitEvent) => {
    e.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      const updated = await updateProfile({ handle: handle().trim(), displayName: displayName().trim(), bio: bio() });
      auth.setUser(updated);
      setHandle(updated.handle);
      setMessage({ ok: true, text: 'Saved.' });
    } catch (err) {
      setMessage({ ok: false, text: err instanceof Error ? err.message : 'Could not save' });
    } finally {
      setBusy(false);
    }
  };

  const input =
    'w-full rounded-md border border-neutral-300 dark:border-neutral-700 bg-transparent px-3 py-2 ' +
    'focus:outline-none focus:ring-2 focus:ring-red-600';

  return (
    <section class="space-y-3">
      <h2 class="text-lg font-semibold">Channel</h2>
      <form class="space-y-3" onSubmit={save}>
        <label class="block space-y-1">
          <span class="text-sm font-medium">Handle</span>
          <div class="flex items-center gap-1">
            <span class="text-neutral-500">@</span>
            <input
              class={input}
              required
              minLength={3}
              maxLength={30}
              pattern="[a-zA-Z0-9][a-zA-Z0-9._\-]{2,29}"
              value={handle()}
              onInput={(e) => setHandle(e.currentTarget.value)}
            />
          </div>
          <span class="text-xs text-neutral-500">
            Your channel lives at{' '}
            <A href={`/c/${user().handle}`} class="text-red-600 hover:underline">
              /c/{user().handle}
            </A>
          </span>
        </label>
        <label class="block space-y-1">
          <span class="text-sm font-medium">Display name</span>
          <input class={input} required maxLength={50} value={displayName()} onInput={(e) => setDisplayName(e.currentTarget.value)} />
        </label>
        <label class="block space-y-1">
          <span class="text-sm font-medium">About</span>
          <textarea class={`${input} min-h-20`} maxLength={1000} value={bio()} onInput={(e) => setBio(e.currentTarget.value)} />
        </label>
        <div class="flex items-center gap-3">
          <button
            type="submit"
            disabled={busy()}
            class="rounded-md bg-red-600 px-4 py-2 font-medium text-white hover:bg-red-700 disabled:opacity-60"
          >
            Save
          </button>
          <Show when={message()}>
            {(m) => (
              <span role="status" class={`text-sm ${m().ok ? 'text-green-600' : 'text-red-600'}`}>
                {m().text}
              </span>
            )}
          </Show>
        </div>
      </form>
    </section>
  );
}
