import type { JSX } from 'solid-js';

interface Props {
  label: string;
  href?: string;
  onClick?: JSX.EventHandler<HTMLAnchorElement, MouseEvent>;
}

/** "Sign in with Microsoft" button following Microsoft's branding (four-square logo, neutral button). */
export default function MicrosoftButton(props: Props) {
  return (
    <a
      href={props.href ?? '#'}
      // the sign-in URL is a server route (/api/...): stop the SPA router from handling the click client-side
      rel="external"
      onClick={props.onClick}
      class="flex w-full items-center justify-center gap-3 rounded-md border border-neutral-300 dark:border-neutral-700 bg-white dark:bg-neutral-900 px-3 py-2 font-medium hover:bg-neutral-50 dark:hover:bg-neutral-800"
    >
      <svg width="20" height="20" viewBox="0 0 21 21" aria-hidden="true">
        <rect x="1" y="1" width="9" height="9" fill="#f25022" />
        <rect x="11" y="1" width="9" height="9" fill="#7fba00" />
        <rect x="1" y="11" width="9" height="9" fill="#00a4ef" />
        <rect x="11" y="11" width="9" height="9" fill="#ffb900" />
      </svg>
      {props.label}
    </a>
  );
}
