import { createSignal } from 'solid-js';
import { useNavigate } from '@solidjs/router';
import { nextReaction, react } from '../api/social';
import { useAuth } from '../auth/AuthProvider';
import { formatCount } from '../util/format';

/** Like / dislike buttons with optimistic counts. */
export default function LikeBar(props: { publicId: string; likes: number; dislikes: number; mine: number | null | undefined }) {
  const auth = useAuth();
  const navigate = useNavigate();
  // seeded from props once; afterwards the server's answers are the source of truth
  const [mine, setMine] = createSignal(props.mine ?? 0);
  const [likes, setLikes] = createSignal(props.likes);
  const [dislikes, setDislikes] = createSignal(props.dislikes);

  const click = async (value: -1 | 1) => {
    if (!auth.user()) {
      navigate(`/login?returnTo=${encodeURIComponent(location.pathname)}`);
      return;
    }
    const before = { mine: mine(), likes: likes(), dislikes: dislikes() };
    const next = nextReaction(mine(), value);
    setLikes(likes() + (next === 1 ? 1 : 0) - (mine() === 1 ? 1 : 0));
    setDislikes(dislikes() + (next === -1 ? 1 : 0) - (mine() === -1 ? 1 : 0));
    setMine(next);
    try {
      const state = await react(props.publicId, next);
      setMine(state.myReaction);
      setLikes(state.likeCount);
      setDislikes(state.dislikeCount);
    } catch {
      setMine(before.mine);
      setLikes(before.likes);
      setDislikes(before.dislikes);
    }
  };

  const base = 'flex items-center gap-1.5 px-4 py-2 text-sm font-medium hover:bg-neutral-300 dark:hover:bg-neutral-700';
  return (
    <div class="flex overflow-hidden rounded-full bg-neutral-200 dark:bg-neutral-800">
      <button class={`${base} border-r border-neutral-300 dark:border-neutral-700`} aria-pressed={mine() === 1} onClick={() => click(1)}>
        <span aria-hidden="true">{mine() === 1 ? '👍' : '👍🏻'}</span>
        <span class={mine() === 1 ? 'text-red-600' : ''}>{formatCount(likes())}</span>
        <span class="sr-only">likes</span>
      </button>
      <button class={base} aria-pressed={mine() === -1} aria-label="Dislike" onClick={() => click(-1)}>
        <span aria-hidden="true" class={mine() === -1 ? '' : 'opacity-60'}>
          👎
        </span>
        <span class={mine() === -1 ? 'text-red-600' : 'text-neutral-500'}>{formatCount(dislikes())}</span>
      </button>
    </div>
  );
}
