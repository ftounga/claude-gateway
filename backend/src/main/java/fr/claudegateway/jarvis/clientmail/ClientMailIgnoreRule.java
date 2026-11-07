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

/** <b>Une règle « ignorer »</b> (F-249 / SF-249-06, D13) — appliquée avant toute IA aux mails déposés d'un client. */
@Entity
@Table(name = "jarvis_mail_ignore_rules")
@Getter
@Setter
@NoArgsConstructor
public class ClientMailIgnoreRule {

    public static final String SENDER = "SENDER";
    public static final String SUBJECT = "SUBJECT";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "kind", nullable = false, length = 8)
    private String kind;

    @Column(name = "rule_value", nullable = false, length = 200)
    private String ruleValue;

    @Column(name = "sample", length = 200)
    private String sample;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
