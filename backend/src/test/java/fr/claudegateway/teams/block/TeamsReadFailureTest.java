package fr.claudegateway.teams.block;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.teams.TeamsToolCatalog;

/**
 * <b>Le « zéro » d'une lecture Teams, classé et rendu visible</b> (F-89 / SF-89-11).
 *
 * <p>Ce test tient la <b>règle</b> : quelle phrase du runner devient quel motif, quelle gravité porte
 * quelle couleur, et ce qui autorise le repli. Le branchement dans la boucle est tenu ailleurs
 * ({@code AtelierChatServiceTeamsReadFailureTest}).</p>
 */
class TeamsReadFailureTest {

    // Les phrases sont celles que le runner écrit sur un zéro (ObservationDiagnostic / TeamsGapKind),
    // reproduites ici pour prouver qu'elles sont reconnues telles quelles, accents et apostrophes compris.
    private static final String SERVED =
            "Aucune conversation trouvée. Teams n'a rien servi d'utile depuis le rattachement "
                    + "(0 réponse, aucune qui porte ce contenu) : Teams affiche peut-être depuis son "
                    + "cache local, ou l'écran n'a pas été ouvert. Ouvrez l'écran voulu dans Teams, "
                    + "puis redemandez.";
    private static final String CLASSIFIED =
            "Le contenu est arrivé mais n'a pas été reconnu : Teams a répondu par des chemins que "
                    + "l'adaptateur ne reconnaît pas (3 réponses non classées depuis le rattachement).";
    private static final String SCREEN =
            "Teams a changé d'écran : la vue attendue n'était pas affichée.";
    private static final String SESSION =
            "L'onglet a atterri sur une page d'identification : session Microsoft à rouvrir.";

    @Test
    @DisplayName("« rien servi » : ambre, geste humain — ouvrir l'écran")
    void nothingServed() {
        Optional<TeamsReadFailure.Reason> reason =
                TeamsReadFailure.classify(TeamsToolCatalog.FIND_CONVERSATIONS, SERVED);
        assertThat(reason).contains(TeamsReadFailure.Reason.NOTHING_SERVED);
        assertThat(reason.get().severity()).isEqualTo(TeamsReadFailure.Severity.ATTENTION);
    }

    @Test
    @DisplayName("« non reconnu » : ambre, correctif runner — rouvrir n'y changera rien")
    void nothingClassified() {
        assertThat(TeamsReadFailure.classify(TeamsToolCatalog.READ_CONVERSATION, CLASSIFIED))
                .contains(TeamsReadFailure.Reason.NOTHING_CLASSIFIED);
    }

    @Test
    @DisplayName("« Teams a changé d'écran » : ambre")
    void screenChanged() {
        assertThat(TeamsReadFailure.classify(TeamsToolCatalog.MEETING_TRANSCRIPT, SCREEN))
                .contains(TeamsReadFailure.Reason.SCREEN_CHANGED);
    }

    @Test
    @DisplayName("« session Microsoft à rouvrir » : ROUGE, liaison rompue")
    void sessionExpired() {
        Optional<TeamsReadFailure.Reason> reason =
                TeamsReadFailure.classify(TeamsToolCatalog.SEARCH, SESSION);
        assertThat(reason).contains(TeamsReadFailure.Reason.SESSION_EXPIRED);
        assertThat(reason.get().severity()).isEqualTo(TeamsReadFailure.Severity.BROKEN);
    }

    @Test
    @DisplayName("une lecture qui a rendu du contenu n'est JAMAIS un échec")
    void aReadWithContentIsNeverAFailure() {
        String withContent = "3 conversations trouvées : Migration IAM, Comité, Support. "
                + "Ce qui n'a pas pu être lu : plafond de remontée atteint.";
        assertThat(TeamsReadFailure.classify(TeamsToolCatalog.FIND_CONVERSATIONS, withContent))
                .isEmpty();
    }

    @Test
    @DisplayName("un zéro d'un outil qui N'EST PAS une lecture n'est pas requalifié")
    void aNonReadingToolIsNeverClassified() {
        assertThat(TeamsReadFailure.classify("bash", SERVED)).isEmpty();
        assertThat(TeamsReadFailure.classify(TeamsToolCatalog.CAPTURE_START, SERVED)).isEmpty();
    }

    @Test
    @DisplayName("le bloc d'échec est display-only : titre, motif en sous-titre, motif machine, AUCUNE ligne")
    void theFailureCardIsDisplayOnly() {
        TeamsBlockCard card = TeamsReadFailure.card(TeamsReadFailure.Reason.NOTHING_SERVED);
        assertThat(card.kind()).isEqualTo(TeamsBlockCard.Kind.READ_FAILED);
        assertThat(card.title()).isEqualTo(TeamsReadFailure.TITLE);
        assertThat(card.subtitle()).isEqualTo(TeamsReadFailure.Reason.NOTHING_SERVED.motive());
        assertThat(card.reason()).isEqualTo("NOTHING_SERVED");
        assertThat(card.allLines()).isEmpty();
        assertThat(card.moments()).isEmpty();
    }

    @Test
    @DisplayName("le bandeau de repli est un bloc PROJECT_FALLBACK")
    void theFallbackBannerIsItsOwnKind() {
        TeamsBlockCard banner = TeamsReadFailure.fallbackBanner();
        assertThat(banner.kind()).isEqualTo(TeamsBlockCard.Kind.PROJECT_FALLBACK);
        assertThat(banner.title()).isEqualTo(TeamsReadFailure.FALLBACK_TITLE);
    }

    @Test
    @DisplayName("seul le geste explicite autorise le repli — Réessayer, non")
    void onlyTheExplicitGestureAuthorizesFallback() {
        assertThat(TeamsReadFailure.authorizesFallback(TeamsReadFailure.FALLBACK_PRECISION)).isTrue();
        assertThat(TeamsReadFailure.authorizesFallback("cherche dans le projet stp")).isTrue();
        assertThat(TeamsReadFailure.authorizesFallback(TeamsReadFailure.RETRY_PRECISION)).isFalse();
        assertThat(TeamsReadFailure.authorizesFallback("résume la réunion d'hier")).isFalse();
        assertThat(TeamsReadFailure.authorizesFallback(null)).isFalse();
    }

    @Test
    @DisplayName("les outils de fond du poste sont gardés ; les outils Teams et le plan restent ouverts")
    void projectAnswerToolsAreGuardedButTeamsToolsStayOpen() {
        assertThat(TeamsReadFailure.isProjectAnswerTool("bash")).isTrue();
        assertThat(TeamsReadFailure.isProjectAnswerTool("read_file")).isTrue();
        assertThat(TeamsReadFailure.isProjectAnswerTool("explore")).isTrue();
        assertThat(TeamsReadFailure.isProjectAnswerTool(TeamsToolCatalog.FIND_CONVERSATIONS)).isFalse();
        assertThat(TeamsReadFailure.isProjectAnswerTool("set_plan")).isFalse();
    }

    @Test
    @DisplayName("la règle molle est remplacée par une règle NON négociable, et la porte la relaie")
    void theSoftRuleIsReplacedByANonNegotiableOne() {
        assertThat(TeamsToolCatalog.READ_FAILURE_RULE)
                .contains("RÈGLE NON NÉGOCIABLE")
                .contains("Réessayer");
        // La consigne des outils de lecture porte toujours la règle durcie.
        assertThat(TeamsToolCatalog.NOTHING_RULE).contains(TeamsToolCatalog.READ_FAILURE_RULE);
    }
}
