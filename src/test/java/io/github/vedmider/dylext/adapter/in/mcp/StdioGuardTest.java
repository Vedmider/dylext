package io.github.vedmider.dylext.adapter.in.mcp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class StdioGuardTest {

    @Test
    void stdoutCarriesOnlyProtocolBytesOnceClaimedAndStrayPrintsGoToStderr() {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream protocol = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(protocol, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));

            StdioGuard.claimStdout();
            System.out.println("banner or log noise");
            StdioGuard.protocolOut().println("{\"jsonrpc\":\"2.0\"}");

            assertThat(protocol.toString(StandardCharsets.UTF_8)).isEqualTo("{\"jsonrpc\":\"2.0\"}" + System.lineSeparator());
            assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("banner or log noise");
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
            StdioGuard.reset();
        }
    }

    @Test
    void stdioModeDefaultsKeepTheBannerOffAndAvoidBindingAPort() {
        assertThat(StdioGuard.stdioDefaults())
                .containsEntry("spring.main.banner-mode", "off")
                .containsEntry("spring.main.web-application-type", "none");
    }
}
