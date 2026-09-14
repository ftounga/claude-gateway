package fr.claudegateway.teams.block;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import fr.claudegateway.teams.TeamsToolCatalog;

/**
 * <b>Le « zéro » d'une lecture Teams devient un échec typé, visible, et bloquant</b> (F-89 / SF-89-11).
 *
 * <h2>Le constat</h2>
 *
 * <p>En production (poste CAGIP, 2026-09-15) : {@code teams_find_conversations} rend un zéro
 * (NOTHING_SERVED / NOTHING_CLASSIFIED), <b>et l'agent enchaîne {@code bash} + {@code edit_file}</b> et
 * répond depuis les fichiers du projet. La {@link TeamsToolCatalog#NOTHING_RULE} était trop
 * <b>molle</b> : l'agent la contournait et se rabattait <b>en silence</b> sur le poste. Rien ne
 * signalait à l'utilisateur que la réponse ne venait pas de Teams.</p>
 *
 * <h2>La décision</h2>
 *
 * <p>Quand une lecture Teams ne rend rien d'exploitable <b>après réseau ET écran</b> (SF-89-06),
 * l'agent ne répond PAS la question de fond depuis le poste en silence. À la place :</p>
 * <ol>
 *   <li>un <b>bloc riche d'échec</b> ({@link TeamsBlockCard.Kind#READ_FAILED}) est posé dans le fil,
 *       coloré (attention/ambre, ou liaison rompue/rouge), portant le motif exact et deux actions —
 *       <i>Réessayer</i> et <i>Chercher dans le projet à la place</i> ;</li>
 *   <li>le repli sur le poste est un <b>choix explicite de l'utilisateur</b> : tant qu'il n'a pas
 *       choisi, {@code bash} / {@code read_file} et les autres outils de fond sont refusés.</li>
 * </ol>
 *
 * <h2>Pourquoi la détection lit la PROSE du runner</h2>
 *
 * <p>Le résultat d'un outil routé vers la machine ne voyage que sous forme de <b>texte</b>
 * ({@code RunnerCallResult.content}) — le diagnostic structuré du runner (gaps, observation) n'y est
 * pas porté. La classification s'appuie donc sur les <b>phrases stables</b> que le runner écrit sur
 * un zéro, verrouillées par ses propres tests
 * ({@code fr.claudegateway.runner.teams.ObservationDiagnostic}, {@code TeamsGapKind}). Ces phrases ne
 * sont écrites que sur un <b>vrai zéro</b> (aucune nature utile classée) : une lecture partielle avec
 * du contenu n'en porte aucune, et n'est donc jamais requalifiée en échec.</p>
 */
public final class TeamsReadFailure {

    private TeamsReadFailure() {
    }

    /** Le titre du bloc d'échec — le même partout, pour qu'on le reconnaisse d'un regard. */
    public static final String TITLE = "Teams n'a pas pu être lu";

    /** Le titre du bandeau de repli — la source de la réponse qui suit, dite en toutes lettres. */
    public static final String FALLBACK_TITLE = "Réponse basée sur le projet, pas sur Teams";

    /** Le sous-titre du bandeau de repli. */
    public static final String FALLBACK_SUBTITLE =
            "Vous avez autorisé le repli : cette réponse vient des fichiers du poste, pas de Teams.";

    /**
     * <b>Les six outils de LECTURE dont un zéro devient un échec</b> (F-89 / SF-89-11). Les outils qui
     * <i>créent</i> (capture), <i>écrivent</i> (F-108) ou <i>présentent</i> (blocs) n'en sont pas :
     * leur zéro n'est pas une lecture manquée.
     */
    public static final Set<String> READING_TOOLS = Set.of(
            TeamsToolCatalog.FIND_CONVERSATIONS,
            TeamsToolCatalog.READ_CONVERSATION,
            TeamsToolCatalog.MENTIONS,
            TeamsToolCatalog.SEARCH,
            TeamsToolCatalog.FIND_MEETINGS,
            TeamsToolCatalog.MEETING_TRANSCRIPT);

    /** Vrai si ce nom d'outil est une <b>lecture Teams</b> dont l'échec doit se voir. */
    public static boolean isReadingTool(String tool) {
        return tool != null && READING_TOOLS.contains(tool);
    }

