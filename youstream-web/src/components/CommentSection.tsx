import { createSignal, For, Show } from 'solid-js';
import { A } from '@solidjs/router';
import { useInfiniteQuery, useQueryClient } from '@tanstack/solid-query';
import {
  deleteComment,
  editComment,
  listComments,
  listReplies,
  postComment,
  type Comment,
} from '../api/social';
import { useAuth } from '../auth/AuthProvider';
import { countOf, timeAgo } from '../util/format';
import Avatar from './Avatar';

const textarea =
  'w-full rounded-md border border-neutral-300 dark:border-neutral-700 bg-transparent px-3 py-2 text-sm ' +
  'focus:outline-none focus:ring-2 focus:ring-red-600';

function CommentForm(props: { placeholder: string; initial?: string; submitLabel: string; onSubmit: (body: string) => Promise<unknown>; onCancel?: () => void }) {
  const [body, setBody] = createSignal(props.initial ?? '');
  const [busy, setBusy] = createSignal(false);
  const [error, setError] = createSignal<string | null>(null);

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    if (!body().trim()) return;
    setBusy(true);
    setError(null);
    try {
      await props.onSubmit(body().trim());
      setBody('');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not post the comment');
    } finally {
      setBusy(false);
    }
  };

  return (
    <form class="space-y-2" onSubmit={submit}>
      <textarea
        class={`${textarea} min-h-16`}
        placeholder={props.placeholder}
        maxLength={10000}
        value={body()}
        onInput={(e) => setBody(e.currentTarget.value)}
      />
      <Show when={error()}>
        <p role="alert" class="text-sm text-red-600">
          {error()}
        </p>
      </Show>
      <div class="flex justify-end gap-2 text-sm">
        <Show when={props.onCancel}>
          <button type="button" class="rounded-full px-3 py-1.5 hover:bg-neutral-200 dark:hover:bg-neutral-800" onClick={props.onCancel}>
            Cancel
          </button>
        </Show>
        <button
          type="submit"
          disabled={busy() || !body().trim()}
          class="rounded-full bg-red-600 px-4 py-1.5 font-medium text-white hover:bg-red-700 disabled:opacity-50"
        >
          {props.submitLabel}
        </button>
      </div>
    </form>
  );
}

