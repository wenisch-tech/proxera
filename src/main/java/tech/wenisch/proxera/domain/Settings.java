package tech.wenisch.proxera.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "settings")
@Getter
@Setter
@NoArgsConstructor
public class Settings {

    @Id
    private Long id = 1L;

    @Column(name = "rewrite_urls", nullable = false)
    private boolean rewriteUrls = true;
}
