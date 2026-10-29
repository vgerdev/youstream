import { render } from 'solid-js/web';
import { Router } from '@solidjs/router';
import { QueryClient, QueryClientProvider } from '@tanstack/solid-query';
import { routes } from './routes';
import AppShell from './layouts/AppShell';
import './index.css';

const queryClient = new QueryClient();

render(
  () => (
    <QueryClientProvider client={queryClient}>
      <Router root={AppShell}>{routes}</Router>
    </QueryClientProvider>
  ),
  document.getElementById('root')!,
);
