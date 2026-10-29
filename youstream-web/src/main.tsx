import { render } from 'solid-js/web';
import { Router } from '@solidjs/router';
import { QueryClient, QueryClientProvider } from '@tanstack/solid-query';
import { routes } from './routes';
import AppShell from './layouts/AppShell';
import { AuthProvider } from './auth/AuthProvider';
import { restoreSession } from './api/client';
import './index.css';

const queryClient = new QueryClient();
// before anything renders: pages' first requests then carry the restored access token
void restoreSession();

render(
  () => (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <Router root={AppShell}>{routes}</Router>
      </AuthProvider>
    </QueryClientProvider>
  ),
  document.getElementById('root')!,
);
