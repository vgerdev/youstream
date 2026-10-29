import { createSignal, Show } from 'solid-js';
import { authErrorMessage } from '../auth/errors';

interface Props {
  submitLabel: string;
  onSubmit(username: string, password: string): Promise<unknown>;
}

/** Username/password form for directory (LDAP) accounts. */
export default function LdapLoginForm(props: Props) {
  const [username, setUsername] = createSignal('');
  const [password, setPassword] = createSignal('');
  const [error, setError] = createSignal<string | null>(null);
  const [busy, setBusy] = createSignal(false);

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await props.onSubmit(username().trim(), password());
      setPassword('');
    } catch (err) {
      setError(authErrorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const input =
    'w-full rounded-md border border-neutral-300 dark:border-neutral-700 bg-transparent px-3 py-2 ' +
    'focus:outline-none focus:ring-2 focus:ring-red-600';

  return (
    <form class="space-y-3" onSubmit={submit}>
      <label class="block space-y-1">
        <span class="text-sm font-medium">Username</span>
        <input
          class={input}
          name="username"
          autocomplete="username"
          required
          value={username()}
          onInput={(e) => setUsername(e.currentTarget.value)}
        />
      </label>
      <label class="block space-y-1">
        <span class="text-sm font-medium">Password</span>
        <input
          class={input}
          type="password"
          name="password"
          autocomplete="current-password"
          required
          value={password()}
          onInput={(e) => setPassword(e.currentTarget.value)}
        />
      </label>
      <Show when={error()}>
        <p role="alert" class="text-sm text-red-600">
          {error()}
        </p>
      </Show>
      <button
        type="submit"
        disabled={busy()}
        class="w-full rounded-md bg-red-600 px-3 py-2 font-medium text-white hover:bg-red-700 disabled:opacity-60"
      >
        {busy() ? 'Signing in…' : props.submitLabel}
      </button>
    </form>
  );
}
