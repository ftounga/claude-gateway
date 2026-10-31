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
    }
}
