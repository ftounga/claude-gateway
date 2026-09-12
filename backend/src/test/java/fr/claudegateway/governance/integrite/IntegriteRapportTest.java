package fr.claudegateway.governance.integrite;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-95 / SF-95-01 — <b>deux niveaux, et ils ne se mélangent pas</b>.
 *
 * <p>Ce que ces tests protègent : qu'un avertissement ne puisse jamais se faire passer pour un
 * refus, et qu'un rapport dont on n'a rien lu ne se présente jamais comme un rapport sain.</p>
 */
class IntegriteRapportTest {

    @Test
    @DisplayName("les deux niveaux se rendent séparément")
    void levelsAreReturnedSeparately() {
        IntegriteRapport rapport = IntegriteRapport.de(List.of(
                IntegriteConstat.carteAbsente("reseau.md"),
                IntegriteConstat.detteEnCours("migration-dns", 2, "acces.md")));

        assertThat(rapport.erreurs()).hasSize(1);
        assertThat(rapport.avertissements()).hasSize(1);
        assertThat(rapport.bloque()).isTrue();
    }

    @Test
    @DisplayName("les avertissements seuls ne bloquent jamais")
    void warningsAloneNeverBlock() {
        IntegriteRapport rapport = IntegriteRapport.de(List.of(
                IntegriteConstat.detteEnCours("migration-dns", 1, "acces.md"),
                IntegriteConstat.lienMort("acces.md", "vieux-sujet/notes.md")));

        assertThat(rapport.bloque()).isFalse();
        assertThat(rapport.correction()).contains(IntegriteRapport.ENTETE_AVERTISSEMENTS);
        assertThat(rapport.correction()).doesNotContain(IntegriteRapport.ENTETE_ERREURS);
    }

    @Test
    @DisplayName("la correction cite les erreurs avant les avertissements, sous deux intitulés")
    void errorsComeFirstUnderTheirOwnHeading() {
        IntegriteRapport rapport = IntegriteRapport.de(List.of(
                IntegriteConstat.detteEnCours("migration-dns", 1, "acces.md"),
                IntegriteConstat.carteAbsente("reseau.md")));

        String correction = rapport.correction();

        assertThat(correction.indexOf(IntegriteRapport.ENTETE_ERREURS))
                .isLessThan(correction.indexOf(IntegriteRapport.ENTETE_AVERTISSEMENTS));
        assertThat(correction).contains("ne bloquent pas");
        assertThat(correction).contains("carte/fichier-absent").contains("dette/en-cours");
    }

    @Test
    @DisplayName("au-delà de la borne, les constats non cités sont annoncés — jamais tus")
    void findingsBeyondTheQuotaAreAnnounced() {
        List<IntegriteConstat> constats = new ArrayList<>();
        for (int i = 0; i < IntegriteRapport.MAX_ERREURS_CITEES + 2; i++) {
            constats.add(IntegriteConstat.carteAbsente("fichier-" + i + ".md"));
        }

        String correction = IntegriteRapport.de(constats).correction();

        assertThat(correction).contains("fichier-0.md").contains("fichier-2.md");
        assertThat(correction).doesNotContain("fichier-4.md");
        assertThat(correction).contains("et 2 autres constats");
    }

    @Test
    @DisplayName("la correction tient dans le verdict de F-50")
    void theCorrectionFitsInACheckpointVerdict() {
        List<IntegriteConstat> constats = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            constats.add(IntegriteConstat.carteAbsente("fichier-" + i + ".md"));
            constats.add(IntegriteConstat.detteEnCours("projet-" + i, 3, "acces.md, reseau.md"));
        }

        assertThat(IntegriteRapport.de(constats).correction().length())
                .isLessThanOrEqualTo(IntegriteRapport.MAX_CORRECTION_CHARS);
    }

    @Test
    @DisplayName("un rapport sain ne dit rien, et un rapport silencieux n'est pas un rapport sain")
    void silenceIsNotHealth() {
        assertThat(IntegriteRapport.sain().correction()).isEmpty();
        assertThat(IntegriteRapport.sain().rienASignaler()).isTrue();

        IntegriteRapport silencieux = IntegriteRapport.silencieux();
        assertThat(silencieux.inspecte()).isFalse();
        assertThat(silencieux.rienASignaler()).isFalse();
        assertThat(silencieux.bloque()).isFalse();
    }

    @Test
    @DisplayName("une liste nulle ne lève pas, et le rapport reste lisible")
    void aNullListIsSurvivable() {
        IntegriteRapport rapport = new IntegriteRapport(null, true);

        assertThat(rapport.constats()).isEmpty();
        assertThat(rapport.par(IntegriteNiveau.ERREUR)).isEmpty();
        assertThat(rapport.correction()).isEmpty();
    }
}
