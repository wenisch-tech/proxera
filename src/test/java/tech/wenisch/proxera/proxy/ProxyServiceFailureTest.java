package tech.wenisch.proxera.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncContext;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tech.wenisch.proxera.bus.MessageBus;
import tech.wenisch.proxera.domain.Agent;
import tech.wenisch.proxera.domain.Route;
import tech.wenisch.proxera.domain.RouteDomain;
import tech.wenisch.proxera.service.AccessLogService;
import tech.wenisch.proxera.service.RoutingService;
import tech.wenisch.proxera.service.SettingsService;
import tech.wenisch.proxera.tunnel.ResponsePayload;

class ProxyServiceFailureTest {

    private final RoutingService routingService = mock(RoutingService.class);
    private final MessageBus messageBus = mock(MessageBus.class);
    private final AccessLogService accessLogService = mock(AccessLogService.class);
    private final SettingsService settingsService = mock(SettingsService.class);
    private final ProxyService proxyService = new ProxyService(
            routingService, messageBus, accessLogService, new ObjectMapper(), settingsService);

    @Test
    void missingRouteReturnsPlain404AndLogsFailure() throws Exception {
        MockHttpServletRequest request = request("GET", "/manifest.json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AsyncContext asyncContext = mock(AsyncContext.class);

        when(routingService.resolve("homeassistant.intranet.wenisch.tech", "/manifest.json"))
                .thenReturn(Optional.empty());

        proxyService.proxy(request, response, asyncContext);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).isEqualTo("Not Found");
        verify(accessLogService).logFailure(eq(null), eq(null), eq(request), eq(404), anyLong());
        verify(asyncContext).complete();
    }

    @Test
    void tunnelFailureReturnsPlain502AndLogsFailure() throws Exception {
        Agent agent = Agent.builder().id(UUID.randomUUID()).name("k8s@home").build();
        Route route = Route.builder()
                .id(UUID.randomUUID())
                .name("homeassistant")
                .agent(agent)
                .localHost("192.168.1.199")
                .localPort(8123)
                .build();
        RouteDomain routeDomain = RouteDomain.builder()
                .route(route)
                .domain("homeassistant.intranet.wenisch.tech")
                .build();

        MockHttpServletRequest request = request("POST", "/auth/token");
        request.setContent("grant_type=authorization_code&code=dummy".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AsyncContext asyncContext = mock(AsyncContext.class);
        CompletableFuture<ResponsePayload> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("agent timed out"));

        when(routingService.resolve("homeassistant.intranet.wenisch.tech", "/auth/token"))
                .thenReturn(Optional.of(routeDomain));
        when(messageBus.dispatch(eq(agent.getId()), any(String.class), any(String.class))).thenReturn(failed);

        proxyService.proxy(request, response, asyncContext);

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getContentAsString()).isEqualTo("Bad Gateway");
        verify(accessLogService).logFailure(eq(route), eq(agent.getId()), eq(request), eq(502), anyLong());
        verify(asyncContext).complete();
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Host", "homeassistant.intranet.wenisch.tech");
        request.setRemoteAddr("10.244.7.1");
        request.setServerPort(443);
        request.setScheme("https");
        return request;
    }
}
