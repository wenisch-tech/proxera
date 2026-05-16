package tech.wenisch.proxera.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
