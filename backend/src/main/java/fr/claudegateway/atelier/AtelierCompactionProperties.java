package fr.claudegateway.atelier;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages de la <b>compaction automatique</b> du fil d'Atelier (F-117 / SF-117-01). Externalisés
 * pour être ajustables sans livraison, et portés par un record à part afin de ne toucher à aucun des
 * constructeurs d'{@link AtelierProperties} (et donc à aucun de leurs appelants de test).
 *
 * @param enabled        active la compaction (défaut {@code true}). <b>Coupe-circuit</b> : à
 *                       {@code false}, aucune estimation ni résumé — le fil est rejoué exactement
 *                       comme avant F-117.
 * @param triggerTokens  seuil, en tokens estimés du <b>texte rejoué</b>, au-delà duquel la compaction
 *                       se déclenche (défaut {@code 120 000}). Volontairement <b>sous</b> la fenêtre
 *                       réelle du modèle (200 K) : la marge couvre la consigne système, les outils et
 *                       la sortie. Estimé par heuristique caractères/token — jamais un décompte exact,
 *                       qui exigerait un appel réseau à chaque tour.
 * @param keepRecentTurns nombre de <b>messages récents</b> gardés intégralement au rejeu (défaut
 *                       {@code 6}, soit ~3 tours user/assistant). Tout ce qui précède est résumé. Un
 *                       plancher de 2 garantit qu'au moins le dernier échange survit entier.
 */
@ConfigurationProperties(prefix = "app.atelier.compaction")
public record AtelierCompactionProperties(Boolean enabled, Integer triggerTokens,
        Integer keepRecentTurns) {

    /** Seuil de déclenchement par défaut (F-117 / SF-117-01) : marge de sécurité sous 200 K. */
    public static final int DEFAULT_TRIGGER_TOKENS = 120_000;
    /** Messages récents gardés entiers par défaut. */
    public static final int DEFAULT_KEEP_RECENT_TURNS = 6;
    /** Plancher : au moins un échange complet reste toujours intact. */
    public static final int MIN_KEEP_RECENT_TURNS = 2;

    public AtelierCompactionProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        // Un seuil absent, nul ou négatif retombe sur le défaut : une faute de configuration ne doit
        // ni couper toute compaction, ni la déclencher à chaque tour.
        if (triggerTokens == null || triggerTokens <= 0) {
            triggerTokens = DEFAULT_TRIGGER_TOKENS;
        }
        if (keepRecentTurns == null || keepRecentTurns < MIN_KEEP_RECENT_TURNS) {
            keepRecentTurns = DEFAULT_KEEP_RECENT_TURNS;
        }
    }
}
