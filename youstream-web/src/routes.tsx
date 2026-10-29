import { lazy } from 'solid-js';
import type { RouteDefinition } from '@solidjs/router';

export const routes: RouteDefinition[] = [
  { path: '/', component: lazy(() => import('./pages/Home')) },
  { path: '*404', component: lazy(() => import('./pages/NotFound')) },
];
