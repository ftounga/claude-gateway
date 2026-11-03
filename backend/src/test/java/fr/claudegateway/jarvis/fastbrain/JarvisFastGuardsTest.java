package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** F-235 / SF-235-03 — les garde-fous déterministes du cerveau rapide (D9, D11). */
class JarvisFastGuardsTest {

    private static final String CONTEXT = """
            Maintenant : dimanche 1 novembre 2026, 14:32
            Univers (état à 14:32) :
            - client Edenred (connecté) : sujet Edenred-Run (id 1234) — chantier 3 « Migration Keycloak », 4/7, étape : tests
            À envoyer (2) : compte rendu à Paul Martin
            Fil : ticket EDN-212 bloqué depuis 1 200 minutes, 15 % restant, budget 3 400 €
            """;

    @Test
    void numbersMustBeGrounded() {
        assertThat(JarvisFastGuards.ungroundedFact("Le chantier 3 en est à 4 étapes sur 7.", CONTEXT, "")).isNull();
        assertThat(JarvisFastGuards.ungroundedFact("Il reste 15 % à faire.", CONTEXT, "")).isNull();
        assertThat(JarvisFastGuards.ungroundedFact("Le budget est de 3 400 €.", CONTEXT, "")).isNull();
        assertThat(JarvisFastGuards.ungroundedFact("Bloqué depuis 1200 minutes.", CONTEXT, "")).isNull();
        assertThat(JarvisFastGuards.ungroundedFact("Il reste 37 tickets.", CONTEXT, "")).isEqualTo("37");
        assertThat(JarvisFastGuards.ungroundedFact("Le budget est de 9 €.", CONTEXT, "")).isNotNull();
        assertThat(JarvisFastGuards.ungroundedFact("La réunion est à 16 h 30.", CONTEXT, "")).isNotNull();
        assertThat(JarvisFastGuards.ungroundedFact("La réunion est à 16 h 30.", CONTEXT, "réunion à 16h30"))
                .isNull();
    }

    /** F-237 / SF-237-15 — banc PIECES en prod : une heure en fin de ligne n'était jamais reconnue. */
    @Test
    void timesAtTheEndOfALineAreGroundedAndLinesAreNeverGluedTogether() {
        String screen = """
                ## Écran
                1. vidéo — « Démo du déploiement » — mardi 09:55
                2. vidéo — « Cours 2 : Terraform » — lundi 14:00
                3. vidéo — « Cours 3 : Observabilité » — lundi 16:20""";
        assertThat(JarvisFastGuards.ungroundedFact("Voici « Cours 2 : Terraform », de lundi 14:00.", screen, ""))
                .isNull();
        assertThat(JarvisFastGuards.ungroundedFact("Voici la démo, de mardi 09:55.", screen, "")).isNull();
        assertThat(JarvisFastGuards.ungroundedFact("Voici la démo, produite ce matin à 9h55.", screen, "")).isNull();
        assertThat(JarvisFastGuards.ungroundedFact("Il y a 14003 vues.", screen, "")).isEqualTo("14003");
        assertThat(JarvisFastGuards.ungroundedFact("La réunion est à 17:30.", screen, "")).isNotNull();
    }

    @Test
    void ticketKeysMustBeGrounded() {
        assertThat(JarvisFastGuards.ungroundedFact("Le ticket EDN-212 est bloqué.", CONTEXT, "")).isNull();
        assertThat(JarvisFastGuards.ungroundedFact("Le ticket EDN-999 est bloqué.", CONTEXT, "")).isNotNull();
    }

    @Test
    void properNamesMustBeGroundedInTheContextOrTheWords() {
        assertThat(JarvisFastGuards.ungroundedFact("Le compte rendu est pour Paul Martin.", CONTEXT, "")).isNull();
        assertThat(JarvisFastGuards.ungroundedFact("Sur Edenred, tout va bien.", CONTEXT, "")).isNull();
        assertThat(JarvisFastGuards.ungroundedFact("Le compte rendu est pour Sophie.", CONTEXT, ""))
                .isEqualTo("Sophie");
        assertThat(JarvisFastGuards.ungroundedFact("Le compte rendu est pour Sophie.", CONTEXT, "et Sophie ?"))
                .isNull();
        assertThat(JarvisFastGuards.ungroundedFact("Bien, Monsieur, je vous le dis lundi.", CONTEXT, "")).isNull();
    }

