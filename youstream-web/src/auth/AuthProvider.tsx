import { createContext, createSignal, onMount, useContext, type Accessor, type ParentProps } from 'solid-js';
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
  const [user, setUser] = createSignal<User | null>(null);
  const [loading, setLoading] = createSignal(true);

  // restores the session from the refresh cookie on page load (also after "Sign in with Microsoft")
  onMount(async () => {
    try {
      const session = await client.refreshSession();
      setUser(session?.user ?? null);
    } finally {
      setLoading(false);
    }
  });

  const value: AuthContextValue = {
    user,
    loading,
    async loginWithLdap(username, password) {
      const session = await client.loginWithLdap(username, password);
      setUser(session.user);
      return session.user;
    },
    async logout() {
      await client.logout();
      setUser(null);
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
