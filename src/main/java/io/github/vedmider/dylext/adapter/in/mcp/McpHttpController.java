package io.github.vedmider.dylext.adapter.in.mcp;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** JSON-RPC over HTTP for clients that cannot spawn a stdio MCP server; same dispatcher as stdio. */
@RestController
public class McpHttpController {

    private final McpDispatcher dispatcher;

    public McpHttpController(McpDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping(path = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(@RequestBody String request) {
        String response = dispatcher.handle(request);
        return response == null ? ResponseEntity.accepted().build()
                : ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }
}
