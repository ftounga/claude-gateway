package fr.claudegateway.governance.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La forme du marqueur de fin de tour (F-52 / SF-52-02).
 *
 * <p>Ce que ces tests protègent : que le contrôle reste <b>mécanique</b>. Il lit une forme, il
 * n'interprète rien — et tout ce qu'il ne sait pas lire est déclaré illisible plutôt que deviné.</p>
 */
class FinDeTourMarkerTest {

    @Test
    @DisplayName("un marqueur propre se lit")
    void aCleanMarkerIsRead() {
        Optional<FinDeTourMarker> marker =
                FinDeTourMarker.parse("Fait.\n\n<!-- fin-de-tour: promotion=aucune; dette=0 -->");

        assertThat(marker).isPresent();
        assertThat(marker.get().nothingToPromote()).isTrue();
        assertThat(marker.get().dette()).isZero();
    }

    @Test
    @DisplayName("la casse et les espaces sont libres")
    void caseAndSpacingAreFree() {
        assertThat(FinDeTourMarker.parse("<!--FIN-DE-TOUR:promotion = aucune ;dette =  2-->"))
                .get()
                .extracting(FinDeTourMarker::dette)
                .isEqualTo(2);
    }

    @Test
    @DisplayName("le dernier marqueur fait foi : une réponse peut citer la forme avant de la poser")
    void theLastMarkerWins() {
        String reply = "La forme est <!-- fin-de-tour: promotion=aucune; dette=9 -->, comme ceci.\n\n"
                + "<!-- fin-de-tour: promotion=aucune; dette=0 -->";

        assertThat(FinDeTourMarker.parse(reply)).get()
                .extracting(FinDeTourMarker::dette).isEqualTo(0);
    }

    @Test
    @DisplayName("une liste de promotions se lit, sans doublon ni vide")
    void promotionsAreRead() {
        Optional<FinDeTourMarker> marker = FinDeTourMarker.parse(
                "<!-- fin-de-tour: promotion=le format du jeton, , le format du jeton, la borne SSE; dette=1 -->");

        assertThat(marker).isPresent();
        assertThat(marker.get().nothingToPromote()).isFalse();
        assertThat(marker.get().promotions()).containsExactly("le format du jeton", "la borne SSE");
        assertThat(marker.get().dette()).isEqualTo(1);
    }

    @Test
    @DisplayName("« aucune », « none » et le vide veulent tous dire rien")
    void everyWayOfSayingNothing() {
        assertThat(FinDeTourMarker.parse("<!-- fin-de-tour: promotion=aucune; dette=0 -->")
                .orElseThrow().nothingToPromote()).isTrue();
        assertThat(FinDeTourMarker.parse("<!-- fin-de-tour: promotion=none; dette=0 -->")
                .orElseThrow().nothingToPromote()).isTrue();
        assertThat(FinDeTourMarker.parse("<!-- fin-de-tour: promotion=; dette=0 -->")
                .orElseThrow().nothingToPromote()).isTrue();
    }

    @Test
    @DisplayName("une dette qu'on ne sait pas compter rend le marqueur illisible")
    void anUncountableDebtMakesTheMarkerUnreadable() {
        // Conclure « zéro » sur une valeur qu'on n'a pas comprise serait le pire des deux mondes.
        assertThat(FinDeTourMarker.parse("<!-- fin-de-tour: promotion=aucune; dette=trois -->"))
                .isEmpty();
        assertThat(FinDeTourMarker.parse("<!-- fin-de-tour: promotion=aucune; dette=-1 -->")).isEmpty();
    }

    @Test
    @DisplayName("un marqueur sans dette est illisible : c'est la moitié qui bloque")
    void aMarkerWithoutDebtIsUnreadable() {
        assertThat(FinDeTourMarker.parse("<!-- fin-de-tour: promotion=aucune -->")).isEmpty();
    }

    @Test
    @DisplayName("une clef inconnue n'empêche pas de lire le reste")
    void anUnknownKeyIsIgnored() {
        assertThat(FinDeTourMarker.parse(
                "<!-- fin-de-tour: promotion=aucune; humeur=bonne; dette=0 -->")).isPresent();
    }

    @Test
    @DisplayName("absent, vide ou nul : rien à lire")
    void absentIsAbsent() {
        assertThat(FinDeTourMarker.parse(null)).isEmpty();
        assertThat(FinDeTourMarker.parse("   ")).isEmpty();
        assertThat(FinDeTourMarker.parse("Fait, sans rien déclarer.")).isEmpty();
        assertThat(FinDeTourMarker.parse("<!-- autre chose -->")).isEmpty();
    }

    @Test
    @DisplayName("la citation des éléments est bornée : une action corrective n'est pas un inventaire")
    void citedPromotionsAreBounded() {
        StringBuilder body = new StringBuilder("<!-- fin-de-tour: promotion=");
        for (int i = 0; i < 30; i++) {
            body.append("element-numero-").append(i).append(',');
        }
        body.append("; dette=0 -->");

        String cited = FinDeTourMarker.parse(body.toString()).orElseThrow().citedPromotions();

        assertThat(cited).endsWith("…");
        assertThat(cited.length()).isLessThan(FinDeTourMarker.MAX_CITED_CHARS + 40);
    }
}
