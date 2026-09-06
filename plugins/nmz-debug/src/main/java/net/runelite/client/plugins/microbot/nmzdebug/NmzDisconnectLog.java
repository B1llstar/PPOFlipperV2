package net.runelite.client.plugins.microbot.nmzdebug;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import javax.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Keeps a rolling buffer of the most recent [NMZDEBUG] lines in memory, and on a client
 * disconnect (GameState.CONNECTION_LOST or a LOGIN_SCREEN transition while the plugin thinks
 * it should still be logged in) dumps that buffer to a dedicated file on disk at
 * {@code ~/.runelite/nmzdebug-disconnect.log}. Console output alone is lost once the client
 * restarts or the console scrolls past it, so this is the only durable record of exactly what
 * the script was doing in the moments leading up to a disconnect.
 */
@Slf4j
@Singleton
public class NmzDisconnectLog {

    private static final Path LOG_PATH = RuneLite.RUNELITE_DIR.toPath().resolve("nmzdebug-disconnect.log");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    // Enough lines to cover several ticks of context (each tick logs ~5-20 lines) without the
    // buffer itself becoming a memory concern - it's just recent strings, dropped once full.
    private static final int MAX_BUFFERED_LINES = 2000;

    private final Deque<String> buffer = new ArrayDeque<>(MAX_BUFFERED_LINES);
    private volatile boolean warnedAboutFailure = false;

    /** Records one line into the rolling buffer. Does not touch disk. */
    public synchronized void record(String line) {
        String stamped = "[" + TIMESTAMP_FORMAT.format(Instant.now()) + "] " + line;
        if (buffer.size() >= MAX_BUFFERED_LINES) {
            buffer.removeFirst();
        }
        buffer.addLast(stamped);
    }

    /**
     * Flushes the current buffer to disk as one dated entry, prefixed with the given reason
     * (e.g. "CONNECTION_LOST" or "LOGIN_SCREEN while expected to be logged in"). Best-effort:
     * a failure to write is logged once via SLF4J and otherwise swallowed, since a diagnostics
     * dump must never itself crash the script.
     */
    public synchronized void dumpToDisk(String reason) {
        List<String> snapshot = new ArrayList<>(buffer);
        StringBuilder sb = new StringBuilder();
        sb.append("===== disconnect detected: ").append(reason)
                .append(" at ").append(TIMESTAMP_FORMAT.format(Instant.now())).append(" =====")
                .append(System.lineSeparator());
        if (snapshot.isEmpty()) {
            sb.append("(no buffered lines captured before this event)").append(System.lineSeparator());
        } else {
            for (String line : snapshot) {
                sb.append(line).append(System.lineSeparator());
            }
        }
        sb.append("===== end of dump (" + snapshot.size() + " lines) =====").append(System.lineSeparator());
        sb.append(System.lineSeparator());

        try {
            Files.write(LOG_PATH, sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("[NMZDEBUG] disconnect dump written to " + LOG_PATH);
        } catch (IOException e) {
            if (!warnedAboutFailure) {
                warnedAboutFailure = true;
                log.warn("NmzDebug: failed to write disconnect log at {} - {}", LOG_PATH, e.getMessage());
            }
        }
    }
}
