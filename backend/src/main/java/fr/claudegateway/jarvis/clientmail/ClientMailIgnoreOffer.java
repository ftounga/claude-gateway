package fr.claudegateway.jarvis.clientmail;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** <b>Une proposition « Ignorer ces mails ? »</b> (F-249 / SF-249-06, D13) — repérée sans IA, jamais reproposée. */
@Entity
@Table(name = "jarvis_mail_ignore_offers")
@Getter
@Setter
@NoArgsConstructor
public class ClientMailIgnoreOffer {

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String DECLINED = "DECLINED";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** {@code SENDER} ou {@code SUBJECT}. */
    @Column(name = "kind", nullable = false, length = 8)
    private String kind;

    @Column(name = "rule_value", nullable = false, length = 200)
    private String ruleValue;

    /** Ce que l'écran montre (l'adresse, ou un objet d'exemple). */
    @Column(name = "sample", length = 200)
    private String sample;

    @Column(name = "occurrences", nullable = false)
    private int occurrences;

    @Column(name = "status", nullable = false, length = 10)
    private String status = PENDING;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;
}
