import { lazy, type Component } from 'solid-js';
import type { RouteDefinition } from '@solidjs/router';
import RequireAuth from './auth/RequireAuth';

/** Lazy page that redirects anonymous users to /login. */
const signedIn = (load: () => Promise<{ default: Component }>) => {
  const Page = lazy(load);
  return () => (
    <RequireAuth>
      <Page />
    </RequireAuth>
  );
};

export const routes: RouteDefinition[] = [
  { path: '/', component: lazy(() => import('./pages/Home')) },
  { path: '/login', component: lazy(() => import('./pages/Login')) },
  { path: '/watch/:id', component: lazy(() => import('./pages/Watch')) },
  { path: '/c/:handle', component: lazy(() => import('./pages/Channel')) },
  { path: '/search', component: lazy(() => import('./pages/Search')) },
  { path: '/playlist/:id', component: lazy(() => import('./pages/PlaylistPage')) },
  { path: '/playlists', component: signedIn(() => import('./pages/Playlists')) },
  { path: '/embed/:id', component: lazy(() => import('./pages/Embed')) },
  { path: '/subscriptions', component: signedIn(() => import('./pages/Subscriptions')) },
  { path: '/upload', component: signedIn(() => import('./pages/Upload')) },
  { path: '/studio', component: signedIn(() => import('./pages/Studio')) },
  { path: '/account', component: signedIn(() => import('./pages/Account')) },
  { path: '*404', component: lazy(() => import('./pages/NotFound')) },
];