    @Test
    void fillersAreOnlyBlockedWhenAlone() {
        assertThat(JarvisFastGuards.isFillerAlone("Je m'en occupe.")).isTrue();
        assertThat(JarvisFastGuards.isFillerAlone("Je m'en occupe, Monsieur.")).isTrue();
        assertThat(JarvisFastGuards.isFillerAlone("Très bien.")).isTrue();
        assertThat(JarvisFastGuards.isFillerAlone("Une seconde…")).isTrue();
        assertThat(JarvisFastGuards.isFillerAlone("Laissez-moi vérifier.")).isTrue();
        assertThat(JarvisFastGuards.isFillerAlone("Je t'écoute.")).isTrue();
        assertThat(JarvisFastGuards.isFillerAlone("Je m'en occupe : je relance Paul ce soir.")).isFalse();
        assertThat(JarvisFastGuards.isFillerAlone("Très bien, le chantier 3 est aux tests.")).isFalse();
    }

    @Test
    void repeatsAreDetectedAfterNormalisation() {
        List<String> recent = List.of("Le chantier 3 en est aux tests.", "Avec plaisir, Monsieur.");
        assertThat(JarvisFastGuards.isRepeat("le chantier 3 en est aux tests !", recent)).isTrue();
        assertThat(JarvisFastGuards.isRepeat("Avec plaisir, Monsieur.", recent)).isTrue();
        assertThat(JarvisFastGuards.isRepeat("Le chantier 4 en est aux tests.", recent)).isFalse();
    }

    @Test
    void forcedDelegationIsAClosedList() {
        assertThat(JarvisFastGuards.forcesDelegation("Vérifie.")).isTrue();
        assertThat(JarvisFastGuards.forcesDelegation("Jarvis, demande à Opus")).isTrue();
        assertThat(JarvisFastGuards.forcesDelegation("Réfléchis-y")).isTrue();
        assertThat(JarvisFastGuards.forcesDelegation("Non, c'est faux.")).isTrue();
        assertThat(JarvisFastGuards.forcesDelegation("Vérifie, s'il vous plaît")).isTrue();
        assertThat(JarvisFastGuards.forcesDelegation("Vérifie les tickets d'Edenred")).isFalse();
        assertThat(JarvisFastGuards.forcesDelegation("Où en est le chantier ?")).isFalse();
    }

    @Test
    void toolTextIsDetected() {
        assertThat(JarvisFastGuards.looksLikeToolCall("deleguer(demande=\"x\")")).isTrue();
        assertThat(JarvisFastGuards.looksLikeToolCall("{\"demande\": \"x\"}")).isTrue();
        assertThat(JarvisFastGuards.looksLikeToolCall("<tool_use>")).isTrue();
        assertThat(JarvisFastGuards.looksLikeToolCall("Je délègue la demande à Opus.")).isFalse();
    }

    @Test
    void splitsCompleteSentencesAndKeepsTheRest() {
        JarvisFastGuards.Split split = JarvisFastGuards.split("Bonjour, Monsieur. Le chantier 3 en est");
        assertThat(split.sentences()).containsExactly("Bonjour, Monsieur.");
        assertThat(split.rest()).isEqualTo(" Le chantier 3 en est");
    }

    // ---------------------------------------------------------------- F-239 / SF-239-02

    @Test
    void f239InvitationsToSpeakAreRecognisedAloneOrAsAConclusion() {
        assertThat(JarvisFastGuards.isListeningInvitation(
                "Je vous écoute pour la suite de la journée.")).isTrue();
        assertThat(JarvisFastGuards.isListeningInvitation("Je vous écoute, Monsieur."))
                .isTrue();
        assertThat(JarvisFastGuards.isListeningInvitation(
                "Je reste à votre disposition si besoin.")).isTrue();
        assertThat(JarvisFastGuards.isListeningInvitation("N'hésitez pas.")).isTrue();
        assertThat(JarvisFastGuards.isListeningInvitation(
                "Paul vous écoute demain à 10 h.")).isFalse();
        assertThat(JarvisFastGuards.isListeningInvitation(
                "Le chantier en est aux tests.")).isFalse();
    }

