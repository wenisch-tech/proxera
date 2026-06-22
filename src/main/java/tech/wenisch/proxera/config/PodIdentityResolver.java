package tech.wenisch.proxera.config;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PodIdentityResolver {

    private final String proxeraPodId;
    private final String hostname;

    public PodIdentityResolver(@Value("${PROXERA_POD_ID:}") String proxeraPodId,
                               @Value("${HOSTNAME:}") String hostname) {
        this.proxeraPodId = proxeraPodId;
        this.hostname = hostname;
    }

    public Optional<String> currentPodId() {
        if (proxeraPodId != null && !proxeraPodId.isBlank()) {
            return Optional.of(proxeraPodId);
        }
        if (hostname != null && !hostname.isBlank()) {
            return Optional.of(hostname);
        }
        return Optional.empty();
    }
}
