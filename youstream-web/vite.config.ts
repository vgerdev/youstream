/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import solid from 'vite-plugin-solid';
import tailwindcss from '@tailwindcss/vite';

const backend = 'http://localhost:8080';

export default defineConfig({
  // solid-refresh (HMR) cannot load under Vitest
  plugins: [solid({ hot: !process.env.VITEST }), tailwindcss()],
  server: {
    port: 3000,
    proxy: {
      '/api': backend,
      '/media': backend,
      '/thumbs': backend,
      '/avatars': backend,
    },
  },
  build: {
    outDir: 'dist',
    target: 'es2022',
    // hls.js (~580 kB) is its own chunk, loaded only by the player
    chunkSizeWarningLimit: 700,
  },
  test: {
    environment: 'happy-dom',
    passWithNoTests: true,
  },
  resolve: {
    conditions: ['development', 'browser'],
  },
});
