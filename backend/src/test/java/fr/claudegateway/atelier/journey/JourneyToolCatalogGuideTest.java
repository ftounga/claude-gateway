package fr.claudegateway.atelier.journey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** F-218 / SF-218-01 : le guide du parcours dit que le plan validé vaut feu vert, étapes EXTERNE comprises. */
class JourneyToolCatalogGuideTest {

    @Test
    void theValidatedPlanIsAGreenLightForExternalSteps() {
        assertThat(JourneyToolCatalog.GUIDE).contains("Le plan validé VAUT FEU VERT");
        // F-222 / SF-222-01 : un amendement dans l'objectif passe sans clic ; un « go » écrit valide le plan.
        assertThat(JourneyToolCatalog.GUIDE).contains("same_objective=true — l'amendement s'applique SANS CLIC")
                .contains("Dans le doute, c'est un changement d'objectif").contains("alors valider_plan")
                .doesNotContain("il repasse par la validation de l'utilisateur. Le plan validé");
        assertThat(JourneyToolCatalog.NAMES).contains(JourneyToolCatalog.VALIDATE_PLAN);
        assertThat(JourneyToolCatalog.GUIDE).contains("EXTERNE comprise (push, merge, apply, envoi), sans redemander");
        // La validation du plan elle-même (F-176) est gardée.
        assertThat(JourneyToolCatalog.GUIDE).contains("L'utilisateur valide le PREMIER plan d'un chantier d'un clic");
        // F-222 / SF-222-04 : version et mots exacts, jamais en autonome ; l'externe ne passe pas sans clic ; sommeil.
        assertThat(JourneyToolCatalog.GUIDE).contains("la version qu'il a lue (vN) et ses mots EXACTS de ce tour")
                .contains("jamais dans une course autonome ni une routine")
                .contains("Une étape EXTERNE ajoutée ou modifiée n'entre jamais sans clic")
                .contains("EN SOMMEIL : un chantier en Exécution sans aucun geste depuis 3 jours")
                .contains("si son PREMIER argument est un verbe de lecture");
        assertThat(JourneyToolCatalog.validatePlanDefinition().inputSchema().toString()).contains("version");
    }

    @Test
    void writtenCloseOfAVerifiedChantier() {
        // F-261 / SF-261-01 : « tu peux clore » écrit → cloturer_chantier, mots exacts, jamais d'initiative.
        assertThat(JourneyToolCatalog.GUIDE).contains("alors cloturer_chantier avec le chantier et ses mots EXACTS de ce "
                + "tour ; jamais de ta propre initiative ni dans une course autonome");
        assertThat(JourneyToolCatalog.NAMES).contains(JourneyToolCatalog.CLOSE_CHANTIER);
        assertThat(JourneyToolCatalog.closeChantierDefinition().inputSchema().toString())
                .contains("chantier", "user_words");
        assertThat(JourneyToolCatalog.closeChantierDefinition().inputSchema().get("required"))
                .isEqualTo(java.util.List.of("chantier", "user_words"));
    }

    @Test
    void outOfPlanCorridorAndHonestStop() {
        // F-274 / SF-274-01 : une demande explicite hors de tout plan, ou transverse, se fait ici, sans renvoi.
        assertThat(JourneyToolCatalog.GUIDE).contains("HORS CHANTIER : une demande EXPLICITE de l'utilisateur")
                .contains("appelle d'abord geste_hors_chantier")
                .contains("Ne renvoie JAMAIS l'utilisateur vers un autre fil").contains("avec chantier: N")
                .contains("ARRÊT : si tu ne fais pas ce qui est demandé, c'est TA décision")
                .contains("N'écris jamais que le tour « s'est terminé »");
        // L'amendement reste la règle pour ce que l'agent décide seul.
        assertThat(JourneyToolCatalog.GUIDE).contains("Une modification hors plan que tu décides est un AMENDEMENT");
        assertThat(JourneyToolCatalog.NAMES).contains(JourneyToolCatalog.OUT_OF_PLAN);
        assertThat(JourneyToolCatalog.outOfPlanDefinition().inputSchema().get("required"))
                .isEqualTo(java.util.List.of("user_words", "objet"));
    }

    @Test
    void readPlanOfAnotherChantier() {
        // F-274 / SF-274-02 : le plan d'un autre chantier se lit pour l'amender, sans le recopier de mémoire.
        assertThat(JourneyToolCatalog.GUIDE).contains("complet se lit avec lire_plan_chantier, pour l'amender");
        assertThat(JourneyToolCatalog.NAMES).contains(JourneyToolCatalog.READ_PLAN);
        assertThat(JourneyToolCatalog.readPlanDefinition().inputSchema().get("required"))
                .isEqualTo(java.util.List.of("chantier"));
    }
}
