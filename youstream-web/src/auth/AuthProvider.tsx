import { createContext, createSignal, onMount, useContext, type Accessor, type ParentProps } from 'solid-js';
import { useQueryClient } from '@tanstack/solid-query';
import * as client from '../api/client';
import type { User } from '../api/client';

interface AuthContextValue {
  user: Accessor<User | null>;
  /** True until the initial silent refresh has finished. */
  loading: Accessor<boolean>;
  loginWithLdap(username: string, password: string): Promise<User>;
  logout(): Promise<void>;
  setUser(user: User | null): void;
}

const AuthContext = createContext<AuthContextValue>();

export function AuthProvider(props: ParentProps) {
  const queryClient = useQueryClient();
  const [user, setUser] = createSignal<User | null>(null);
  const [loading, setLoading] = createSignal(true);

  // restores the session from the refresh cookie on page load (also after "Sign in with Microsoft")
  onMount(async () => {
    try {
      const session = await client.restoreSession();
      setUser(session?.user ?? null);
    } finally {
      setLoading(false);
    }
  });

  /** Cached responses contain per-user fields (my reaction, subscribed, canEdit), so refetch when the user changes. */
  const switchUser = (next: User | null) => {
    setUser(next);
    void queryClient.invalidateQueries();
  };

  const value: AuthContextValue = {
    user,
    loading,
    async loginWithLdap(username, password) {
      const session = await client.loginWithLdap(username, password);
      switchUser(session.user);
      return session.user;
    },
    async logout() {
      await client.logout();
      switchUser(null);
    },
    setUser,
  };

  return <AuthContext.Provider value={value}>{props.children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
