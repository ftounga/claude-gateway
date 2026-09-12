package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * <b>L'origine du temps</b> (F-90 / SF-90-02) : sans elle, tout l'alignement est faux — et faux
 * <b>silencieusement</b>.
 */
class MomentTimelineTest {

    private static final Instant DEBUT = Instant.parse("2026-09-12T14:00:00Z");

    @Test
    void le_decalage_et_linstant_se_convertissent_dans_les_deux_sens() {
        MomentTimeline frise = MomentTimeline.given(DEBUT);

        assertEquals(DEBUT.plusSeconds(305), frise.at(305));
        assertEquals(305d, frise.offsetOf(DEBUT.plusSeconds(305)));
        assertEquals(-20d, frise.offsetOf(DEBUT.minusSeconds(20)),
                "ce qui précède le début de la vidéo donne un décalage NÉGATIF, pas zéro");
    }

    @Test
    void une_frise_sans_origine_ne_calcule_rien_et_se_dit_telle() {
        MomentTimeline frise = MomentTimeline.unknown();

        assertFalse(frise.isKnown());
        assertTrue(frise.describe().contains("Je ne sais pas"));
        // Aucune valeur de repli : une valeur de repli produirait des horodatages qui ont l'air
        // justes.
        assertThrows(IllegalStateException.class, () -> frise.at(10));
    }

    @Test
    void un_instant_absent_ne_devient_jamais_une_origine() {
        assertFalse(MomentTimeline.given(null).isKnown());
        assertFalse(MomentTimeline.fromMeeting(null).isKnown());
        assertFalse(MomentTimeline.fromMeeting(new TeamsMeeting("19:abc", "Sans début", null, null,
                "", List.of(), "", true, true, "")).isKnown());
    }

    @Test
    void lorigine_dit_dou_elle_vient() {
        TeamsMeeting meeting = new TeamsMeeting("19:abc", "Comité de migration", DEBUT, null, "",
                List.of(), "", true, true, "");

        assertTrue(MomentTimeline.fromMeeting(meeting).describe()
                .contains("le début de la réunion « Comité de migration » observé dans Teams"));
        assertTrue(MomentTimeline.given(DEBUT).describe().contains("que vous m'avez donné"));
    }

    @Test
    void le_decalage_deplace_lorigine_et_se_lit_en_toutes_lettres() {
        MomentTimeline frise = MomentTimeline.given(DEBUT).shiftedBy(-45);

        assertEquals(DEBUT.minusSeconds(45), frise.videoStartedAt());
        assertTrue(frise.describe().contains("-00:00:45"));
        assertEquals(MomentTimeline.given(DEBUT), MomentTimeline.given(DEBUT).shiftedBy(0),
                "un décalage nul ne change rien, et ne dit rien");
    }
}
