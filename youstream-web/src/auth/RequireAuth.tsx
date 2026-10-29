import { Match, Switch, type ParentProps } from 'solid-js';
import { Navigate, useLocation } from '@solidjs/router';
import { useAuth } from './AuthProvider';

/** Renders children for signed-in users; otherwise sends them to /login and back afterwards. */
export default function RequireAuth(props: ParentProps) {
  const auth = useAuth();
  const location = useLocation();
  const returnTo = () => encodeURIComponent(location.pathname + location.search);

  return (
    <Switch>
      <Match when={auth.loading()}>
        <p class="text-center text-neutral-500 mt-16">Loading…</p>
      </Match>
      <Match when={!auth.user()}>
        <Navigate href={`/login?returnTo=${returnTo()}`} />
      </Match>
      <Match when={auth.user()}>{props.children}</Match>
    </Switch>
  );
}
