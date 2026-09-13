package fr.claudegateway.radar;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Un autre nom d'un sujet (F-99) : « la double auth des presta » pour « le MFA ». C'est ce que
 * l'analyse (F-101) lit pour reconnaître un sujet que personne ne nomme pareil.
 */
@Entity
@Table(name = "radar_subject_aliases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarSubjectAlias {

    public static final int MAX_ALIAS_LENGTH = 200;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "alias", nullable = false, length = MAX_ALIAS_LENGTH)
    private String alias;

    /** Clé de comparaison : minuscules, espaces réduits. */
    @Column(name = "normalized", nullable = false, length = MAX_ALIAS_LENGTH)
    private String normalized;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 16)
    private RadarAliasOrigin origin;

    /** Consigne de rattachement : ce nom n'est <b>pas</b> ce sujet (SF-99-03). */
    @Column(name = "rejected", nullable = false)
    private boolean rejected;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
