package fr.claudegateway.billing.seat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Configuration du <b>supplément par client supplémentaire</b> (F-65 / SF-65-01), <b>par espace</b> depuis
 * F-107 / SF-107-05.
 *
 * <p>Les clés historiques ({@code app.seat.*}) portent le supplément de la <b>Forge</b> ; {@code app.seat.vigie}
 * porte celui de la <b>Vigie</b>. Les <b>montants et jetons décidés par le PO le 2026-09-13</b> (cadrage F-107
 * §9) sont les défauts de {@code application.yml} : Forge 39 / 29 / 19 € avec 2 / 1,5 / 1 M jetons, Vigie 39 €
 * fixe. <b>Les price IDs restent vides</b> — et tant qu'un price n'est pas branché, <b>le supplément
 * n'apporte aucun jeton</b> ({@link SeatQuotaService}) : une part de quota sans supplément facturé serait un
 * cadeau.</p>
 *
 * @param includedSeats      clients couverts par l'abonnement lui-même dans la Forge (défaut <b>1</b>)
 * @param tokensPerExtraSeat jetons mensuels apportés par un client supplémentaire quand aucun palier ne
 *                           s'applique (défaut <b>0</b>)
 * @param quotaTiers         dégressivité par rang de supplément : jetons et montant affiché. <b>Miroir</b>
 *                           de la grille à paliers du fournisseur de paiement, tenu aligné par le PO
 * @param proration          règle de proratisation d'un client devenu facturable en cours de mois
 * @param priceId            price ID du supplément Forge — vide => non facturé, et aucun jeton apporté
 * @param displayPrice       montant d'affichage quand aucun palier ne porte le sien (cosmétique)
 * @param vigie              supplément de la Vigie (F-107 / SF-107-05)
 */
@ConfigurationProperties(prefix = "app.seat")
public record SeatProperties(
        Integer includedSeats,
        Long tokensPerExtraSeat,
        List<QuotaTier> quotaTiers,
        SeatProration proration,
        String priceId,
        String displayPrice,
        Vigie vigie) {

    /** L'abonnement couvre un client : la prémisse de `docs/TARIFS.md`, pas une décision de prix. */
    private static final int DEFAULT_INCLUDED_SEATS = 1;

    /** Configuration d'avant F-107 / SF-107-05 : Vigie par défaut. */
    public SeatProperties(Integer includedSeats, Long tokensPerExtraSeat, List<QuotaTier> quotaTiers,
            SeatProration proration, String priceId, String displayPrice) {
        this(includedSeats, tokensPerExtraSeat, quotaTiers, proration, priceId, displayPrice, null);
    }

    @ConstructorBinding
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
        if (vigie == null) {
            vigie = new Vigie(null, null, null);
        }
    }

    /**
     * Jetons apportés par le supplément de rang {@code rank} (1 = premier client au-delà de ceux que
     * l'abonnement couvre), <b>avant</b> proratisation. Le premier palier dont le plafond couvre ce rang
     * gagne ; au-delà du dernier, le dernier continue de s'appliquer. Sans palier, l'apport est plat.
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
        return tierFor(rank).tokens();
    }

    /**
     * Montant d'affichage du supplément Forge de rang {@code rank} (F-107 / SF-107-05) : celui du palier s'il
     * en porte un, sinon {@link #displayPrice()}.
     *
     * @param rank rang du supplément (1, 2, 3…)
     * @return le montant, éventuellement vide
     */
    public String displayPriceForExtraSeat(int rank) {
        if (rank <= 0 || quotaTiers.isEmpty()) {
            return displayPrice;
        }
        String tierPrice = tierFor(rank).displayPrice();
        return tierPrice == null || tierPrice.isBlank() ? displayPrice : tierPrice.trim();
    }

    /** Montant d'affichage du premier supplément Forge — celui qu'on annonce en tête. */
    public String firstExtraSeatDisplayPrice() {
        return displayPriceForExtraSeat(1);
    }

    /** Vrai si le supplément Forge est réellement facturé, c'est-à-dire si le PO a branché un price. */
    public boolean isBilled() {
        return !priceId.isEmpty();
    }

    private QuotaTier tierFor(int rank) {
        return quotaTiers.stream()
                .filter(tier -> rank <= tier.upToSeats())
                .findFirst()
                .orElse(quotaTiers.get(quotaTiers.size() - 1));
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
     * Un palier de dégressivité : « jusqu'au n-ième client supplémentaire, chacun apporte tant de jetons et
     * s'affiche à tel montant ».
     *
     * @param upToSeats    rang maximal de supplément couvert par ce palier (strictement positif)
     * @param tokens       jetons mensuels apportés par chaque supplément de ce palier
     * @param displayPrice montant d'affichage EUR de ce palier (F-107 / SF-107-05), ou {@code null}
     */
    public record QuotaTier(Integer upToSeats, Long tokens, String displayPrice) {

        /** Palier d'avant F-107 : sans montant. */
        public QuotaTier(Integer upToSeats, Long tokens) {
            this(upToSeats, tokens, null);
        }

        @ConstructorBinding
        public QuotaTier {
        }
    }

    /**
     * Le supplément de la <b>Vigie</b> (F-107 / SF-107-05) : fixe, sans dégressivité — chaque synchro coûte
     * vraiment. Il n'apporte <b>aucun jeton de conversation</b> : ce qu'un client suivi apporte est sa réserve
     * de synchro, déjà comptée par client.
     *
     * @param includedSeats clients suivis couverts par l'option ou le plan Vigie (défaut <b>1</b>)
     * @param priceId       price ID du supplément Vigie — vide => non facturé
     * @param displayPrice  montant d'affichage EUR (cosmétique), vide par défaut dans le code
     */
    public record Vigie(Integer includedSeats, String priceId, String displayPrice) {

        public Vigie {
            if (includedSeats == null || includedSeats < 0) {
                includedSeats = DEFAULT_INCLUDED_SEATS;
            }
            priceId = priceId == null ? "" : priceId.trim();
            displayPrice = displayPrice == null ? "" : displayPrice.trim();
        }

        /** Vrai si le supplément Vigie est réellement facturé. */
        public boolean isBilled() {
            return !priceId.isEmpty();
        }
    }
}