    /**
     * <b>Les outils de « réponse de fond » depuis le poste</b> — ceux que l'agent ne doit PAS employer
     * tant que l'échec Teams n'a pas reçu de choix de l'utilisateur. La liste est volontairement
     * étroite : les outils Teams (dont la relance de lecture), la présentation, le plan restent
     * ouverts, car <i>Réessayer</i> passe par eux.
     */
    public static boolean isProjectAnswerTool(String tool) {
        if (tool == null) {
            return false;
        }
        return switch (tool) {
            case "bash", "read_file", "write_file", "edit_file", "search_files", "list_files",
                    "explore" -> true;
            default -> false;
        };
    }

    /**
     * <b>Ce que l'agent reçoit quand sa lecture Teams a rendu un zéro</b> — la règle NON négociable qui
     * remplace la {@link TeamsToolCatalog#NOTHING_RULE} molle : produire le bloc d'échec (déjà posé) et
     * <b>s'arrêter</b>.
     */
    public static final String STOP_INSTRUCTION =
            "ÉCHEC DE LECTURE TEAMS. Cette lecture n'a rien rendu d'exploitable, après le réseau ET "
                    + "l'écran. Un bloc d'échec est déjà posé dans le fil, avec deux actions pour "
                    + "l'utilisateur : Réessayer, ou Chercher dans le projet à la place. RÈGLE NON "
                    + "NÉGOCIABLE : arrête-toi ici. Ne réponds PAS à la question de fond depuis le "
                    + "projet, bash, grep ou les fichiers du poste, et ne substitue JAMAIS le contenu "
                    + "du projet à Teams sans un geste explicite de l'utilisateur. Termine ton tour "
                    + "sans autre outil de fond.";

    /**
     * <b>Ce que l'agent reçoit s'il tente quand même un outil de fond</b> après un échec Teams et sans
     * choix de l'utilisateur : le second verrou, celui qui rend la règle vraie plutôt que suggérée.
     */
    public static final String GATE_MESSAGE =
            "Refusé : une lecture Teams a échoué dans ce tour et l'utilisateur n'a pas encore choisi. "
                    + "Tant qu'il n'a pas cliqué « Chercher dans le projet à la place » (ou dit qu'il "
                    + "t'autorise à répondre via le projet), tu ne peux pas répondre la question de "
                    + "fond depuis le poste. Arrête-toi et laisse l'utilisateur choisir dans le bloc "
                    + "d'échec Teams.";

    /**
     * La précision envoyée par le bouton <b>Chercher dans le projet à la place</b> (F-84 / SF-84-06) —
     * et la seule chose qui autorise le repli.
     */
    public static final String FALLBACK_PRECISION = "Autorisé à répondre via le projet à la place.";

    /** La précision envoyée par le bouton <b>Réessayer</b> : relance la lecture Teams. */
    public static final String RETRY_PRECISION = "Réessaie la lecture Teams.";