    @Test
    void f240_05ClosingPromptsAreRecognisedButAQuestionAskedForIsNot() {
        String chat = "Journée chargée aujourd'hui.";
        assertThat(JarvisFastGuards.isClosingPrompt("Dites-moi par où vous souhaitez commencer.", chat)).isTrue();
        assertThat(JarvisFastGuards.isClosingPrompt("Dites-moi quand vous souhaitez que je prépare l'envoi.", chat))
                .isTrue();
        assertThat(JarvisFastGuards.isClosingPrompt("Dites-moi lequel, Monsieur ?", chat)).isFalse();
        assertThat(JarvisFastGuards.isClosingPrompt("Je reste attentif à ce qui se présente.", chat)).isTrue();
        assertThat(JarvisFastGuards.isClosingPrompt("Par quoi souhaitez-vous commencer ?", chat)).isTrue();
        assertThat(JarvisFastGuards.isClosingPrompt("Par où voulez-vous commencer, Monsieur ?", chat)).isTrue();
        assertThat(JarvisFastGuards.isClosingPrompt("Par quoi souhaitez-vous commencer ?", "Commençons.")).isFalse();
        assertThat(JarvisFastGuards.isClosingPrompt("Par où voulez-vous commencer ?", "On s'y met ?")).isFalse();
        assertThat(JarvisFastGuards.isClosingPrompt("Le chantier 1 commence demain.", "Où en est Orion ?")).isFalse();
        assertThat(JarvisFastGuards.isListeningInvitation("Dites-moi par où vous souhaitez commencer.")).isFalse();
    }

    @Test
    void f240_05TheLastOpusAnswerAndItsTicketsAreRead() {
        String context = "Univers : ORN-99\n- 09:10 Monsieur : Regarde.\n- 09:12 Opus : ORN-41 puis ORN-44.\n"
                + "- 09:13 Monsieur : Bien.\n- 09:14 Opus : LDIC-223 et LDIC-223.";
        assertThat(JarvisFastGuards.lastOpusAnswer(context)).isEqualTo("LDIC-223 et LDIC-223.");
        assertThat(JarvisFastGuards.lastOpusAnswer("Maintenant : lundi")).isNull();
        assertThat(JarvisFastGuards.lastOpusAnswer(null)).isNull();
        assertThat(JarvisFastGuards.tickets("LDIC-223 et LDIC-223, ORN-4")).containsExactly("LDIC-223", "ORN-4");
    }

    @Test
    void f239GreetingsAndCourtesiesAreSmallTalkRequestsAreNot() {
        for (String said : List.of("Bonjour Jarvis.", "Bonjour, Monsieur", "Merci beaucoup !", "Bonjour Jarvis, ça va ?",
                "Salut", "Au revoir, à demain", "Ok Jarvis", "Bonsoir", "Jarvis")) {
            assertThat(JarvisFastGuards.isSmallTalk(said)).as(said).isTrue();
        }
        for (String said : List.of("Bonjour, regarde les tickets d'Edenred", "Où en est CAGIP ?",
                "Merci de lancer les tests", "")) {
            assertThat(JarvisFastGuards.isSmallTalk(said)).as(said).isFalse();
        }
    }

    @Test
    void f239CourtesyRepliesAreShortAndFitTheGreeting() {
        assertThat(JarvisFastGuards.courtesyReply("Bonjour Jarvis"))
                .isEqualTo("Bonjour, Monsieur.");
        assertThat(JarvisFastGuards.courtesyReply("Bonsoir"))
                .isEqualTo("Bonsoir, Monsieur.");
        assertThat(JarvisFastGuards.courtesyReply("Merci Jarvis"))
                .isEqualTo("Avec plaisir, Monsieur.");
        assertThat(JarvisFastGuards.courtesyReply("Au revoir"))
                .isEqualTo("Bonne journée, Monsieur.");
        assertThat(JarvisFastGuards.courtesyReply("Jarvis ?")).isEqualTo("Oui, Monsieur ?");
        assertThat(JarvisFastGuards.courtesyReply("Ça va ?"))
                .isEqualTo("Fort bien, merci, Monsieur.");
        assertThat(JarvisFastGuards.isListeningInvitation(
                JarvisFastGuards.courtesyReply("Bonjour"))).isFalse();
    }

    // ---------------------------------------------------------------- F-239 / SF-239-13 (banc prod du 02/11)

    @Test
    void f239MetaTalkAndDetailOffersAreRecognised() {
        assertThat(JarvisFastGuards.isMetaTalk("Le contexte ne m'en dit pas plus.")).isTrue();
        assertThat(JarvisFastGuards.isMetaTalk("D'après mes notes, rien ne bloque.")).isTrue();
        assertThat(JarvisFastGuards.isMetaTalk("Le chantier 1 est en recette.")).isFalse();
        assertThat(JarvisFastGuards.isDetailOffer("Je peux détailler les autres chantiers si vous le souhaitez.")).isTrue();
        assertThat(JarvisFastGuards.isDetailOffer("Souhaitez-vous le détail, Monsieur ?")).isTrue();
        assertThat(JarvisFastGuards.isDetailOffer("Je peux vous en dire plus sur Helios.")).isTrue();
        assertThat(JarvisFastGuards.isDetailOffer("Claire Dumas doit valider la recette.")).isFalse();
    }

