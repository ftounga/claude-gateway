package fr.claudegateway.ai.failover;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.bedrock.BedrockProperties;

/**
 * L'aiguillage entre Anthropic et Bedrock (F-277 / SF-277-02) : mode, classement des pannes, disjoncteur.
 * Partagé par les deux interfaces IA ({@code AIProvider}, {@code AiAgentProvider}).
 *
 * <p><b>Règles</b> (cadrage D5 à D8) :</p>
 * <ul>
 *   <li>{@code off} : Anthropic seul, rien ne change. Un appel BYOK va <b>toujours</b> chez Anthropic, avec la clé du
 *       client, quel que soit le mode.</li>
 *   <li>{@code bedrock} : tout appel hébergé part sur Bedrock.</li>
 *   <li>{@code auto} : Anthropic d'abord. L'appel est rejoué sur Bedrock en cas de crédit épuisé ou de
 *       facturation, de 401/403, de clé plateforme absente, ou de 5xx/529 resté en échec après les relances
 *       existantes. Il n'est <b>jamais</b> rejoué sur un autre 4xx (400 ordinaire, 413, 429), ni sur un refus du
 *       modèle (réponse 200), ni si du texte est déjà parti à l'écran.</li>
 *   <li>Disjoncteur : une panne de crédit, de clé ou de clé absente l'ouvre pour {@code open-minutes}. Pendant ce
 *       temps, les appels hébergés vont directement sur Bedrock. Ensuite, un appel retente Anthropic
 *       (demi-ouverture) : un succès referme, une nouvelle panne rouvre. Une surcharge ne fait basculer que l'appel
 *       en cours.</li>
 * </ul>
 */
@Component
public class ProviderFailover {

    private static final Logger log = LoggerFactory.getLogger(ProviderFailover.class);

    /** Pourquoi un appel est rejoué sur Bedrock. */
    public enum Cause {
        CREDIT(true), AUTH(true), MISSING_KEY(true), OVERLOADED(false);

        private final boolean opensCircuit;

        Cause(boolean opensCircuit) {
            this.opensCircuit = opensCircuit;
        }

        public boolean opensCircuit() {
            return opensCircuit;
        }
    }

    private final BedrockProperties properties;
    private final Clock clock;
    /** Fin d'ouverture du disjoncteur ; {@code null} = fermé. */
    private volatile Instant openUntil;

    public ProviderFailover(BedrockProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        ServedChannel.currentRoute(this::currentRoute);
        if (properties.failoverMode() != BedrockProperties.Mode.OFF) {
            log.info("Aiguillage IA : mode={}, région={}, préfixe={}, disjoncteur={} min.",
                    properties.failoverMode(), properties.region(), properties.modelPrefix(),
                    properties.openMinutes());
        }
    }

    public BedrockProperties.Mode mode() {
        return properties.failoverMode();
    }

    /** Route d'un appel hébergé à cet instant (pour {@link ServedChannel}). */
    AiChannel currentRoute() {
        return switch (mode()) {
            case OFF -> AiChannel.ANTHROPIC;
            case BEDROCK -> AiChannel.BEDROCK;
            case AUTO -> circuitOpen() ? AiChannel.BEDROCK : AiChannel.ANTHROPIC;
        };
    }

    /** Vrai tant que le disjoncteur est ouvert. */
    public boolean circuitOpen() {
        Instant until = openUntil;
        return until != null && clock.instant().isBefore(until);
    }

    /**
     * Route un appel.
     *
     * @param kind           libellé du journal ({@code agent}, {@code chat})
     * @param model          modèle demandé (journal)
     * @param byok           vrai si l'appel porte la clé du client : jamais basculé
     * @param anthropic      l'appel chez Anthropic
     * @param bedrock        le même appel chez Bedrock
     * @param replayAllowed  faux si l'appel Anthropic a déjà produit un effet visible (texte relayé)
     */
    public <T> T route(String kind, String model, boolean byok, Supplier<T> anthropic, Supplier<T> bedrock,
            BooleanSupplier replayAllowed) {
        BedrockProperties.Mode mode = mode();
        if (mode == BedrockProperties.Mode.OFF || byok) {
            T result = anthropic.get();
            ServedChannel.record(AiChannel.ANTHROPIC);
            return result;
        }
        if (mode == BedrockProperties.Mode.BEDROCK || circuitOpen()) {
            T result = bedrock.get();
            ServedChannel.record(AiChannel.BEDROCK);
            return result;
        }
        boolean probing = openUntil != null;
        T result;
        try {
            result = anthropic.get();
        } catch (RuntimeException ex) {
            Optional<Cause> cause = classify(ex);
            if (cause.isEmpty()) {
                throw ex;
            }
            if (!replayAllowed.getAsBoolean()) {
                // Cadrage D8 : du texte est déjà à l'écran ; le rejouer le dupliquerait. L'échec reste visible.
                log.warn("Bascule IA impossible : l'appel avait déjà relayé du texte (appel={}, modèle={}, cause={}).",
                        kind, model, cause.get());
                throw ex;
            }
            if (cause.get().opensCircuit()) {
                open(cause.get(), probing);
            }
            log.warn("Bascule IA vers Bedrock (appel={}, modèle={}, cause={}) : l'appel est rejoué sur le canal "
                    + "de secours.", kind, model, cause.get());
            try {
                T replayed = bedrock.get();
                ServedChannel.record(AiChannel.BEDROCK);
                return replayed;
            } catch (RuntimeException bedrockFailure) {
                bedrockFailure.addSuppressed(ex);
                log.warn("Bascule IA : Bedrock a échoué aussi (appel={}, modèle={}, erreur={}).", kind, model,
                        bedrockFailure.getClass().getSimpleName());
                throw bedrockFailure;
            }
        }
        if (probing) {
            close();
        }
        ServedChannel.record(AiChannel.ANTHROPIC);
        return result;
    }

    private void open(Cause cause, boolean reopening) {
        openUntil = clock.instant().plus(properties.openDuration());
        log.warn("Disjoncteur IA {} (cause={}) : appels hébergés servis par Bedrock pendant {} min.",
                reopening ? "rouvert" : "ouvert", cause, properties.openMinutes());
    }

    private void close() {
        if (openUntil != null) {
            openUntil = null;
            log.warn("Disjoncteur IA refermé : Anthropic répond de nouveau, les appels hébergés y retournent.");
        }
    }

    /**
     * Classe une panne d'Anthropic. Rien (vide) = pas de bascule : l'erreur suit son chemin habituel.
     * Visible des tests.
     */
    public static Optional<Cause> classify(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof AIProviderUnavailableException) {
                return Optional.of(Cause.MISSING_KEY);
            }
            if (t instanceof RestClientResponseException response) {
                return classify(response.getStatusCode().value(), response.getResponseBodyAsString());
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return Optional.empty();
    }

    static Optional<Cause> classify(int status, String body) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (status == 402 || ((status == 400 || status == 403)
                && (lower.contains("credit balance") || lower.contains("billing_error")
                        || lower.contains("purchase credits")))) {
            return Optional.of(Cause.CREDIT);
        }
        if (status == 401 || status == 403) {
            return Optional.of(Cause.AUTH);
        }
        if (status >= 500) {
            return Optional.of(Cause.OVERLOADED);
        }
        return Optional.empty();
    }
}
