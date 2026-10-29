package im.phantom.web.phantom;

import com.sun.net.httpserver.HttpServer;
import im.phantom.web.auth.SessionAccount;
import im.phantom.web.auth.SessionSupport;
import im.phantom.web.config.WebImProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProxyControllerTest {
    @Test
    void forwardsAttachmentHeadersAndBytesWithoutForwardingUpstreamCookies() throws Exception {
        byte[] bytes = "{\"说明\":\"原始内容\"}".getBytes(StandardCharsets.UTF_8);
        String disposition = ContentDisposition.attachment().filename("配置.json", StandardCharsets.UTF_8)
                .build().toString();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/media/m1/content", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Content-Disposition", disposition);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Cache-Control", "private, max-age=60");
            exchange.getResponseHeaders().set("Set-Cookie", "upstream=private");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        upstream.start();
        try {
            SessionSupport sessions = mock(SessionSupport.class);
            when(sessions.require(any())).thenReturn(new SessionAccount("a1", "u1", "alice", "Alice", 0));
            WebImProperties properties = new WebImProperties("app_test", "secret",
                    "http://127.0.0.1:" + upstream.getAddress().getPort(),
                    "ws://localhost/ws", "/api/phantom", Duration.ofMinutes(15),
                    Duration.ofDays(30), true, false, List.of());
            var mvc = MockMvcBuilders.standaloneSetup(
                    new ProxyController(new ProxyPathPolicy(), sessions, properties)).build();
            mvc.perform(get("/api/phantom/media/m1/content").header("X-User-Id", "u1")
                            .queryParam("expires", "123").queryParam("signature", "sig"))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(bytes))
                    .andExpect(content().contentType("application/json"))
                    .andExpect(header().string("Content-Disposition", disposition))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Cache-Control", "private, max-age=60"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        } finally {
            upstream.stop(0);
        }
    }
}