    @Test
    void f239AChantierNumberMustBeCopiedExactly() {
        String context = "- client Orion : sujet Orion-Run\n  - chantier 1 — Refonte du portail, 3/6";
        assertThat(JarvisFastGuards.ungroundedFact("Le chantier 3 du portail est en recette.", context, ""))
                .isEqualTo("chantier 3");
        assertThat(JarvisFastGuards.ungroundedFact("Le chantier 1 du portail est en recette.", context, "")).isNull();
        assertThat(JarvisFastGuards.ungroundedFact("Le chantier 7 avance.", "Chantier courant : 7", "")).isNull();
        assertThat(JarvisFastGuards.ungroundedFact("Le chantier 9 avance.", "", "Où en est le chantier 9 ?")).isNull();
    }

    @Test
    void f240MoreRequestsAreShortAndAboutTheLastAnswer() {
        assertThat(JarvisFastGuards.isMoreRequest("Dis-m'en plus.")).isTrue();
        assertThat(JarvisFastGuards.isMoreRequest("Jarvis, développe.")).isTrue();
        assertThat(JarvisFastGuards.isMoreRequest("Tu peux développer le point Helios ?")).isTrue();
        assertThat(JarvisFastGuards.isMoreRequest("Et ensuite ?")).isTrue();
        assertThat(JarvisFastGuards.isMoreRequest("Plus de détails sur la migration")).isTrue();
        // Une nouvelle demande (identifiant, chiffre), une phrase longue ou « vérifie » ne sont pas « la suite ».
        assertThat(JarvisFastGuards.isMoreRequest("Détaille le ticket LDIC-223.")).isFalse();
        assertThat(JarvisFastGuards.isMoreRequest(
                "Développe-moi une stratégie complète de migration pour tout le parc applicatif du client")).isFalse();
        assertThat(JarvisFastGuards.isMoreRequest("Vérifie.")).isFalse();
        assertThat(JarvisFastGuards.isMoreRequest("Continue.")).isFalse();
        assertThat(JarvisFastGuards.isMoreRequest("Bonjour Jarvis.")).isFalse();
    }

    @Test
    void f240AnOpusAnswerIsFoundInTheRecentExchanges() {
        assertThat(JarvisFastGuards.hasOpusAnswer("## Derniers échanges\n- 09:12 Opus : Deux tickets sont bloqués."))
                .isTrue();
        assertThat(JarvisFastGuards.hasOpusAnswer("## Derniers échanges\n- 09:12 Monsieur : bonjour\n- Jarvis : Bonjour."))
                .isFalse();
        assertThat(JarvisFastGuards.hasOpusAnswer(null)).isFalse();
    }

    @Test
    void f240_06AnAdmissionOfIgnoranceIsRecognised() {
        assertThat(JarvisFastGuards.claimsIgnorance(
                "Je l'ignore, Monsieur : rien dans vos dossiers ne mentionne ce week-end.")).isTrue();
        assertThat(JarvisFastGuards.claimsIgnorance("Je ne sais pas, Monsieur.")).isTrue();
        assertThat(JarvisFastGuards.claimsIgnorance("Monsieur, je n'en sais rien.")).isTrue();
        assertThat(JarvisFastGuards.claimsIgnorance("Je n'ai rien sur ce week-end, Monsieur.")).isTrue();
        assertThat(JarvisFastGuards.claimsIgnorance("Je n'ai aucune information concernant samedi.")).isTrue();
        assertThat(JarvisFastGuards.claimsIgnorance("Il n'y a rien dans mes notes à ce sujet.")).isTrue();
        // Ni une demande de répéter, ni une réponse ancrée, ni une politesse.
        assertThat(JarvisFastGuards.claimsIgnorance("Pardon, Monsieur, je n'ai pas saisi la fin.")).isFalse();
        assertThat(JarvisFastGuards.claimsIgnorance("Je n'ai rien compris, pouvez-vous répéter ?")).isFalse();
        assertThat(JarvisFastGuards.claimsIgnorance("Rien à envoyer pour l'instant.")).isFalse();
        assertThat(JarvisFastGuards.claimsIgnorance("Je n'en doute pas, Monsieur.")).isFalse();
        assertThat(JarvisFastGuards.claimsIgnorance("Voulez-vous que je génère la preuve, Monsieur ?")).isFalse();
    }

