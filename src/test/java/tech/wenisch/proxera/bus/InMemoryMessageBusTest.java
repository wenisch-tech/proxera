package tech.wenisch.proxera.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import tools.jackson.databind.ObjectMapper;

import tech.wenisch.proxera.tunnel.ResponsePayload;
import tech.wenisch.proxera.tunnel.TunnelErrorException;
import tech.wenisch.proxera.tunnel.TunnelErrorPayload;
import tech.wenisch.proxera.tunnel.TunnelManager;

class InMemoryMessageBusTest {

    @Test
    void failCompletesPendingFutureExceptionally() {
        InMemoryMessageBus bus = new InMemoryMessageBus(mock(ApplicationEventPublisher.class));
        bus.setTunnelManager(mock(TunnelManager.class));
        bus.setObjectMapper(new ObjectMapper());

        CompletableFuture<ResponsePayload> future = bus.dispatch(UUID.randomUUID(), "{\"path\":\"/\"}", "corr-1");
        bus.fail("corr-1", new TunnelErrorPayload("UPSTREAM_ERROR", "backend failed"));

        assertThat(future).isCompletedExceptionally();
        Throwable failure = catchFailure(future);
        assertThat(failure).isInstanceOf(TunnelErrorException.class);
        assertThat(((TunnelErrorException) failure).error().code()).isEqualTo("UPSTREAM_ERROR");
    }

    @Test
    void completeStillReturnsResponse() throws Exception {
        InMemoryMessageBus bus = new InMemoryMessageBus(mock(ApplicationEventPublisher.class));
        bus.setTunnelManager(mock(TunnelManager.class));
        bus.setObjectMapper(new ObjectMapper());

        CompletableFuture<ResponsePayload> future = bus.dispatch(UUID.randomUUID(), "{\"path\":\"/\"}", "corr-2");
        bus.complete("corr-2", new ResponsePayload(200, Map.of(), null, 1));

        assertThat(future.get().status()).isEqualTo(200);
    }

    private static Throwable catchFailure(CompletableFuture<?> future) {
        try {
            future.get();
            throw new AssertionError("expected failure");
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
