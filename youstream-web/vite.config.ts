/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import solid from 'vite-plugin-solid';
import tailwindcss from '@tailwindcss/vite';

const backend = 'http://localhost:8080';

export default defineConfig({
  plugins: [solid(), tailwindcss()],
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
  },
  test: {
    environment: 'happy-dom',
    passWithNoTests: true,
  },
  resolve: {
    conditions: ['development', 'browser'],
  },
});
