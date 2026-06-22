package tech.wenisch.proxera.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PodIdentityResolverTest {

    @Test
    void prefersExplicitProxeraPodId() {
        PodIdentityResolver resolver = new PodIdentityResolver("proxera-1", "hostname-1");

        assertThat(resolver.currentPodId()).contains("proxera-1");
    }

    @Test
    void fallsBackToHostnameWhenExplicitPodIdMissing() {
        PodIdentityResolver resolver = new PodIdentityResolver("", "hostname-1");

        assertThat(resolver.currentPodId()).contains("hostname-1");
    }
}
