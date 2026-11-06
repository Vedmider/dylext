package com.example.contextmcp.adapter.in.mcp;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Reads newline-delimited JSON-RPC requests from stdin and writes responses to stdout. */
@Component
@ConditionalOnProperty(name = "contextmcp.mcp.stdio", havingValue = "true")
public class McpStdioRunner implements ApplicationRunner {

    private final McpDispatcher dispatcher;

    public McpStdioRunner(McpDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String response = dispatcher.handle(line);
                if (response != null) {
                    out.println(response);
                }
            }
        }
    }
}
