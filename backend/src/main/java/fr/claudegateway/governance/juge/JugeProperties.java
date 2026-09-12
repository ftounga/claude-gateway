package fr.claudegateway.governance.juge;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Les garde-fous du juge indépendant (F-94 / SF-94-02).
 *
 * <p>Quatre réglages, et chacun répond à une question qu'on se posera en production : <b>est-ce que
 * je peux l'éteindre ?</b> ({@code enabled}), <b>combien ça coûte ?</b> ({@code model},
 * {@code maxTokens}), <b>combien de temps ça fait attendre ?</b> ({@code timeout}).</p>
 *
 * <p><b>Un réglage aberrant retombe sur le défaut</b> plutôt que d'empêcher le démarrage : une
 * valeur mal saisie dans une carte de configuration ne doit pas condamner le produit entier pour une
 * fonction qui, elle, n'a qu'à s'abstenir.</p>
 *
 * @param enabled   coupe-circuit. {@code false} : aucun appel n'est jamais émis
 * @param model     modèle du juge ; vide → le modèle <b>rapide</b> du catalogue
 * @param maxTokens plafond de sortie de l'appel ; un verdict est court par construction
 * @param timeout   délai au bout duquel on <b>rend la main</b> — le garde-fou qui compte : sans lui,
 *                  un fournisseur lent ajouterait le délai HTTP du chat (120 s) à la fin d'un tour
 */
@ConfigurationProperties(prefix = "app.governance.juge")
public record JugeProperties(Boolean enabled, String model, Integer maxTokens, Duration timeout) {

    /** Plafond de sortie par défaut. Un verdict tient en quelques lignes, le raisonnement en peu. */
    public static final int DEFAULT_MAX_TOKENS = 1_000;

    /** Bornes du plafond de sortie : en dessous le verdict serait coupé, au-dessus ce n'est plus un juge. */
    public static final int MIN_MAX_TOKENS = 100;
    public static final int MAX_MAX_TOKENS = 4_000;

    /** Délai par défaut : ce qu'on accepte d'ajouter à la fin d'un tour, pas davantage. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(25);

    /** Bornes du délai. En dessous, aucun appel n'aboutirait ; au-dessus, le tour est pris en otage. */
    public static final Duration MIN_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration MAX_TIMEOUT = Duration.ofSeconds(120);

    /** Normalise à la construction : ce qui est aberrant retombe sur le défaut, sans échouer. */
    public JugeProperties {
        enabled = enabled == null || enabled;
        model = model == null || model.isBlank() ? null : model.trim();
        maxTokens = maxTokens == null || maxTokens < MIN_MAX_TOKENS || maxTokens > MAX_MAX_TOKENS
                ? DEFAULT_MAX_TOKENS
                : maxTokens;
        timeout = timeout == null || timeout.compareTo(MIN_TIMEOUT) < 0
                || timeout.compareTo(MAX_TIMEOUT) > 0
                ? DEFAULT_TIMEOUT
                : timeout;
    }

    /** Les réglages par défaut — ceux d'une installation qui n'a rien configuré. */
    public static JugeProperties defaults() {
        return new JugeProperties(true, null, DEFAULT_MAX_TOKENS, DEFAULT_TIMEOUT);
    }

    /** Vrai si le juge a le droit d'appeler. */
    public boolean actif() {
        return Boolean.TRUE.equals(enabled);
    }
}
