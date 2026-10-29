import { render } from 'solid-js/web';
import { Router } from '@solidjs/router';
import { QueryClient, QueryClientProvider } from '@tanstack/solid-query';
import { routes } from './routes';
import AppShell from './layouts/AppShell';
import { AuthProvider } from './auth/AuthProvider';
import './index.css';

const queryClient = new QueryClient();

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
