package fr.claudegateway.billing.seat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration du <b>supplément par poste supplémentaire</b> (F-65 / SF-65-01).
 *
 * <p><b>Aucun montant n'est décidé dans le code.</b> Ce bloc ne porte que le mécanisme : combien de
 * postes l'abonnement couvre, combien de jetons apporte un supplément, comment la part se proratise
 * en cours de mois, et sur quel price le PO branchera la facturation. <b>Les défauts préservent le
 * comportement actuel</b> : {@code tokensPerExtraSeat = 0} et aucun price configuré, donc aucun
 * jeton apporté et rien de facturé tant que le PO n'a rien renseigné.</p>
 *
 * <p><b>Pourquoi {@code app.seat} et non {@code app.billing.stripe}</b>, où vivent les autres price
 * IDs : le supplément n'est ni un plan ni une option du catalogue, et ses quatre réglages —
 * couverture, jetons, paliers, proratisation — n'ont de sens que lus ensemble. Les disperser
 * obligerait le PO à en rassembler la moitié dans un fichier et l'autre moitié dans un autre pour
 * comprendre ce qu'il vend.</p>
 *
 * @param includedSeats      nombre de postes couverts par l'abonnement lui-même (défaut <b>1</b> —
 *                           c'est la prémisse de la grille : « l'abonnement couvre un poste »)
 * @param tokensPerExtraSeat jetons mensuels apportés par un poste supplémentaire, quand aucun palier
 *                           ne s'applique (défaut <b>0</b> : mécanisme inerte)
 * @param quotaTiers         dégressivité de l'apport de jetons par rang de supplément (vide par
 *                           défaut → apport plat). <b>Miroir</b> de la grille à paliers du
 *                           fournisseur de paiement : c'est le PO qui tient les deux alignées
 * @param proration          règle de proratisation d'un poste devenu facturable en cours de mois
 * @param priceId            price ID du supplément chez le fournisseur de paiement — <b>point de
 *                           branchement</b> laissé au PO. Vide => le supplément n'est pas facturé,
 *                           et les écrans le disent
 * @param displayPrice       montant d'affichage du supplément (cosmétique, comme les autres
 *                           {@code display-prices} : le débit appartient au price ID). Vide par
 *                           défaut — un montant inventé ici serait pire que son absence
 */
@ConfigurationProperties(prefix = "app.seat")
public record SeatProperties(
        Integer includedSeats,
        Long tokensPerExtraSeat,
        List<QuotaTier> quotaTiers,
        SeatProration proration,
        String priceId,
        String displayPrice) {

    /** L'abonnement couvre un poste : la prémisse de `docs/TARIFS.md`, pas une décision de prix. */
    private static final int DEFAULT_INCLUDED_SEATS = 1;

    public SeatProperties {
        if (includedSeats == null || includedSeats < 0) {
            includedSeats = DEFAULT_INCLUDED_SEATS;
        }
        if (tokensPerExtraSeat == null || tokensPerExtraSeat < 0) {
            // Fail-inert : une valeur absente ou aberrante n'invente pas de jetons.
            tokensPerExtraSeat = 0L;
        }
        if (proration == null) {
            proration = SeatProration.DAILY;
        }
        quotaTiers = sanitize(quotaTiers);
        priceId = priceId == null ? "" : priceId.trim();
        displayPrice = displayPrice == null ? "" : displayPrice.trim();
    }

    /**
     * Jetons apportés par le supplément de rang {@code rank} (1 = premier poste au-delà de ceux que
     * l'abonnement couvre), <b>avant</b> proratisation.
     *
     * <p>Le premier palier dont le plafond couvre ce rang gagne ; au-delà du dernier palier, c'est
     * le dernier qui continue de s'appliquer — une grille de remises s'arrête toujours sur un
     * « et au-delà », jamais sur un trou. Sans aucun palier, l'apport est plat.</p>
     *
     * @param rank rang du supplément (1, 2, 3…) ; un rang nul ou négatif n'apporte rien
     * @return jetons mensuels apportés, jamais négatif
     */
    public long tokensForExtraSeat(int rank) {
        if (rank <= 0) {
            return 0L;
        }
        if (quotaTiers.isEmpty()) {
            return tokensPerExtraSeat;
        }
        return quotaTiers.stream()
                .filter(tier -> rank <= tier.upToSeats())
                .findFirst()
                .orElse(quotaTiers.get(quotaTiers.size() - 1))
                .tokens();
    }

    /** Vrai si le supplément est réellement facturé, c'est-à-dire si le PO a branché un price. */
    public boolean isBilled() {
        return !priceId.isEmpty();
    }

    private static List<QuotaTier> sanitize(List<QuotaTier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return List.of();
        }
        List<QuotaTier> valid = new ArrayList<>(tiers.stream()
                .filter(tier -> tier != null && tier.upToSeats() != null && tier.upToSeats() > 0)
                .filter(tier -> tier.tokens() != null && tier.tokens() >= 0)
                .toList());
        valid.sort(Comparator.comparingInt(QuotaTier::upToSeats));
        return List.copyOf(valid);
    }

    /**
     * Un palier de dégressivité : « jusqu'au n-ième poste supplémentaire, chacun apporte tant de
     * jetons ».
     *
     * <p>Une table et non une formule : une remise commerciale est une liste de paliers négociés,
     * pas une fonction mathématique — et une formule dans le code serait un montant déguisé.</p>
     *
     * @param upToSeats rang maximal de supplément couvert par ce palier (strictement positif)
     * @param tokens    jetons mensuels apportés par chaque supplément de ce palier
     */
    public record QuotaTier(Integer upToSeats, Long tokens) {
    }
}
