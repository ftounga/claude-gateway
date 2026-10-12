package fr.claudegateway.runner;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Jeton runner tel que persisté localement (F-38 / SF-38-03) : la valeur opaque, le <b>poste</b> lié
 * et la date d'expiration renvoyés par {@code POST /runner/pair} (SF-38-01).
 *
 * <p><b>Le champ est {@code hostId} depuis F-48 / SF-48-01</b>, où le poste a remplacé le projet
 * comme unité de l'appairage. Le runner, lui, a continué d'attendre {@code workspaceId} jusqu'à
 * SF-48-04 : sur un mapper en configuration stricte, le {@code hostId} rendu par la gateway était un
 * champ <b>inconnu</b>, et toute lecture échouait. L'utilisateur lisait « Réponse d'appairage
 * illisible » pendant que la gateway, elle, avait créé le jeton — un appairage réussi d'un côté,
 * perdu de l'autre. <b>Aucun appairage de machine neuve n'a fonctionné entre le 2026-09-10 et le
 * 2026-09-12.</b></p>
 *
 * <p>{@link JsonIgnoreProperties} est la leçon de ce défaut, pas un ornement : un runner installé
 * chez un client vit plus longtemps que la version de gateway qu'il a connue. Un champ ajouté
 * demain ne doit pas l'empêcher de lire son jeton — il doit l'ignorer. C'est déjà ce que fait
 * {@code SessionMemory} ; {@code StoredToken} était le seul à ne pas le faire.</p>
 *
 * <p>F-194 / SF-194-03 : {@code hostName} (le poste auquel le code a rattaché cette machine) est
 * conservé pour être <b>dit à chaque reprise</b> ; {@code previousRootName} (la racine que cette
 * machine remplace) ne sert qu'au moment de l'appairage et n'est jamais stocké
 * ({@link #forStorage()}). Tous deux sont nuls face à une gateway antérieure.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredToken(
        @JsonProperty("token") String token,
        @JsonProperty("hostId") UUID hostId,
        @JsonProperty("expiresAt") OffsetDateTime expiresAt,
        @JsonProperty("hostName") String hostName,
        @JsonProperty("previousRootName") String previousRootName) {

    @JsonCreator
    public StoredToken {
    }

    /** Jeton sans nom de poste — celui d'une gateway antérieure à F-194. */
    public StoredToken(String token, UUID hostId, OffsetDateTime expiresAt) {
        this(token, hostId, expiresAt, null, null);
    }

    /** Ce qui s'écrit sur le disque : l'avertissement d'appairage n'a de sens qu'une fois. */
    public StoredToken forStorage() {
        return new StoredToken(token, hostId, expiresAt, hostName, null);
    }

    /** {@code true} si le jeton est expiré (avec une marge de sécurité de 60 s). */
    public boolean isExpired(OffsetDateTime now) {
        return expiresAt != null && !expiresAt.minusSeconds(60).isAfter(now);
    }
}
