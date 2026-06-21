package tech.wenisch.proxera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import tech.wenisch.proxera.domain.Agent;
import tech.wenisch.proxera.domain.Route;
import tech.wenisch.proxera.domain.RouteDomain;
import tech.wenisch.proxera.repository.AgentRepository;
import tech.wenisch.proxera.repository.RouteDomainRepository;
import tech.wenisch.proxera.repository.RouteRepository;

class RouteServiceTest {

    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private final RouteDomainRepository routeDomainRepository = mock(RouteDomainRepository.class);
    private final RoutingService routingService = mock(RoutingService.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final RouteService routeService = new RouteService(agentRepository, routeRepository, routeDomainRepository, routingService);

    @Test
    void updateCopiesRouteFlagsToManagedRoute() {
        ReflectionTestUtils.setField(routeService, "entityManager", entityManager);

        UUID routeId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Agent agent = Agent.builder().id(agentId).name("home").build();
        Route managed = Route.builder()
                .id(routeId)
                .name("homeassistant")
                .agent(agent)
                .localHost("192.168.1.199")
                .localPort(8123)
                .forwardClientIpHeaders(true)
                .preserveHostHeader(false)
                .build();
        managed.getDomains().add(RouteDomain.builder()
                .route(managed)
                .domain("homeassistant.intranet.wenisch.tech")
                .build());

        Route update = Route.builder()
                .id(routeId)
                .name("homeassistant")
                .agent(agent)
                .localHost("192.168.1.199")
                .localPort(8123)
                .enabled(true)
                .forwardClientIpHeaders(false)
                .preserveHostHeader(true)
                .build();
        update.getDomains().add(RouteDomain.builder()
                .domain("homeassistant.intranet.wenisch.tech")
                .build());

        when(agentRepository.existsById(agentId)).thenReturn(true);
        when(routeRepository.findByIdWithDetails(routeId)).thenReturn(Optional.of(managed));
        when(entityManager.getReference(eq(Agent.class), eq(agentId))).thenReturn(agent);

        routeService.save(update);

        assertThat(managed.isForwardClientIpHeaders()).isFalse();
        assertThat(managed.isPreserveHostHeader()).isTrue();
        verify(routingService).invalidateCache();
    }

    @Test
    void createResolvesAgentReferenceInsideTransaction() {
        ReflectionTestUtils.setField(routeService, "entityManager", entityManager);

        UUID agentId = UUID.randomUUID();
        Agent detachedAgent = Agent.builder().id(agentId).build();
        Agent managedAgent = Agent.builder().id(agentId).name("home").build();
        Route create = Route.builder()
                .name("homeassistant")
                .agent(detachedAgent)
                .localHost("192.168.1.199")
                .localPort(8123)
                .enabled(true)
                .build();

        when(agentRepository.existsById(agentId)).thenReturn(true);
        when(entityManager.getReference(eq(Agent.class), eq(agentId))).thenReturn(managedAgent);
        when(routeRepository.save(create)).thenReturn(create);

        routeService.save(create);

        assertThat(create.getAgent()).isSameAs(managedAgent);
        verify(routingService).invalidateCache();
    }
}
