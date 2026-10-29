import type { ParentProps } from 'solid-js';
import { A } from '@solidjs/router';

export default function AppShell(props: ParentProps) {
  return (
    <div class="min-h-screen flex flex-col">
      <header class="h-14 px-4 flex items-center gap-4 border-b border-neutral-200 dark:border-neutral-800">
        <A href="/" class="flex items-center gap-1 font-bold text-lg">
          <span class="inline-block w-7 h-5 rounded bg-red-600 text-white text-xs leading-5 text-center">▶</span>
          YouStream
        </A>
      </header>
      <main class="flex-1 p-4">{props.children}</main>
    </div>
  );
}
