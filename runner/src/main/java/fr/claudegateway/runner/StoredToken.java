package fr.claudegateway.runner;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Jeton runner tel que persisté localement (F-38 / SF-38-03) : la valeur opaque, le workspace lié et
 * la date d'expiration renvoyés par {@code POST /runner/pair} (SF-38-01).
 */
public record StoredToken(
        @JsonProperty("token") String token,
        @JsonProperty("workspaceId") UUID workspaceId,
        @JsonProperty("expiresAt") OffsetDateTime expiresAt) {

    @JsonCreator
    public StoredToken {
    }

    /** {@code true} si le jeton est expiré (avec une marge de sécurité de 60 s). */
    public boolean isExpired(OffsetDateTime now) {
        return expiresAt != null && !expiresAt.minusSeconds(60).isAfter(now);
    }
}
