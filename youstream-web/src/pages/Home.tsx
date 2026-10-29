import { Show } from 'solid-js';
import { useQuery } from '@tanstack/solid-query';
import { getHealth } from '../api/client';
import { useAuth } from '../auth/AuthProvider';

export default function Home() {
  const auth = useAuth();
  const health = useQuery(() => ({ queryKey: ['health'], queryFn: getHealth }));

  return (
    <section class="max-w-2xl mx-auto mt-16 text-center space-y-4">
      <h1 class="text-3xl font-bold">
        <Show when={auth.user()} fallback="Hello from YouStream">
          {(u) => <>Welcome, {u().displayName}</>}
        </Show>
      </h1>
      <p class="text-neutral-500">
        Backend:{' '}
        <Show when={health.data} fallback={<span>{health.isError ? 'unreachable' : 'checking…'}</span>}>
          {(h) => <span class="font-mono text-green-600">{h().status}</span>}
        </Show>
      </p>
    </section>
  );
}
