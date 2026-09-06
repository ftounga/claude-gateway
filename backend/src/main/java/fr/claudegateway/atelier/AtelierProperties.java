package fr.claudegateway.atelier;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages de l'Atelier (F-28). Externalisés pour être ajustables sans changement de code.
 *
 * @param storage       fournisseur de stockage : {@code in-memory} (dev/tests, défaut) ou {@code s3} (cluster)
 * @param bucket        bucket S3 (mode {@code s3}) ; fourni par l'environnement
 * @param prefix        préfixe racine des objets ({@code atelier/} par défaut)
 * @param maxTotalBytes taille décompressée totale maximale d'un zip (anti zip-bomb)
 * @param maxEntries    nombre d'entrées maximal d'un zip (anti zip-bomb)
 * @param maxFileBytes  taille maximale d'un fichier décompressé (anti zip-bomb)
 * @param maxIterations plafond d'allers-retours d'un message dans la boucle d'agent (F-28 / SF-28-19).
 *                      Calibré sur l'usage réel : à 12, un tiers des demandes était coupé en chemin.
 *                      Configurable pour se baisser sans livraison, tant que le cache de prompt (R6)
 *                      n'a pas changé l'arbitrage de coût.
 * @param model         modèle de la <b>boucle maison</b> (F-39 / SF-39-10, défaut
 *                      {@code claude-opus-5}). Réglage à elle : il était jusqu'ici emprunté au
 *                      catalogue du <b>chat</b> (F-02), si bien que changer le modèle proposé aux
 *                      utilisateurs changeait en silence celui qui exécute des commandes sur leur
 *                      machine. Volontairement <b>non</b> validé contre {@code ModelCatalog}, qui dit
 *                      ce que le chat propose, pas ce que le harnais exécute
 * @param effort        effort de raisonnement de la boucle maison (F-39 / SF-39-10) : {@code low} à
 *                      {@code max}, défaut {@code high} — le défaut du fournisseur, posé
 *                      explicitement pour être réglable sans livraison. {@code xhigh} attend le
 *                      lot 6 : la boucle appelle en non-streamé, et monter l'effort avant d'avoir
 *                      câblé timeout et retry échangerait de la profondeur contre des tours coupés
 *                      au budget de temps
 */
@ConfigurationProperties(prefix = "app.atelier")
public record AtelierProperties(
        String storage,
        String bucket,
        String prefix,
        Long maxTotalBytes,
        Integer maxEntries,
        Long maxFileBytes,
        Integer maxIterations,
        String model,
        String effort) {

    /** Modèle de la boucle maison à défaut de configuration (F-39 / SF-39-10). */
    public static final String DEFAULT_MODEL = "claude-opus-5";
    /** Effort par défaut : celui du fournisseur, écrit pour être réglable (F-39 / SF-39-10). */
    public static final String DEFAULT_EFFORT = "high";
    /** Niveaux d'effort acceptés — même vocabulaire que le chemin Managed Agents (SF-28-17). */
    private static final java.util.Set<String> ALLOWED_EFFORTS =
            java.util.Set.of("low", "medium", "high", "xhigh", "max");

    public AtelierProperties {
        if (storage == null || storage.isBlank()) {
            storage = "in-memory";
        }
        if (prefix == null || prefix.isBlank()) {
            prefix = "atelier/";
        }
        if (maxTotalBytes == null || maxTotalBytes <= 0) {
            maxTotalBytes = 50L * 1024 * 1024; // 50 Mo
        }
        if (maxEntries == null || maxEntries <= 0) {
            maxEntries = 2000;
        }
        if (maxFileBytes == null || maxFileBytes <= 0) {
            maxFileBytes = 2L * 1024 * 1024; // 2 Mo
        }
        if (maxIterations == null || maxIterations <= 0) {
            maxIterations = 30;
        }
        // Au-delà, le budget de temps du tour (10 min) aurait tranché de toute façon : mieux vaut
        // une borne lisible qu'un plafond qui n'a jamais l'occasion de s'appliquer.
        if (maxIterations > 100) {
            maxIterations = 100;
        }
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        // Une faute de frappe en configuration ne doit pas faire échouer les tours (même règle que
        // SF-28-17) : un effort inconnu retombe sur le défaut, il n'arrête pas le démarrage.
        if (effort == null || effort.isBlank() || !ALLOWED_EFFORTS.contains(effort)) {
            effort = DEFAULT_EFFORT;
        }
    }
}
