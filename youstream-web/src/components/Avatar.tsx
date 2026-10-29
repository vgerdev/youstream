const SIZES = { sm: 'h-8 w-8 text-sm', md: 'h-10 w-10', lg: 'h-20 w-20 text-3xl' };

/** Initial-letter avatar (profile pictures come later). */
export default function Avatar(props: { name: string | undefined; size?: keyof typeof SIZES }) {
  return (
    <span
      aria-hidden="true"
      class={`grid shrink-0 place-items-center rounded-full bg-red-600 font-semibold text-white ${SIZES[props.size ?? 'md']}`}
    >
      {(props.name ?? '?').charAt(0).toUpperCase()}
    </span>
  );
}
