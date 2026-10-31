package fr.claudegateway.quota.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * <b>Ce que le fournisseur facture</b> (F-224 / SF-224-02), lu chez lui — l'autre moitié du rapprochement avec le
 * coût que la gateway calcule. Interface neutre (Provider Independence) : aujourd'hui l'API d'administration
 * d'Anthropic, demain celle d'un autre fournisseur.
 */
public interface ProviderBillingReport {

    /** Vrai si la lecture est possible (clé d'administration posée). Sans elle, rien n'est appelé. */
    boolean configured();

    /** Le montant facturé par jour, en dollars, sur {@code [from, to)} (UTC). */
    List<BilledDay> billedCosts(LocalDate from, LocalDate to);

    /** La consommation par jour, clé API et modèle, sur {@code [from, to)} (UTC). */
    List<KeyUsage> usageByKey(LocalDate from, LocalDate to);

    /** Le nom lisible des clés API connues (identifiant → « nom (indice) »), best-effort. */
    Map<String, String> keyLabels();

    /** Un jour de facture. */
    record BilledDay(LocalDate day, BigDecimal amountUsd) {
    }

    /**
     * Une consommation d'une clé, un jour, sur un modèle — les tokens par nature, pour être chiffrés à la grille.
     *
     * @param inputTokens      entrée au plein tarif (hors cache)
     * @param cacheWriteTokens écriture de cache (5 min et 1 h confondues)
     */
    record KeyUsage(LocalDate day, String apiKeyId, String model, long inputTokens, long outputTokens,
                    long cacheReadTokens, long cacheWriteTokens) {
    }
}
