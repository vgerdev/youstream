import { createSignal, For, Show } from 'solid-js';
import { useMutation, useQuery, useQueryClient } from '@tanstack/solid-query';
import { addSshKey, deleteSshKey, getSftpInfo, listSshKeys } from '../api/sshKeys';
import { formatBytes, timeAgo } from '../util/format';

function CopyLine(props: { text: string }) {
  const [copied, setCopied] = createSignal(false);
  return (
    <div class="flex items-center gap-2 rounded-md bg-neutral-100 dark:bg-neutral-900 px-3 py-2 font-mono text-sm">
      <code class="flex-1 overflow-x-auto whitespace-nowrap">{props.text}</code>
      <button
        type="button"
        class="shrink-0 text-xs text-neutral-500 hover:text-neutral-900 dark:hover:text-white"
        onClick={async () => {
          await navigator.clipboard.writeText(props.text);
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        }}
      >
        {copied() ? 'Copied' : 'Copy'}
      </button>
    </div>
  );
}

/** Account page section: how to upload over SFTP, and the user's SSH keys. */
export default function SftpSection() {
  const queryClient = useQueryClient();
  const info = useQuery(() => ({ queryKey: ['sftp-info'], queryFn: getSftpInfo }));
  const keys = useQuery(() => ({ queryKey: ['ssh-keys'], queryFn: listSshKeys }));
  const [publicKey, setPublicKey] = createSignal('');
  const [name, setName] = createSignal('');

  const add = useMutation(() => ({
    mutationFn: () => addSshKey(publicKey().trim(), name().trim()),
    onSuccess: () => {
      setPublicKey('');
      setName('');
      return queryClient.invalidateQueries({ queryKey: ['ssh-keys'] });
    },
  }));
  const remove = useMutation(() => ({
    mutationFn: deleteSshKey,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ssh-keys'] }),
  }));

  const input =
    'w-full rounded-md border border-neutral-300 dark:border-neutral-700 bg-transparent px-3 py-2 ' +
    'focus:outline-none focus:ring-2 focus:ring-red-600';

  return (
    <Show when={info.data?.enabled ? info.data : undefined}>
      {(sftp) => (
        <section class="space-y-4">
          <h2 class="text-lg font-semibold">Upload over SFTP</h2>
          <p class="text-sm text-neutral-500">
            Upload large files or whole folders with any SFTP client (FileZilla, WinSCP, Cyberduck, <code>sftp</code>).
            Each finished file becomes a <strong>private</strong> video you can publish from Studio. Files up to{' '}
            {formatBytes(sftp().maxFileBytes)}.
          </p>

          <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            <dt class="text-neutral-500">Host</dt>
            <dd class="font-mono">{sftp().host}</dd>
            <dt class="text-neutral-500">Port</dt>
            <dd class="font-mono">{sftp().port}</dd>
            <dt class="text-neutral-500">Username</dt>
            <dd class="font-mono">{sftp().username}</dd>
            <dt class="text-neutral-500">Sign in with</dt>
            <dd>
              {sftp().passwordLogin ? 'your directory password or an SSH key below' : 'an SSH key below'}
            </dd>
            <Show when={sftp().hostKeyFingerprint}>
              <dt class="text-neutral-500">Host key</dt>
              <dd class="font-mono break-all">{sftp().hostKeyFingerprint}</dd>
            </Show>
          </dl>

          <div class="space-y-2">
            <p class="text-sm font-medium">From a terminal</p>
            <CopyLine text={`sftp ${sftp().port === 22 ? '' : `-P ${sftp().port} `}${sftp().username}@${sftp().host}`} />
            <CopyLine text="put my-video.mp4" />
            <p class="text-xs text-neutral-500">
              Check the host key fingerprint above when your client asks for the first time. After each upload a{' '}
              <code>my-video.mp4.status</code> file tells you whether it was accepted. To set the title, description or
              visibility, upload <code>my-video.json</code> first, e.g.{' '}
              <code>{'{"title": "Our trip", "visibility": "UNLISTED"}'}</code>.
            </p>
          </div>

          <div class="space-y-3">
            <h3 class="font-medium">SSH keys</h3>
            <Show when={keys.data?.length} fallback={<p class="text-sm text-neutral-500">No keys yet.</p>}>
              <ul class="divide-y divide-neutral-200 dark:divide-neutral-800 text-sm">
                <For each={keys.data}>
                  {(k) => (
                    <li class="flex items-center gap-3 py-2">
                      <div class="min-w-0 flex-1">
                        <p class="font-medium">{k.name}</p>
                        <p class="truncate font-mono text-xs text-neutral-500">
                          {k.keyType} {k.fingerprint}
                        </p>
                        <p class="text-xs text-neutral-500">
                          added {timeAgo(k.createdAt)} · {k.lastUsedAt ? `last used ${timeAgo(k.lastUsedAt)}` : 'never used'}
                        </p>
                      </div>
                      <button
                        class="text-red-600 hover:underline"
                        onClick={() => {
                          if (confirm(`Remove the key "${k.name}"?`)) remove.mutate(k.id);
                        }}
                      >
                        Remove
                      </button>
                    </li>
                  )}
                </For>
              </ul>
            </Show>

            <form
              class="space-y-2"
              onSubmit={(e) => {
                e.preventDefault();
                add.mutate();
              }}
            >
              <textarea
                class={`${input} min-h-20 font-mono text-xs`}
                placeholder="ssh-ed25519 AAAA… you@computer   (contents of ~/.ssh/id_ed25519.pub)"
                required
                value={publicKey()}
                onInput={(e) => setPublicKey(e.currentTarget.value)}
              />
              <div class="flex gap-2">
                <input
                  class={input}
                  placeholder="Name (optional)"
                  maxLength={100}
                  value={name()}
                  onInput={(e) => setName(e.currentTarget.value)}
                />
                <button
                  type="submit"
                  disabled={add.isPending}
                  class="shrink-0 rounded-md bg-red-600 px-4 py-2 font-medium text-white hover:bg-red-700 disabled:opacity-60"
                >
                  Add key
                </button>
              </div>
              <Show when={add.error}>
                <p role="alert" class="text-sm text-red-600">
                  {add.error?.message}
                </p>
              </Show>
              <p class="text-xs text-neutral-500">
                No key yet? Run <code>ssh-keygen -t ed25519</code> and paste the contents of the <code>.pub</code> file.
              </p>
            </form>
          </div>
        </section>
      )}
    </Show>
  );
}
