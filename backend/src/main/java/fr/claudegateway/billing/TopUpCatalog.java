package fr.claudegateway.billing;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Catalogue statique des packs de tokens rachetables (top-up, F-21 / SF-21-02). Volontairement simple
 * (PROJECT.md §14.2 — Simplicity First) : une liste immuable en mémoire, sans table dédiée. Les prix et
 * price IDs Stripe sont externalisés en configuration ; ce catalogue ne décrit que la structure produit
 * (code, libellé, nombre de tokens crédités).
 *
 * <p>V1 expose un unique pack {@code STANDARD} — suffisant pour l'UX « Racheter des tokens » (SF-21-03).
 * Ajouter un pack = ajouter une entrée ici + son price ID de configuration — décision réversible.</p>
 */
@Component
public class TopUpCatalog {

    private static final List<TopUpPack> PACKS = List.of(
            // Le CODE reste « DAY » : il voyage dans les métadonnées Stripe des paiements déjà
            // encaissés et dans APP_QUOTA_ALERT_TOPUP_PACK. Seul le nom change (SF-21-06), pour
            // qu'il cesse d'être l'homonyme du plan « Pass journée » retiré par SF-09-04 — c'est
            // cette confusion qui avait masqué, des mois durant, que ce plan n'avait aucun prix.
            new TopUpPack("DAY", "Recharge 200 k tokens", 200_000L),
            new TopUpPack("STANDARD", "Recharge — 1 M tokens", 1_000_000L));

    /** Liste immuable des packs du catalogue. */
    public List<TopUpPack> packs() {
        return PACKS;
    }

    /**
     * Résout un pack par son code (insensible à la casse, trim). {@link Optional#empty()} si le code
     * est absent/vide ou inconnu du catalogue.
     */
    public Optional<TopUpPack> find(String code) {
        if (!StringUtils.hasText(code)) {
            return Optional.empty();
        }
        String normalized = code.trim();
        return PACKS.stream()
                .filter(p -> p.code().equalsIgnoreCase(normalized))
                .findFirst();
    }
}
