package fr.claudegateway.atelier.journey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** F-218 / SF-218-01 : le guide du parcours dit que le plan validé vaut feu vert, étapes EXTERNE comprises. */
class JourneyToolCatalogGuideTest {

    @Test
    void theValidatedPlanIsAGreenLightForExternalSteps() {
        assertThat(JourneyToolCatalog.GUIDE).contains("Le plan validé VAUT FEU VERT");
        assertThat(JourneyToolCatalog.GUIDE).contains("EXTERNE comprise (push, merge, apply, envoi), sans redemander");
        // La validation du plan elle-même (F-176) est gardée.
        assertThat(JourneyToolCatalog.GUIDE).contains("L'utilisateur valide le plan d'un clic");
    }
}
