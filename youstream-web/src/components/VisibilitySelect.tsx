import type { Visibility } from '../api/videos';

export const VISIBILITY_LABELS: Record<Visibility, string> = {
  PUBLIC: 'Public',
  UNLISTED: 'Unlisted',
  PRIVATE: 'Private',
};

export const VISIBILITY_HINTS: Record<Visibility, string> = {
  PUBLIC: 'Everyone can find and watch it',
  UNLISTED: 'Anyone with the link can watch it',
  PRIVATE: 'Only you can watch it',
};

export default function VisibilitySelect(props: {
  value: Visibility;
  onChange: (v: Visibility) => void;
  disabled?: boolean;
  class?: string;
}) {
  return (
    <select
      class={`rounded-md border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 ${props.class ?? ''}`}
      value={props.value}
      disabled={props.disabled}
      onChange={(e) => props.onChange(e.currentTarget.value as Visibility)}
    >
      {(Object.keys(VISIBILITY_LABELS) as Visibility[]).map((v) => (
        <option value={v} title={VISIBILITY_HINTS[v]}>
          {VISIBILITY_LABELS[v]}
        </option>
      ))}
    </select>
  );
}
