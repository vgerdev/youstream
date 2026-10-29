import { lazy } from 'solid-js';
import type { RouteDefinition } from '@solidjs/router';
import RequireAuth from './auth/RequireAuth';

const Account = lazy(() => import('./pages/Account'));

export const routes: RouteDefinition[] = [
  { path: '/', component: lazy(() => import('./pages/Home')) },
  { path: '/login', component: lazy(() => import('./pages/Login')) },
  {
    path: '/account',
    component: () => (
      <RequireAuth>
        <Account />
      </RequireAuth>
    ),
  },
  { path: '*404', component: lazy(() => import('./pages/NotFound')) },
];
