import { A } from '@solidjs/router';

export default function NotFound() {
  return (
    <section class="max-w-2xl mx-auto mt-16 text-center space-y-4">
      <h1 class="text-2xl font-bold">Page not found</h1>
      <A href="/" class="text-red-600 hover:underline">
        Back to home
      </A>
    </section>
  );
}
