package fr.claudegateway.governance.integrite;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.governance.GovernanceHostRule;

/**
 * F-95 / SF-95-01 — <b>chaque message porte son action corrective</b>.
 *
 * <p>C'est l'exigence littérale de la feature, et son motif est écrit dans le prompt d'origine : le
 * message est lu par un <b>modèle qui doit corriger</b>, pas par un humain qui doit comprendre. Ces
 * tests l'épinglent règle par règle — un constat qui se contenterait du constat passerait ici, et
 * nulle part ailleurs.</p>
 */
class IntegriteConstatTest {

    /** Un geste, c'est un verbe à l'impératif. Un constat seul n'en contient aucun. */
    private static final Pattern GESTE = Pattern.compile(
            "(?i)(reprends|crée|rends|déplace|remonte|promeus|coche|corrige|retire|vérifie|supprime|clos)");

    @Test
    @DisplayName("chaque fabrique nomme la cible et dit le geste")
    void everyFactoryNamesTheTargetAndTheGesture() {
        List<IntegriteConstat> constats = List.of(
                IntegriteConstat.carteAbsente("reseau.md"),
                IntegriteConstat.carteSansStructure("acces.md"),
                IntegriteConstat.indexSurcharge("README.md", 72),
                IntegriteConstat.lienMort("acces.md", "migration-dns/STATE.md"),
                IntegriteConstat.stateAbsent("migration-dns"),
                IntegriteConstat.detteALaCloture("migration-dns", 2, "acces.md, reseau.md"),
                IntegriteConstat.detteEnCours("migration-dns", 1, "acces.md"),
                IntegriteConstat.projetDepotGit("portail-client"),
                IntegriteConstat.noteHorsDepot("repos/portail", List.of("notes.md")));

        assertThat(constats).allSatisfy(constat -> {
            assertThat(constat.message()).isNotBlank();
            assertThat(constat.message()).contains(constat.cible());
            assertThat(GESTE.matcher(constat.message()).find())
                    .as("le message de « %s » porte un geste : %s", constat.regle().id(),
                            constat.message())
                    .isTrue();
            assertThat(constat.message().length())
                    .isLessThanOrEqualTo(IntegriteConstat.MAX_MESSAGE_CHARS);
        });
    }

    @Test
    @DisplayName("toutes les règles ont une fabrique — aucune n'est déclarée sans message")
    void everyRuleHasAFactory() {
        List<IntegriteRegle> couvertes = List.of(
                IntegriteConstat.carteAbsente("a.md").regle(),
                IntegriteConstat.carteSansStructure("a.md").regle(),
                IntegriteConstat.carteNonDeclaree("enjeux.md").regle(),
                IntegriteConstat.indexSurcharge("a.md", 61).regle(),
                IntegriteConstat.lienMort("a.md", "b/c").regle(),
                IntegriteConstat.stateAbsent("p").regle(),
                IntegriteConstat.detteALaCloture("p", 1, "a.md").regle(),
                IntegriteConstat.detteEnCours("p", 1, "a.md").regle(),
                IntegriteConstat.projetDepotGit("p").regle(),
                IntegriteConstat.noteHorsDepot("repos/d", List.of("n.md")).regle());

        assertThat(couvertes).containsExactlyInAnyOrder(IntegriteRegle.values());
    }

    @Test
    @DisplayName("les deux règles de clonage reprennent le geste de F-93, sans le réécrire")
    void cloningRulesReuseTheirStatementFromGovernanceHostRule() {
        assertThat(IntegriteConstat.projetDepotGit("sujet-x").message())
                .contains(GovernanceHostRule.PROJET_SANS_GIT.correction());
        assertThat(IntegriteConstat.noteHorsDepot("repos/portail", List.of("notes.md")).message())
                .contains(GovernanceHostRule.NOTE_HORS_DEPOT.correction());
        assertThat(IntegriteRegle.PROJET_DEPOT_GIT.id())
                .isEqualTo(GovernanceHostRule.PROJET_SANS_GIT.id());
        assertThat(IntegriteRegle.NOTE_HORS_DEPOT.id())
                .isEqualTo(GovernanceHostRule.NOTE_HORS_DEPOT.id());
    }

    @Test
    @DisplayName("la dette distingue la clôture de l'en-cours — l'une bloque, l'autre informe")
    void debtSeparatesClosureFromWork() {
        assertThat(IntegriteConstat.detteALaCloture("p", 1, "acces.md").niveau())
                .isEqualTo(IntegriteNiveau.ERREUR);
        assertThat(IntegriteConstat.detteEnCours("p", 1, "acces.md").niveau())
                .isEqualTo(IntegriteNiveau.AVERTISSEMENT);
    }

    @Test
    @DisplayName("plusieurs notes non versionnées sont toutes citées")
    void severalUntrackedNotesAreAllNamed() {
        IntegriteConstat constat =
                IntegriteConstat.noteHorsDepot("repos/portail", List.of("notes.md", "todo.md"));

        assertThat(constat.message()).contains("notes.md").contains("todo.md");
    }

    @Test
    @DisplayName("un message trop long est coupé, jamais vidé")
    void anOverlongMessageIsTruncatedNeverEmptied() {
        String tresLong = "x".repeat(IntegriteConstat.MAX_MESSAGE_CHARS + 200);

        IntegriteConstat constat =
                new IntegriteConstat(IntegriteRegle.CARTE_ABSENTE, "a.md", tresLong);

        assertThat(constat.message()).hasSize(IntegriteConstat.MAX_MESSAGE_CHARS);
        assertThat(constat.message()).endsWith("…");
    }

    @Test
    @DisplayName("un message vide ne produit jamais un constat muet")
    void anEmptyMessageStillSaysSomething() {
        IntegriteConstat constat = new IntegriteConstat(IntegriteRegle.CARTE_ABSENTE, "a.md", "  ");

        assertThat(constat.message()).isNotBlank();
    }
}
