package fr.claudegateway.teams.block;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>Ce qu'un bloc doit porter pour être posé</b> (F-89 / SF-89-02).
 *
 * <p>Ces tests tiennent la règle qui prime sur toutes les autres dans le volet Teams : <i>échouer
 * bruyamment, jamais à moitié faux</i>. Un compte rendu plausible et faux est pire qu'un compte rendu
 * qui refuse, parce qu'on décide dessus — et l'endroit où l'on peut encore l'empêcher est
 * l'émission, pas l'affichage.</p>
 */
class TeamsBlockCardsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TeamsBlockCard readCard(String text) {
        return TeamsBlockCards.read(TeamsBlockCard.Kind.MEETING_CARD, json(text), id -> true);
    }

    private static final String VALID_CARD = """
            {
              "title": "Comité de migration",
              "subtitle": "12 septembre, 45 min",
              "window": "du 5 au 12 septembre, 47 messages lus, 3 non reconnus",
              "gaps": ["3 messages non reconnus le 9 septembre"],
              "sections": [
                {"title": "Ce qu'on attend de vous", "lines": [
                  {"text": "Fournir le schéma réseau avant vendredi", "author": "Paul",
                   "at": "2026-09-12T14:32:00Z", "messageId": "m-1",
                   "webUrl": "https://teams.microsoft.com/l/message/m-1",
                   "certainty": "EXPLICITE"}]},
                {"title": "Décisions", "lines": [
                  {"text": "La migration passe au T3", "author": "Claire",
                   "at": "2026-09-12T14:40:00Z", "messageId": "m-2"}]}
              ]
            }
            """;

    @Nested
    @DisplayName("Un bloc valide garde tout ce qui le rend vérifiable")
    class ValidCard {

        @Test
        void everythingIsKept() {
            TeamsBlockCard card = readCard(VALID_CARD);

            assertThat(card.kind()).isEqualTo(TeamsBlockCard.Kind.MEETING_CARD);
            assertThat(card.title()).isEqualTo("Comité de migration");
            assertThat(card.window()).contains("47 messages lus");
            assertThat(card.gaps()).containsExactly("3 messages non reconnus le 9 septembre");
            assertThat(card.sections()).hasSize(2);
            // L'ordre est celui que l'agent a écrit : « ce qu'on attend de vous » d'abord, parce
            // que c'est ce qu'on cherche et qu'on ne doit pas avoir à faire défiler pour le trouver.
            assertThat(card.sections().get(0).title()).isEqualTo("Ce qu'on attend de vous");
            TeamsBlockCard.Line first = card.sections().get(0).lines().get(0);
            assertThat(first.author()).isEqualTo("Paul");
            assertThat(first.at()).isEqualTo("2026-09-12T14:32:00Z");
            assertThat(first.webUrl()).isEqualTo("https://teams.microsoft.com/l/message/m-1");
            assertThat(first.certainty()).isEqualTo(TeamsBlockCard.Certainty.EXPLICITE);
        }

        @Test
        @DisplayName("une certitude absente vaut « à confirmer » : le doute n'affirme jamais")
        void missingCertaintyMeansToConfirm() {
            TeamsBlockCard card = readCard(VALID_CARD);

            assertThat(card.sections().get(1).lines().get(0).certainty())
                    .isEqualTo(TeamsBlockCard.Certainty.A_CONFIRMER);
        }

        @Test
        @DisplayName("une ligne peut n'avoir que son lien, ou que son identifiant — pas rien")
        void oneSourceIsEnough() {
            assertThat(readCard(VALID_CARD).allLines()).allMatch(TeamsBlockCard.Line::hasSource);
        }
    }

    @Nested
    @DisplayName("Ce qui fait refuser le bloc ENTIER — on n'affiche jamais la moitié d'un compte rendu")
    class Rejections {

        @Test
        @DisplayName("une ligne sans source : LA règle de valeur du volet")
        void aLineWithoutASourceIsRefused() {
            assertThatThrownBy(() -> readCard("""
                    {"title": "T", "window": "W", "gaps": [],
                     "sections": [{"title": "S", "lines": [{"text": "On m'a demandé le schéma"}]}]}
                    """))
                    .isInstanceOf(TeamsBlockRejectedException.class)
                    .hasMessageContaining("n'a pas de source")
                    .hasMessageContaining("messageId");
        }

        @Test
        void anEmptyLineIsRefused() {
            assertThatThrownBy(() -> readCard("""
                    {"title": "T", "window": "W", "gaps": [],
                     "sections": [{"title": "S", "lines": [{"text": "  ", "messageId": "m-1"}]}]}
                    """))
                    .isInstanceOf(TeamsBlockRejectedException.class)
                    .hasMessageContaining("vide");
        }

        @Test
        @DisplayName("sans la fenêtre réellement lue (D4), le compte rendu laisse croire qu'il couvre tout")
        void aMissingWindowIsRefused() {
            assertThatThrownBy(() -> readCard("""
                    {"title": "T", "gaps": [],
                     "sections": [{"title": "S", "lines": [{"text": "x", "messageId": "m-1"}]}]}
                    """))
                    .isInstanceOf(TeamsBlockRejectedException.class)
                    .hasMessageContaining("window");
        }

        @Test
        @DisplayName("les manques NON DÉCLARÉS sont refusés : le silence n'a pas de valeur par défaut")
        void undeclaredGapsAreRefused() {
            assertThatThrownBy(() -> readCard("""
                    {"title": "T", "window": "W",
                     "sections": [{"title": "S", "lines": [{"text": "x", "messageId": "m-1"}]}]}
                    """))
                    .isInstanceOf(TeamsBlockRejectedException.class)
                    .hasMessageContaining("gaps");
        }

        @Test
        @DisplayName("une liste vide de manques est acceptée — elle dit « aucun manque signalé »")
        void anEmptyGapListIsAccepted() {
            assertThat(readCard("""
                    {"title": "T", "window": "W", "gaps": [],
                     "sections": [{"title": "S", "lines": [{"text": "x", "messageId": "m-1"}]}]}
                    """).gaps()).isEmpty();
        }

        @Test
        @DisplayName("une certitude inventée est refusée : c'est le signe d'une échelle imaginée")
        void anUnknownCertaintyIsRefused() {
            assertThatThrownBy(() -> readCard("""
                    {"title": "T", "window": "W", "gaps": [],
                     "sections": [{"title": "S", "lines": [
                        {"text": "x", "messageId": "m-1", "certainty": "PROBABLE_82"}]}]}
                    """))
                    .isInstanceOf(TeamsBlockRejectedException.class)
                    .hasMessageContaining("jamais un score");
        }

        @Test
        @DisplayName("un champ trop long est REFUSÉ, jamais tronqué")
        void tooLongIsRefusedNeverTruncated() {
            String long500 = "x".repeat(TeamsBlockCards.MAX_TEXT_CHARS + 1);
            assertThatThrownBy(() -> readCard("""
                    {"title": "T", "window": "W", "gaps": [],
                     "sections": [{"title": "S", "lines": [{"text": "%s", "messageId": "m-1"}]}]}
                    """.formatted(long500)))
                    .isInstanceOf(TeamsBlockRejectedException.class)
                    .hasMessageContaining("Rien n'est coupé");
        }

        @Test
        void tooManyLinesIsRefusedWithItsBound() {
            StringBuilder lines = new StringBuilder();
            for (int i = 0; i <= TeamsBlockCards.MAX_LINES; i++) {
                lines.append(i > 0 ? "," : "")
                        .append("{\"text\":\"ligne\",\"messageId\":\"m-").append(i).append("\"}");
            }
            assertThatThrownBy(() -> readCard("""
                    {"title": "T", "window": "W", "gaps": [],
                     "sections": [{"title": "S", "lines": [%s]}]}
                    """.formatted(lines)))
                    .isInstanceOf(TeamsBlockRejectedException.class)
                    .hasMessageContaining(String.valueOf(TeamsBlockCards.MAX_LINES))
                    .hasMessageContaining("engagement perdu");
        }

        @Test
        void aSectionWithoutATitleIsRefused() {
            assertThatThrownBy(() -> readCard("""
                    {"title": "T", "window": "W", "gaps": [],
                     "sections": [{"lines": [{"text": "x", "messageId": "m-1"}]}]}
                    """))
                    .isInstanceOf(TeamsBlockRejectedException.class)
                    .hasMessageContaining("titre");
        }
    }

    @Nested
    @DisplayName("La liste : une seule suite de lignes, mêmes exigences")
    class Lists {

        @Test
        void aListIsOneSection() {
            TeamsBlockCard card = TeamsBlockCards.read(TeamsBlockCard.Kind.LIST, json("""
                    {"title": "Vos engagements", "window": "7 derniers jours", "gaps": [],
                     "lines": [
                       {"text": "Envoyer le devis", "messageId": "m-9", "certainty": "EXPLICITE"},
                       {"text": "Relancer Paul", "messageId": "m-10"}]}
                    """), id -> true);

            assertThat(card.kind()).isEqualTo(TeamsBlockCard.Kind.LIST);
            assertThat(card.sections()).hasSize(1);
            assertThat(card.allLines()).hasSize(2);
            assertThat(card.allLines().get(1).certainty())
                    .isEqualTo(TeamsBlockCard.Certainty.A_CONFIRMER);
        }
    }

    @Nested
    @DisplayName("Les moments : c'est l'horodatage qui pose l'image à côté de la phrase")
    class Moments {

        private TeamsBlockCard readMoments(String text, java.util.function.Predicate<String> known) {
            return TeamsBlockCards.read(TeamsBlockCard.Kind.MOMENTS, json(text), known);
        }

        @Test
        void aMomentKeepsItsImageAndItsQuote() {
            TeamsBlockCard card = readMoments("""
                    {"title": "Comité", "window": "réunion du 12 septembre", "gaps": [],
                     "moments": [{"at": "2026-09-12T14:32:00Z",
                                  "quote": "Le planning décale au T3", "speaker": "Claire",
                                  "imageId": "abc123",
                                  "webUrl": "https://teams.microsoft.com/l/t?at=1932"}]}
                    """, id -> true);

            assertThat(card.moments()).hasSize(1);
            assertThat(card.moments().get(0).imageId()).isEqualTo("abc123");
            assertThat(card.moments().get(0).quote()).isEqualTo("Le planning décale au T3");
        }

        @Test
        void aMomentWithoutATimeIsRefused() {
            assertThatThrownBy(() -> readMoments("""
                    {"title": "T", "window": "W", "gaps": [],
                     "moments": [{"quote": "quelque chose"}]}
                    """, id -> true))
                    .isInstanceOf(TeamsBlockRejectedException.class)
                    .hasMessageContaining("pas d'heure");
        }

        @Test
        void aMomentWithoutAQuoteIsRefused() {
            assertThatThrownBy(() -> readMoments("""
                    {"title": "T", "window": "W", "gaps": [],
                     "moments": [{"at": "2026-09-12T14:32:00Z"}]}
                    """, id -> true))
                    .isInstanceOf(TeamsBlockRejectedException.class)
                    .hasMessageContaining("citation");
        }

        @Test
        @DisplayName("une image inconnue fait refuser : on n'invente pas un identifiant d'image")
        void anUnknownImageIsRefused() {
            assertThatThrownBy(() -> readMoments("""
                    {"title": "T", "window": "W", "gaps": [],
                     "moments": [{"at": "2026-09-12T14:32:00Z", "quote": "x", "imageId": "nope"}]}
                    """, id -> false))
                    .isInstanceOf(TeamsBlockRejectedException.class)
                    .hasMessageContaining("n'existe pas pour ce terminal");
        }

        @Test
        @DisplayName("un moment SANS image reste un moment : la phrase et l'heure suffisent")
        void aMomentWithoutAnImageIsFine() {
            TeamsBlockCard card = readMoments("""
                    {"title": "T", "window": "W", "gaps": [],
                     "moments": [{"at": "2026-09-12T14:32:00Z", "quote": "x"}]}
                    """, id -> {
                        throw new AssertionError("aucune image nommée : rien à vérifier");
                    });

            assertThat(card.moments().get(0).imageId()).isEmpty();
        }
    }

    @Nested
    @DisplayName("F-91 — la mention d'un enregistrement local")
    class RecordingNotice {

        @Test
        @DisplayName("la mention est lue et rendue : la trace voyage jusqu'au compte rendu")
        void theNoticeIsCarried() {
            TeamsBlockCard card = readCard("""
                    {"title": "T", "window": "W", "gaps": [],
                     "recordingNotice": "Ce compte rendu provient d'un ENREGISTREMENT LOCAL.",
                     "sections": [{"title": "S", "lines": [
                       {"text": "x", "messageId": "m-1"}]}]}
                    """);

            assertThat(card.recordingNotice())
                    .isEqualTo("Ce compte rendu provient d'un ENREGISTREMENT LOCAL.");
            assertThat(card.fromLocalRecording()).isTrue();
        }

        @Test
        @DisplayName("absente : acceptée — la plupart des blocs ne viennent pas d'une capture")
        void anAbsentNoticeIsFine() {
            TeamsBlockCard card = readCard(VALID_CARD);

            assertThat(card.recordingNotice()).isEmpty();
            assertThat(card.fromLocalRecording()).isFalse();
        }

        @Test
        @DisplayName("trop longue : REFUS, comme tout le reste — on ne tronque jamais")
        void anOverlongNoticeIsRefused() {
            assertThatThrownBy(() -> readCard("""
                    {"title": "T", "window": "W", "gaps": [], "recordingNotice": "%s",
                     "sections": [{"title": "S", "lines": [
                       {"text": "x", "messageId": "m-1"}]}]}
                    """.formatted("m".repeat(TeamsBlockCards.MAX_NOTICE_CHARS + 1))))
                    .isInstanceOf(TeamsBlockRejectedException.class);
        }

        @Test
        @DisplayName("un bloc d'avant F-91 se relit sans mention, et sans casser")
        void anOlderCardStillReads() {
            TeamsBlockCard card = new TeamsBlockCard(TeamsBlockCard.Kind.LIST, "T", "", "W",
                    java.util.List.of(), java.util.List.of(), java.util.List.of());

            assertThat(card.recordingNotice()).isEmpty();
            assertThat(card.fromLocalRecording()).isFalse();
        }
    }

    @Nested
    @DisplayName("Pas de score, nulle part")
    class NoScore {

        @Test
        @DisplayName("le modèle n'a AUCUN champ numérique de confiance")
        void theModelCarriesNoNumber() {
            java.util.List<String> numericFields = java.util.Arrays
                    .stream(TeamsBlockCard.Line.class.getRecordComponents())
                    .filter(component -> Number.class.isAssignableFrom(box(component.getType())))
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();

            assertThat(numericFields)
                    .as("un chiffre donnerait une apparence de mesure à une interprétation")
                    .isEmpty();
        }

        private static Class<?> box(Class<?> type) {
            if (type == int.class) {
                return Integer.class;
            }
            if (type == long.class) {
                return Long.class;
            }
            if (type == double.class) {
                return Double.class;
            }
            if (type == float.class) {
                return Float.class;
            }
            return type;
        }
    }
}
