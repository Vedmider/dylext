package io.github.vedmider.dylext.adapter.in.mcp;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Keeps stdout reserved for JSON-RPC in stdio mode: everything else (banner, logs, stray prints) goes to stderr. */
public final class StdioGuard {

    private static volatile PrintStream protocol;

    private StdioGuard() {
    }

    public static void claimStdout() {
        PrintStream claimed = System.out;
        protocol = claimed;
        System.setOut(System.err);
    }

    public static PrintStream protocolOut() {
        PrintStream p = protocol;
        return p != null ? p
                : new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    }

    public static Map<String, Object> stdioDefaults() {
        return Map.of("spring.main.banner-mode", "off", "spring.main.web-application-type", "none");
    }

    static void reset() {
        protocol = null;
    }
}