    /**
     * Vrai si le message de l'utilisateur <b>autorise explicitement le repli</b> sur le poste
     * (F-89 / SF-89-11). Robuste aux variantes de casse et d'accent : le geste doit compter même écrit
     * à la main.
     */
    public static boolean authorizesFallback(String userText) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        String normalized = fold(userText);
        return normalized.contains("repondre via le projet")
                || normalized.contains("cherche dans le projet")
                || normalized.contains("via le projet a la place");
    }

    /**
     * <b>Quel échec derrière un zéro de lecture Teams</b> — ou {@link Optional#empty()} si le résultat
     * porte du contenu exploitable. On ne classe que les {@link #READING_TOOLS}, et l'ordre est celui
     * de la gravité : une liaison rompue prime sur un « rien servi ».
     *
     * @param tool    le nom de l'outil de lecture appelé
     * @param content le résultat rendu au modèle (texte du runner, ou message d'erreur)
     */
    public static Optional<Reason> classify(String tool, String content) {
        if (!isReadingTool(tool) || content == null || content.isBlank()) {
            return Optional.empty();
        }
        String text = fold(content);
        // Rouge d'abord : la liaison, puis la session — un geste hors de Teams les débloque.
        if (text.contains("session microsoft") || text.contains("signed_out")) {
            return Optional.of(Reason.SESSION_EXPIRED);
        }
        if (text.contains("navigateur non detecte")
                || (text.contains("liaison") && text.contains("n est pas etablie"))
                || (text.contains("liaison teams") && text.contains("pas reliee"))) {
            return Optional.of(Reason.NOT_LINKED);
        }
        // Ambre : l'écran a changé, le contenu n'a pas été reconnu, ou rien n'a été servi.
        if (text.contains("change d ecran")) {
            return Optional.of(Reason.SCREEN_CHANGED);
        }
        if (text.contains("pas ete reconnu") || text.contains("ne reconnait pas")
                || text.contains("nothing_classified")) {
            return Optional.of(Reason.NOTHING_CLASSIFIED);
        }
        if (text.contains("n a rien servi") || text.contains("ouvrez l ecran voulu")
                || text.contains("aucune reponse reseau observee") || text.contains("nothing_served")) {
            return Optional.of(Reason.NOTHING_SERVED);
        }
        return Optional.empty();
    }

    /**
     * <b>Le bloc d'échec, prêt à poser dans le fil</b> (F-89 / SF-89-11). Display-only : aucune ligne,
     * aucun moment — le sous-titre porte le motif et le geste, {@link TeamsBlockCard#reason()} porte la
     * couleur, et les deux actions sont posées par l'écran (elles sont les mêmes pour tous les motifs).
     */
    public static TeamsBlockCard card(Reason reason) {
        return new TeamsBlockCard(TeamsBlockCard.Kind.READ_FAILED, TITLE, reason.motive(), "",
                List.of(), List.of(), List.of(), "", reason.name());
    }

    /**
     * <b>Le bandeau « réponse basée sur le projet »</b> (F-89 / SF-89-11), posé en tête d'un tour de
     * repli autorisé.
     */
    public static TeamsBlockCard fallbackBanner() {
        return new TeamsBlockCard(TeamsBlockCard.Kind.PROJECT_FALLBACK, FALLBACK_TITLE,
                FALLBACK_SUBTITLE, "", List.of(), List.of(), List.of(), "", "");
    }

    /**
     * Fold : minuscules, sans accents, apostrophes et espaces normalisés — pour comparer une phrase du
     * runner sans être trahi par la casse, un accent ou le choix d'apostrophe (« l'écran » vs « l ecran »).
     */
    private static String fold(String value) {
        String stripped = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return stripped.toLowerCase(Locale.ROOT)
                .replaceAll("['’]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * <b>Les cinq motifs d'un échec de lecture Teams</b> (F-89 / SF-89-11), et la <b>gravité</b> qui
     * porte leur couleur : {@link Severity#BROKEN} (rouge, §5) pour une liaison rompue ou une session
     * expirée ; {@link Severity#ATTENTION} (ambre, §12) pour tout le reste.
     */
    public enum Reason {
        /** Teams n'a rien servi d'utile depuis le rattachement : ouvrir l'écran voulu. */
        NOTHING_SERVED(Severity.ATTENTION,
                "Teams n'a rien servi : le contenu n'est pas arrivé depuis le rattachement. "
                        + "Ouvrez l'écran voulu dans Teams, puis réessayez."),
        /** Teams a répondu, mais le contenu n'a pas été reconnu : rouvrir n'y changera rien. */
        NOTHING_CLASSIFIED(Severity.ATTENTION,
                "Teams a répondu, mais le contenu n'a pas été reconnu. Rouvrir ou cliquer n'y "
                        + "changera rien : c'est le runner qui doit apprendre ces chemins."),
        /** Teams a changé d'écran : la vue attendue n'était pas affichée. */
        SCREEN_CHANGED(Severity.ATTENTION,
                "Teams a changé d'écran : la vue attendue n'était pas affichée. Ouvrez la bonne "
                        + "vue dans Teams, puis réessayez."),
        /** La liaison Teams n'est pas établie sur cette machine. */
        NOT_LINKED(Severity.BROKEN,
                "La liaison Teams n'est pas établie sur cette machine. Relancez le navigateur avec "
                        + "son port de débogage, puis réessayez."),
        /** La session Microsoft a expiré : Teams demande une reconnexion. */
        SESSION_EXPIRED(Severity.BROKEN,
                "La session Microsoft a expiré : Teams demande une reconnexion. Reconnectez-vous "
                        + "à Teams dans le navigateur, puis réessayez.");

        private final Severity severity;
        private final String motive;

        Reason(Severity severity, String motive) {
            this.severity = severity;
            this.motive = motive;
        }

        /** La gravité, qui décide de la couleur du bloc. */
        public Severity severity() {
            return severity;
        }

        /** Le motif exact, écrit à l'utilisateur, avec le geste qui débloque. */
        public String motive() {
            return motive;
        }
    }

    /** La gravité d'un échec : elle porte la couleur, jamais un chiffre. */
    public enum Severity {
        /** Attention, action possible côté utilisateur — ambre (§12). */
        ATTENTION,
        /** Liaison rompue, la lecture ne repartira pas sans reconnexion — rouge (§5). */
        BROKEN
    }
}