function CommentItem(props: {
  publicId: string;
  comment: Comment;
  onChanged: () => void;
  isReply?: boolean;
  /** whether the reply thread is open; kept by the parent so it survives list refreshes */
  expanded?: boolean;
  onExpand?: (open: boolean) => void;
}) {
  const auth = useAuth();
  const c = () => props.comment;
  const [editing, setEditing] = createSignal(false);
  const [replying, setReplying] = createSignal(false);
  const showReplies = () => props.expanded ?? false;
  const setShowReplies = (open: boolean) => props.onExpand?.(open);

  const replies = useInfiniteQuery(() => ({
    queryKey: ['replies', props.publicId, c().id],
    queryFn: ({ pageParam }) => listReplies(props.publicId, c().id, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
    enabled: showReplies(),
  }));
  const replyList = () => replies.data?.pages.flatMap((p) => p.items) ?? [];
  const replyCount = () => c().replyCount;

  return (
    <li class="flex gap-3">
      <Avatar name={c().author?.displayName} size={props.isReply ? 'sm' : 'md'} />
      <div class="min-w-0 flex-1 space-y-1">
        <Show
          when={!c().deleted}
          fallback={<p class="text-sm italic text-neutral-500">This comment was deleted.</p>}
        >
          <p class="text-sm">
            <A href={`/c/${c().author?.handle}`} class="font-semibold hover:underline">
              @{c().author?.handle}
            </A>{' '}
            <span class="text-neutral-500">
              {timeAgo(c().createdAt)}
              {c().editedAt ? ' (edited)' : ''}
            </span>
          </p>
          <Show
            when={editing()}
            fallback={<p class="whitespace-pre-wrap break-words text-sm">{c().body}</p>}
          >
            <CommentForm
              placeholder="Edit your comment"
              initial={c().body ?? ''}
              submitLabel="Save"
              onCancel={() => setEditing(false)}
              onSubmit={async (body) => {
                await editComment(c().id, body);
                setEditing(false);
                props.onChanged();
              }}
            />
          </Show>
          <div class="flex gap-3 text-xs font-medium text-neutral-500">
            <Show when={auth.user() && !props.isReply}>
              <button class="hover:text-neutral-900 dark:hover:text-white" onClick={() => setReplying(!replying())}>
                Reply
              </button>
            </Show>
            <Show when={c().canEdit && !editing()}>
              <button class="hover:text-neutral-900 dark:hover:text-white" onClick={() => setEditing(true)}>
                Edit
              </button>
            </Show>
            <Show when={c().canDelete}>
              <button
                class="hover:text-red-600"
                onClick={async () => {
                  if (!confirm('Delete this comment?')) return;
                  await deleteComment(c().id);
                  props.onChanged();
                }}
              >
                Delete
              </button>
            </Show>
          </div>
        </Show>

        <Show when={replying()}>
          <CommentForm
            placeholder={`Reply to @${c().author?.handle}`}
            submitLabel="Reply"
            onCancel={() => setReplying(false)}
            onSubmit={async (body) => {
              await postComment(props.publicId, body, c().id);
              setReplying(false);
              setShowReplies(true);
              await replies.refetch();
              props.onChanged();
            }}
          />
        </Show>

        <Show when={!props.isReply && replyCount() > 0}>
          <button class="text-sm font-medium text-red-600 hover:underline" onClick={() => setShowReplies(!showReplies())}>
            {showReplies() ? 'Hide replies' : `${replyCount()} ${replyCount() === 1 ? 'reply' : 'replies'}`}
          </button>
          <Show when={showReplies()}>
            <ul class="mt-2 space-y-3">
              <For each={replyList()}>
                {(r) => (
                  <CommentItem
                    publicId={props.publicId}
                    comment={r}
                    isReply
                    onChanged={() => {
                      void replies.refetch();
                      props.onChanged();
                    }}
                  />
                )}
              </For>
            </ul>
            <Show when={replies.hasNextPage}>
              <button class="text-sm text-neutral-500 hover:underline" onClick={() => replies.fetchNextPage()}>
                More replies
              </button>
            </Show>
          </Show>
        </Show>
      </div>
    </li>
  );
}

export default function CommentSection(props: { publicId: string; count: number }) {
  const auth = useAuth();
  const queryClient = useQueryClient();
  const [expanded, setExpanded] = createSignal<ReadonlySet<number>>(new Set());
  const expand = (id: number, open: boolean) => {
    const next = new Set(expanded());
    if (open) next.add(id);
    else next.delete(id);
    setExpanded(next);
  };
  const comments = useInfiniteQuery(() => ({
    queryKey: ['comments', props.publicId],
    queryFn: ({ pageParam }) => listComments(props.publicId, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  }));
  const list = () => comments.data?.pages.flatMap((p) => p.items) ?? [];
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['comments', props.publicId] });
    void queryClient.invalidateQueries({ queryKey: ['video', props.publicId] });
  };

  return (
    <section class="space-y-4">
      <h2 class="text-lg font-bold">
        {countOf(props.count, 'Comment')}
      </h2>

      <Show
        when={auth.user()}
        fallback={
          <p class="text-sm text-neutral-500">
            <A href={`/login?returnTo=${encodeURIComponent(`/watch/${props.publicId}`)}`} class="text-red-600 hover:underline">
              Sign in
            </A>{' '}
            to comment.
          </p>
        }
      >
        {(user) => (
          <div class="flex gap-3">
            <Avatar name={user().displayName} />
            <div class="flex-1">
              <CommentForm
                placeholder="Add a comment…"
                submitLabel="Comment"
                onSubmit={async (body) => {
                  await postComment(props.publicId, body);
                  refresh();
                }}
              />
            </div>
          </div>
        )}
      </Show>

      <ul class="space-y-5">
        <For each={list()}>
          {(c) => (
            <CommentItem
              publicId={props.publicId}
              comment={c}
              onChanged={refresh}
              expanded={expanded().has(c.id)}
              onExpand={(open) => expand(c.id, open)}
            />
          )}
        </For>
      </ul>
      <Show when={comments.hasNextPage}>
        <button
          class="rounded-full border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm font-medium"
          disabled={comments.isFetchingNextPage}
          onClick={() => comments.fetchNextPage()}
        >
          {comments.isFetchingNextPage ? 'Loading…' : 'More comments'}
        </button>
      </Show>
    </section>
  );
}
