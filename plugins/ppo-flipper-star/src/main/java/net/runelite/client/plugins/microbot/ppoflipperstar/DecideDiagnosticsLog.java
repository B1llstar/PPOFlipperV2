package net.runelite.client.plugins.microbot.ppoflipperstar;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import javax.inject.Singleton;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * A dedicated, human-readable, append-only log of DECIDE-tick health - deliberately separate from
 * RuneLite's own {@code client.log}, which mixes this plugin's output with every other plugin and
 * script running at the same time (dozens of unrelated log lines per second during normal play),
 * making "what actually went wrong with DECIDE" a slow, manual grep-and-guess exercise across a
 * huge shared file. This writes ONLY DECIDE-pipeline outcomes - one line per tick - to
 * {@code ~/.runelite/ppoflipperstar-decide.log}, so the full picture of "is this working, and if
 * not, why" lives in one small, focused, easy-to-read place.
 *
 * <p>Deliberately NOT a replacement for the existing {@code @Slf4j} logging - those calls still go
 * to {@code client.log} as before (useful for correlating with other plugins/the game client
 * itself). This is purely additive, purely for this one specific pain point, and purely
 * observational - it never changes DECIDE-tick behavior, only records it.
 *
 * <p>Every write is best-effort: a failure to open/write the file is logged once (via the normal
 * SLF4J logger, so it's at least visible in client.log) and otherwise silently ignored - this
 * diagnostic aid must never itself become a reason DECIDE ticks fail or slow down.
 */
@Slf4j
@Singleton
public class DecideDiagnosticsLog {

    private static final Path LOG_PATH = RuneLite.RUNELITE_DIR.toPath().resolve("ppoflipperstar-decide.log");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // Simple size-based rollover so this file never grows unboundedly over a long session - once
    // it exceeds this size, it's truncated back to just its most recent tail rather than deleted
    // outright, so there's always SOME recent history immediately after a rollover.
    private static final long MAX_SIZE_BYTES = 5 * 1024 * 1024;
    private static final long TRIM_TO_BYTES = 2 * 1024 * 1024;

    private volatile boolean warnedAboutFailure = false;

    /** One line summarizing a completed DECIDE tick - the primary record this file exists for. */
    public synchronized void logTick(long tickId, long durationMillis, int watchlistSize, int itemsSent,
                                      int suggestionCount, int guardrailWithheldCount, String outcome) {
        String line = String.format(
            "[%s] tick=%d outcome=%s duration=%dms watchlist=%d sent=%d suggestions=%d guardrail_withheld=%d",
            TIMESTAMP_FORMAT.format(Instant.now()), tickId, outcome, durationMillis, watchlistSize, itemsSent,
            suggestionCount, guardrailWithheldCount);
        write(line);
    }

    /** Free-text line for anything else worth a human noticing - kept generic rather than adding a new typed method for every possible event this pipeline could ever log. */
    public synchronized void logNote(String note) {
        write(String.format("[%s] %s", TIMESTAMP_FORMAT.format(Instant.now()), note));
    }

    private void write(String line) {
        try {
            maybeRollOver();
            Files.write(LOG_PATH, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (!warnedAboutFailure) {
                warnedAboutFailure = true;
                log.warn("PPOFlipperStar: failed to write DECIDE diagnostics log at {} - {}", LOG_PATH, e.getMessage());
            }
        }
    }

    private void maybeRollOver() throws IOException {
        if (!Files.exists(LOG_PATH) || Files.size(LOG_PATH) < MAX_SIZE_BYTES) {
            return;
        }
        byte[] all = Files.readAllBytes(LOG_PATH);
        int start = Math.max(0, all.length - (int) TRIM_TO_BYTES);
        // Avoid starting mid-line - trim forward to the next newline so the kept tail starts cleanly.
        while (start < all.length && all[start] != '\n') {
            start++;
        }
        byte[] tail = java.util.Arrays.copyOfRange(all, Math.min(start + 1, all.length), all.length);
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(LOG_PATH, StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
            writer.println("--- log rolled over, older entries trimmed ---");
            writer.write(new String(tail, StandardCharsets.UTF_8));
        }
    }
}
