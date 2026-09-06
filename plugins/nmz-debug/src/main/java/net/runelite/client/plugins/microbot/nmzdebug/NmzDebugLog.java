package net.runelite.client.plugins.microbot.nmzdebug;

/**
 * Drop-in replacement for {@code System.out.println("[NMZDEBUG] ...")} that also feeds every
 * line into the shared {@link NmzDisconnectLog} ring buffer, so whatever the script was doing
 * right before a disconnect can be dumped to disk. Static/global because the script's tick loop
 * logs from dozens of call sites and plumbing a buffer instance through all of them isn't worth
 * it - there is only ever one NMZ debug session running at a time.
 */
final class NmzDebugLog {

    private static volatile NmzDisconnectLog disconnectLog;

    private NmzDebugLog() {
    }

    static void init(NmzDisconnectLog log) {
        disconnectLog = log;
    }

    static void log(String line) {
        System.out.println(line);
        NmzDisconnectLog current = disconnectLog;
        if (current != null) {
            current.record(line);
        }
    }
}
