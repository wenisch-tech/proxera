package tech.wenisch.proxera.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngressSpec {
    private String name;
    private String namespace;
    private String className;
    @Builder.Default
    private Map<String, String> annotations = new LinkedHashMap<>();
    private String host;
    private String path;
    /** Exact | Prefix | ImplementationSpecific */
    private String pathType;
    private boolean tlsEnabled;
    private String tlsSecretName;
}
