import { createSignal, Show } from 'solid-js';
import { useNavigate } from '@solidjs/router';
import { useQueryClient } from '@tanstack/solid-query';
import { subscribe, unsubscribe } from '../api/social';
import { useAuth } from '../auth/AuthProvider';

/**
 * Subscribe/Subscribed toggle. Anonymous users are sent to sign in; your own channel shows nothing.
 * Reports the new subscriber count through {@code onChange}.
 */
export default function SubscribeButton(props: {
  handle: string;
  subscribed: boolean | null | undefined;
  mine?: boolean;
  onChange?: (subscribed: boolean, subscriberCount: number) => void;
}) {
  const auth = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [busy, setBusy] = createSignal(false);

  const toggle = async () => {
    if (!auth.user()) {
      navigate(`/login?returnTo=${encodeURIComponent(location.pathname)}`);
      return;
    }
    setBusy(true);
    try {
      const result = props.subscribed ? await unsubscribe(props.handle) : await subscribe(props.handle);
      props.onChange?.(result.subscribed, result.subscriberCount);
      void queryClient.invalidateQueries({ queryKey: ['subscriptions'] });
      void queryClient.invalidateQueries({ queryKey: ['subscription-feed'] });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Show when={!props.mine}>
      <button
        onClick={toggle}
        disabled={busy()}
        class={`rounded-full px-4 py-2 text-sm font-medium disabled:opacity-60 ${
          props.subscribed
            ? 'bg-neutral-200 dark:bg-neutral-800 hover:bg-neutral-300 dark:hover:bg-neutral-700'
            : 'bg-neutral-900 text-white dark:bg-white dark:text-neutral-900 hover:opacity-90'
        }`}
      >
        {props.subscribed ? 'Subscribed' : 'Subscribe'}
      </button>
    </Show>
  );
}
