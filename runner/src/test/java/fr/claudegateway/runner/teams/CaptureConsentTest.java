package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * <b>Deux usages, deux gestes</b> (F-91 / SF-91-01, garde-fou n° 2).
 *
 * <p>Ce que ces tests verrouillent n'est pas une validation de paramètre : c'est le seul endroit du
 * volet où le produit <b>refuse de faire ce qu'on lui demande</b>, et la raison pour laquelle il
 * refuse. « Si les deux avaient la même friction, elle deviendrait un réflexe et ne protégerait plus
 * rien. »</p>
 */
@DisplayName("F-91 / SF-91-01 — deux usages, deux gestes")
class CaptureConsentTest {

    @Nested
    @DisplayName("Son propre écran")
    class SelfScreen {

        @Test
        @DisplayName("démarre sans aucune friction : cela ne concerne personne d'autre")
        void selfScreenNeedsNoConfirmation() {
            CaptureConsent consent = CaptureConsent.require("self", null);

            assertEquals(CapturePurpose.SELF_SCREEN, consent.purpose());
            assertFalse(consent.concernsOthers());
            assertTrue(consent.describe().contains("ne concerne personne d'autre"));
        }

        @Test
        @DisplayName("accepte les mots que l'agent écrit vraiment")
        void acceptsBothLanguages() {
            assertEquals(CapturePurpose.SELF_SCREEN, CaptureConsent.require("écran", null).purpose());
            assertEquals(CapturePurpose.SELF_SCREEN, CaptureConsent.require("SCREEN", null).purpose());
        }
    }

    @Nested
    @DisplayName("Une réunion à plusieurs")
    class Meeting {

        @Test
        @DisplayName("sans confirmation : REFUS, et le refus dit ce qui n'est pas garanti")
        void meetingWithoutConfirmationIsRefused() {
            CaptureRefusedException refused = assertThrows(CaptureRefusedException.class,
                    () -> CaptureConsent.require("meeting", null));

            assertEquals(CaptureRefusedException.NOT_CONFIRMED, refused.code());
            assertTrue(refused.getMessage().contains("confirmiez avoir prévenu"));
            // Ce que le produit NE PEUT PAS garantir doit être écrit dans le refus lui-même.
            assertTrue(refused.remedy().contains("de vive voix"));
            assertTrue(refused.remedy().contains("pas à vous protéger"));
        }

        @Test
        @DisplayName("une confirmation à false vaut non, comme une confirmation absente")
        void falseIsNo() {
            assertThrows(CaptureRefusedException.class,
                    () -> CaptureConsent.require("meeting", false));
        }

        @Test
        @DisplayName("avec confirmation : accepté, et la phrase rappelle la limite")
        void meetingWithConfirmation() {
            CaptureConsent consent = CaptureConsent.require("meeting", true);

            assertTrue(consent.concernsOthers());
            assertTrue(consent.participantsInformed());
            assertTrue(consent.describe().contains("de vive voix"));
        }
    }

    @Nested
    @DisplayName("L'usage n'est jamais deviné")
    class NeverGuessed {

        @Test
        @DisplayName("usage absent : REFUS — et surtout pas un repli sur le moins exigeant")
        void missingPurposeIsRefused() {
            CaptureRefusedException refused = assertThrows(CaptureRefusedException.class,
                    () -> CaptureConsent.require(null, true));

            assertEquals(CaptureRefusedException.NO_PURPOSE, refused.code());
            assertTrue(refused.remedy().contains("Je ne le devine pas"));
        }

        @Test
        @DisplayName("usage inconnu : REFUS")
        void unknownPurposeIsRefused() {
            assertThrows(CaptureRefusedException.class,
                    () -> CaptureConsent.require("tout", true));
        }
    }
}
