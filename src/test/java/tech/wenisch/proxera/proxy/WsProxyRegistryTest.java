package tech.wenisch.proxera.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import tech.wenisch.proxera.bus.InMemoryWsRelayBus;
import tech.wenisch.proxera.bus.WsRelayMessage;
import tech.wenisch.proxera.tunnel.TunnelManager;

class WsProxyRegistryTest {

    @Test
    void agentToClientFramesAreSerializedPerClientSession() throws Exception {
        InMemoryWsRelayBus relayBus = new InMemoryWsRelayBus();
        WsProxyRegistry registry = new WsProxyRegistry(relayBus, new TunnelManager(new ObjectMapper()));
        RecordingWebSocketSession session = new RecordingWebSocketSession();
        String wsSessionId = "ws-1";

        registry.registerClientSession(wsSessionId, session);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            int value = i;
            executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                String payload = Base64.getEncoder().encodeToString(("msg-" + value).getBytes());
                relayBus.publishA2C(wsSessionId, WsRelayMessage.data(payload, false));
                return null;
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(session.sentCount.get()).isEqualTo(4);
        assertThat(session.maxConcurrentSends.get()).isEqualTo(1);
    }

    private static final class RecordingWebSocketSession implements WebSocketSession {
        private final AtomicInteger activeSends = new AtomicInteger();
        private final AtomicInteger maxConcurrentSends = new AtomicInteger();
        private final AtomicInteger sentCount = new AtomicInteger();
        private volatile boolean open = true;

        @Override
        public String getId() {
            return "client-session";
        }

        @Override
        public URI getUri() {
            return URI.create("wss://example.invalid/api/websocket");
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return HttpHeaders.EMPTY;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return Map.of();
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 49152);
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 64 * 1024;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 64 * 1024;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            int current = activeSends.incrementAndGet();
            maxConcurrentSends.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(25);
                sentCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } finally {
                activeSends.decrementAndGet();
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public void close(CloseStatus status) {
            open = false;
        }
    }
}
