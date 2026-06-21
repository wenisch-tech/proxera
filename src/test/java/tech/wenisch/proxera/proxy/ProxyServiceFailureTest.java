package tech.wenisch.proxera.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncContext;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProxyService proxyService = new ProxyService(
            routingService, messageBus, accessLogService, objectMapper, settingsService);

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

    @Test
    void disabledClientIpForwardingStripsClientIpHeadersButKeepsProxyContext() throws Exception {
        Agent agent = Agent.builder().id(UUID.randomUUID()).name("k8s@home").build();
        Route route = Route.builder()
                .id(UUID.randomUUID())
                .name("homeassistant")
                .agent(agent)
                .localHost("192.168.1.199")
                .localPort(8123)
                .forwardClientIpHeaders(false)
                .build();
        RouteDomain routeDomain = RouteDomain.builder()
                .route(route)
                .domain("homeassistant.intranet.wenisch.tech")
                .build();

        MockHttpServletRequest request = request("GET", "/auth/login_flow");
        request.addHeader("X-Forwarded-For", "10.244.21.1");
        request.addHeader("X-Real-IP", "10.244.21.1");
        request.addHeader("Forwarded", "for=10.244.21.1");
        request.addHeader("Content-Type", "multipart/form-data; boundary=----WebKitFormBoundaryTest");
        String multipartBody = "------WebKitFormBoundaryTest\r\n"
                + "Content-Disposition: form-data; name=\"grant_type\"\r\n\r\n"
                + "authorization_code\r\n"
                + "------WebKitFormBoundaryTest--\r\n";
        request.setContent(multipartBody.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AsyncContext asyncContext = mock(AsyncContext.class);
        CompletableFuture<ResponsePayload> completed = CompletableFuture.completedFuture(
                new ResponsePayload(200, java.util.Map.of(), null, 4));
        ArgumentCaptor<String> requestJson = ArgumentCaptor.forClass(String.class);

        when(routingService.resolve("homeassistant.intranet.wenisch.tech", "/auth/login_flow"))
                .thenReturn(Optional.of(routeDomain));
        when(messageBus.dispatch(eq(agent.getId()), requestJson.capture(), any(String.class))).thenReturn(completed);

        proxyService.proxy(request, response, asyncContext);

        JsonNode headers = objectMapper.readTree(requestJson.getValue()).get("headers");
        assertThat(headers.has("x-forwarded-for")).isFalse();
        assertThat(headers.has("x-real-ip")).isFalse();
        assertThat(headers.has("forwarded")).isFalse();
        assertThat(headers.get("content-type").get(0).asText())
                .isEqualTo("multipart/form-data; boundary=----WebKitFormBoundaryTest");
        assertThat(headers.get("x-forwarded-host").get(0).asText()).isEqualTo("homeassistant.intranet.wenisch.tech");
        assertThat(headers.get("x-forwarded-proto").get(0).asText()).isEqualTo("https");
        assertThat(headers.get("x-forwarded-port").get(0).asText()).isEqualTo("443");
        assertThat(new String(Base64.getDecoder()
                .decode(objectMapper.readTree(requestJson.getValue()).get("body").asText()), StandardCharsets.UTF_8))
                .isEqualTo(multipartBody);
        assertThat(objectMapper.readTree(requestJson.getValue()).get("preserveHostHeader").asBoolean()).isFalse();
    }

    @Test
    void preserveHostHeaderFlagIsIncludedInRequestPayload() throws Exception {
        Agent agent = Agent.builder().id(UUID.randomUUID()).name("minio").build();
        Route route = Route.builder()
                .id(UUID.randomUUID())
                .name("s3")
                .agent(agent)
                .localHost("192.168.1.248")
                .localPort(80)
                .preserveHostHeader(true)
                .build();
        RouteDomain routeDomain = RouteDomain.builder()
                .route(route)
                .domain("s3.intranet.wenisch.tech")
                .build();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/backups");
        request.addHeader("Host", "s3.intranet.wenisch.tech");
        request.setRemoteAddr("10.244.7.1");
        request.setServerPort(443);
        request.setScheme("https");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AsyncContext asyncContext = mock(AsyncContext.class);
        CompletableFuture<ResponsePayload> completed = CompletableFuture.completedFuture(
                new ResponsePayload(200, java.util.Map.of(), null, 2));
        ArgumentCaptor<String> requestJson = ArgumentCaptor.forClass(String.class);

        when(routingService.resolve("s3.intranet.wenisch.tech", "/backups"))
                .thenReturn(Optional.of(routeDomain));
        when(messageBus.dispatch(eq(agent.getId()), requestJson.capture(), any(String.class))).thenReturn(completed);

        proxyService.proxy(request, response, asyncContext);

        JsonNode payload = objectMapper.readTree(requestJson.getValue());
        assertThat(payload.get("preserveHostHeader").asBoolean()).isTrue();
        assertThat(payload.get("headers").get("host").get(0).asText()).isEqualTo("s3.intranet.wenisch.tech");
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
