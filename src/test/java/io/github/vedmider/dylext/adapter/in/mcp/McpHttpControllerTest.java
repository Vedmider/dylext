package io.github.vedmider.dylext.adapter.in.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class McpHttpControllerTest {

    private final McpDispatcher dispatcher = mock(McpDispatcher.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpHttpController(dispatcher)).build();

    @Test
    void postedJsonRpcIsAnsweredByTheSameDispatcherAsStdio() throws Exception {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        when(dispatcher.handle(req)).thenReturn("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");

        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
    }

    @Test
    void notificationsGetAnEmpty202() throws Exception {
        String note = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        when(dispatcher.handle(note)).thenReturn(null);

        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(note))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }
}