    @Test
    void f240_06AMissingSourceIsOneTheContextDoesNotProvide() {
        assertThat(JarvisFastGuards.namesMissingSource("Je peux consulter votre agenda si vous le souhaitez.",
                "Maintenant : lundi")).isTrue();
        assertThat(JarvisFastGuards.namesMissingSource("Votre calendrier est vide.", "Maintenant : lundi")).isTrue();
        assertThat(JarvisFastGuards.namesMissingSource("Voyons votre emploi du temps.", "")).isTrue();
        assertThat(JarvisFastGuards.namesMissingSource("Votre agenda est libre samedi.",
                "Agenda : samedi libre")).isFalse();
        assertThat(JarvisFastGuards.namesMissingSource("Le chantier 3 en est aux tests.", "")).isFalse();
    }

    // ---- F-240 / SF-240-07 : liens inventés, question de politesse ----

    private static final String P08 = """
            Attentes ouvertes du sujet courant : Claire Dumas doit valider la recette du portail
            A envoyer (1) : compte rendu de recette a Claire Dumas""";

    @Test
    void f240_07AnInventedDependencyIsCutFromTheSentence() {
        assertThat(JarvisFastGuards.withoutInventedLink("Passons au compte rendu de recette pour Claire Dumas, "
                + "Monsieur : il reste à envoyer, et sa validation du portail d'Orion-Run en dépend.", P08,
                "On passe à la suite.")).isEqualTo(
                        "Passons au compte rendu de recette pour Claire Dumas, Monsieur : il reste à envoyer.");
        assertThat(JarvisFastGuards.withoutInventedLink(
                "Le compte rendu reste à envoyer, car Claire l'attend pour valider.", P08, ""))
                .isEqualTo("Le compte rendu reste à envoyer.");
        assertThat(JarvisFastGuards.withoutInventedLink(
                "Le compte rendu reste à envoyer parce que Claire doit valider.", P08, ""))
                .isEqualTo("Le compte rendu reste à envoyer.");
        // Rien de dicible ne reste : chaîne vide.
        assertThat(JarvisFastGuards.withoutInventedLink("Tout dépend de Claire.", P08, "")).isEmpty();
    }

    @Test
    void f240_07AKnownLinkOrAPlainSentenceIsKept() {
        String said = "La validation de Claire dépend du compte rendu.";
        assertThat(JarvisFastGuards.withoutInventedLink(said, P08 + "\nOpus : la validation dépend du compte rendu.",
                "")).isEqualTo(said);
        assertThat(JarvisFastGuards.withoutInventedLink("Ça dépend de Claire, Monsieur.", P08,
                "Ça dépend de qui ?")).isEqualTo("Ça dépend de Claire, Monsieur.");
        assertThat(JarvisFastGuards.withoutInventedLink("Le chantier 3 en est aux tests, en toute indépendance.",
                CONTEXT, "")).isEqualTo("Le chantier 3 en est aux tests, en toute indépendance.");
        assertThat(JarvisFastGuards.withoutInventedLink("Ensuite, on attend la carte de Paul.", CONTEXT, ""))
                .isEqualTo("Ensuite, on attend la carte de Paul.");
    }

    @Test
    void f240_07CourtesyQuestionsAreToldFromClarifications() {
        assertThat(JarvisFastGuards.isCourtesyQuestion("Du nouveau depuis notre dernier entretien ?")).isTrue();
        assertThat(JarvisFastGuards.isCourtesyQuestion("La semaine commence bien ?")).isTrue();
        assertThat(JarvisFastGuards.isCourtesyQuestion("Bien dormi, Monsieur ?")).isTrue();
        assertThat(JarvisFastGuards.isCourtesyQuestion("Et vous ?")).isTrue();
        assertThat(JarvisFastGuards.isCourtesyQuestion("Du nouveau depuis notre dernier entretien.")).isFalse();
        assertThat(JarvisFastGuards.isCourtesyQuestion("Lequel, Monsieur ?")).isFalse();
        assertThat(JarvisFastGuards.isCourtesyQuestion("Vous confirmez ?")).isFalse();
        assertThat(JarvisFastGuards.isCourtesyQuestion("Voulez-vous que je génère la preuve, Monsieur ?")).isFalse();
        assertThat(JarvisFastGuards.isCourtesyQuestion("Par quoi commençons-nous ?")).isFalse();
        assertThat(JarvisFastGuards.courtesyAlreadyAsked("## Conversation du jour\nPremière salutation du jour : non"
                + "\nQuestion de politesse déjà posée : oui")).isTrue();
        assertThat(JarvisFastGuards.courtesyAlreadyAsked("Question de politesse déjà posée : non")).isFalse();
    }
}
