package tech.wenisch.proxera.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.DispatcherType;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import tech.wenisch.proxera.service.RoutingService;

class ProxyDomainHandlerMappingTest {

    private final RoutingService routingService = mock(RoutingService.class);
    private final ProxyController proxyController = mock(ProxyController.class);
    private final ProxyDomainHandlerMapping mapping =
            new ProxyDomainHandlerMapping(routingService, proxyController);

    @Test
    void proxyHostRequestDispatchUsesProxyController() throws Exception {
        MockHttpServletRequest request = request(DispatcherType.REQUEST, "/manifest.json");
        when(routingService.isKnownProxyDomain("homeassistant.intranet.wenisch.tech")).thenReturn(true);

        assertThat(mapping.getHandlerInternal(request)).isSameAs(proxyController);
    }

    @Test
    void proxyHostErrorDispatchBypassesProxyController() throws Exception {
        MockHttpServletRequest request = request(DispatcherType.ERROR, "/error");
        when(routingService.isKnownProxyDomain("homeassistant.intranet.wenisch.tech")).thenReturn(true);

        assertThat(mapping.getHandlerInternal(request)).isNull();
    }

    @Test
    void realProxiedErrorPathStillUsesProxyController() throws Exception {
        MockHttpServletRequest request = request(DispatcherType.REQUEST, "/error");
        when(routingService.isKnownProxyDomain("homeassistant.intranet.wenisch.tech")).thenReturn(true);

        assertThat(mapping.getHandlerInternal(request)).isSameAs(proxyController);
    }

    private static MockHttpServletRequest request(DispatcherType dispatcherType, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setDispatcherType(dispatcherType);
        request.addHeader("Host", "homeassistant.intranet.wenisch.tech");
        return request;
    }
}
