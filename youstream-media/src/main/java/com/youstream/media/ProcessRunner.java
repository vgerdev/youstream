package com.youstream.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs external tools with an argument list (never a shell), a timeout, and line-by-line stdout handling.
 * Running processes can be killed all at once on shutdown.
 */
public class ProcessRunner {

    public record Result(int exitCode, String stdout, String stderrTail) {
    }

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);
    private static final int STDERR_LINES = 40;

    private final Set<Process> running = ConcurrentHashMap.newKeySet();

    /** Runs to completion, collecting stdout. */
    public Result run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        StringBuilder out = new StringBuilder();
        return run(command, timeout, line -> out.append(line).append('\n'), out);
    }

    /** Runs to completion, passing each stdout line to {@code onLine}. */
    public Result run(List<String> command, Duration timeout, Consumer<String> onLine) throws IOException, InterruptedException {
        return run(command, timeout, onLine, null);
    }

    private Result run(List<String> command, Duration timeout, Consumer<String> onLine, StringBuilder collected)
        throws IOException, InterruptedException {
        log.debug("exec {}", command);
        Process process = new ProcessBuilder(command).directory(Path.of(".").toFile()).start();
        running.add(process);
        Deque<String> stderr = new ArrayDeque<>();
        Thread errReader = Thread.ofVirtual().start(() -> drain(process, stderr));
        try {
            process.getOutputStream().close();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onLine.accept(line);
                }
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException(command.getFirst() + " timed out after " + timeout);
            }
            errReader.join(5_000);
            String tail;
            synchronized (stderr) {
                tail = String.join("\n", stderr);
            }
            return new Result(process.exitValue(), collected == null ? "" : collected.toString(), tail);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        } finally {
            running.remove(process);
        }
    }

    /** Kills every process started by this runner (shutdown). */
    public void killAll() {
        running.forEach(Process::destroyForcibly);
    }

    private static void drain(Process process, Deque<String> tail) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (tail) {
                    tail.addLast(line);
                    if (tail.size() > STDERR_LINES) {
                        tail.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // process killed
        }
    }
}
