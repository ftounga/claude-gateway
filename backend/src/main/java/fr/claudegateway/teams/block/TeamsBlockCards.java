package fr.claudegateway.teams.block;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>La lecture — et le refus — d'un appel d'outil de présentation</b> (F-89 / SF-89-02).
 *
 * <h2>Pourquoi la validation est ici, et pas à l'écran</h2>
 *
 * <p>Un écran qui écarterait les lignes sans source afficherait un compte rendu <b>amputé sans le
 * dire</b> : exactement le « à moitié faux » que ce volet interdit. On refuse donc à l'<b>émission</b>,
 * et l'agent reçoit de quoi se corriger dans le même tour.</p>
 *
 * <h2>Refuser plutôt que tronquer</h2>
 *
 * <p>Les bornes (lignes, sections, moments, longueurs) font <b>refuser</b>, jamais couper. Un compte
 * rendu tronqué perd des engagements en silence, et c'est précisément ce qu'on vient y chercher. Le
 * refus dit les bornes exactes, pour que le modèle sache comment découper.</p>
 *
 * <h2>Le doute ne devient jamais une affirmation</h2>
 *
 * <p>Une certitude absente vaut {@link TeamsBlockCard.Certainty#A_CONFIRMER}. Une certitude
 * <b>inconnue</b>, en revanche, est refusée : c'est le signe que le modèle a inventé une échelle —
 * un score, un pourcentage — là où il n'y en a pas.</p>
 */
public final class TeamsBlockCards {

    /** Lignes par bloc, toutes sections confondues. Au-delà, ce n'est plus un compte rendu. */
    public static final int MAX_LINES = 100;
    /** Sections d'une carte. Au-delà, la carte cesse d'être lisible en trois secondes. */
    public static final int MAX_SECTIONS = 10;
    /** Moments par bloc : le haut de la fourchette mesurée au cadrage (20 à 60 images utiles). */
    public static final int MAX_MOMENTS = 60;
    /** Manques déclarés : c'est une liste de trous, pas un journal. */
    public static final int MAX_GAPS = 20;

    public static final int MAX_TITLE_CHARS = 200;
    public static final int MAX_SECTION_TITLE_CHARS = 120;
    public static final int MAX_WINDOW_CHARS = 300;
    public static final int MAX_TEXT_CHARS = 500;
    public static final int MAX_AUTHOR_CHARS = 120;
    public static final int MAX_AT_CHARS = 40;
    public static final int MAX_MESSAGE_ID_CHARS = 200;
    public static final int MAX_URL_CHARS = 1_000;
    public static final int MAX_IMAGE_ID_CHARS = 64;

    private TeamsBlockCards() {
    }

    /**
     * Lit l'appel d'outil et construit le bloc, ou refuse.
     *
     * @param kind          genre attendu, déduit du nom de l'outil appelé
     * @param input         arguments de l'appel, tels que le modèle les a écrits
     * @param imageIsKnown  prédicat qui dit si une image de moment existe <b>pour cet utilisateur</b> ;
     *                      jamais interrogé pour un moment sans image
     * @return le bloc, prêt à être posé dans le fil
     * @throws TeamsBlockRejectedException si l'appel ne peut pas donner un bloc vérifiable
     */
    public static TeamsBlockCard read(TeamsBlockCard.Kind kind, JsonNode input,
            Predicate<String> imageIsKnown) {
        JsonNode node = input == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance()
                : input;
        String title = required(node, "title", MAX_TITLE_CHARS,
                "Donne un titre au bloc : c'est ce qu'on lit en premier.");
        String subtitle = optional(node, "subtitle", MAX_TITLE_CHARS);
        // D4 : le résultat porte TOUJOURS la fenêtre réellement lue. Sans elle, un compte rendu
        // laisse croire qu'il couvre tout — et personne ne peut savoir qu'il ne couvre qu'un jour.
        String window = required(node, "window", MAX_WINDOW_CHARS,
                "Dis la fenêtre RÉELLEMENT lue (« du 5 au 12 septembre, 47 messages lus ») : "
                        + "sans elle, on ne peut pas savoir ce que ce compte rendu ne couvre pas.");
        List<String> gaps = readGaps(node);

        List<TeamsBlockCard.Section> sections = new ArrayList<>();
        List<TeamsBlockCard.Moment> moments = new ArrayList<>();
        if (kind == TeamsBlockCard.Kind.MOMENTS) {
            moments.addAll(readMoments(node, imageIsKnown));
        } else if (kind == TeamsBlockCard.Kind.LIST) {
            sections.add(new TeamsBlockCard.Section("", readLines(node.get("lines"), "lines")));
        } else {
            sections.addAll(readSections(node));
        }
        TeamsBlockCard card = new TeamsBlockCard(kind, title, subtitle, window,
                List.copyOf(sections), List.copyOf(moments), List.copyOf(gaps));
        if (card.allLines().size() > MAX_LINES) {
            throw new TeamsBlockRejectedException("Ce bloc porte " + card.allLines().size()
                    + " lignes ; le maximum est " + MAX_LINES + ". Découpe en plusieurs blocs "
                    + "plutôt que d'en retirer : une ligne coupée est un engagement perdu.");
        }
        return card;
    }

    private static List<String> readGaps(JsonNode node) {
        JsonNode gapsNode = node.get("gaps");
        if (gapsNode == null || !gapsNode.isArray()) {
            // Le silence n'a pas de valeur par défaut : « rien ne manque » et « je n'ai pas regardé »
            // ne sont pas la même chose, et c'est toute la règle du volet.
            throw new TeamsBlockRejectedException(
                    "Déclare `gaps` : ce que tu n'as PAS pu lire, tel que les outils te l'ont rendu "
                            + "(messages non reconnus, fil non atteint, plafond touché). Une liste "
                            + "vide est acceptée si aucun outil n'a signalé de manque — mais elle "
                            + "doit être écrite.");
        }
        if (gapsNode.size() > MAX_GAPS) {
            throw new TeamsBlockRejectedException("Au plus " + MAX_GAPS + " manques déclarés ; "
                    + "regroupe-les.");
        }
        List<String> gaps = new ArrayList<>();
        for (JsonNode gap : gapsNode) {
            String text = trim(gap.asText(""), MAX_WINDOW_CHARS, "gaps[]");
            if (!text.isEmpty()) {
                gaps.add(text);
            }
        }
        return gaps;
    }

    private static List<TeamsBlockCard.Section> readSections(JsonNode node) {
        JsonNode sectionsNode = node.get("sections");
        if (sectionsNode == null || !sectionsNode.isArray() || sectionsNode.isEmpty()) {
            throw new TeamsBlockRejectedException(
                    "Donne au moins une section. Mets en PREMIER ce qu'on attend du lecteur : "
                            + "c'est ce qu'il cherche, et il ne doit pas avoir à faire défiler.");
        }
        if (sectionsNode.size() > MAX_SECTIONS) {
            throw new TeamsBlockRejectedException("Au plus " + MAX_SECTIONS + " sections.");
        }
        List<TeamsBlockCard.Section> sections = new ArrayList<>();
        int index = 0;
        for (JsonNode section : sectionsNode) {
            String where = "sections[" + index + "]";
            String sectionTitle = trim(section.path("title").asText(""), MAX_SECTION_TITLE_CHARS,
                    where + ".title");
            if (sectionTitle.isEmpty()) {
                throw new TeamsBlockRejectedException("Chaque section doit porter un titre (" + where
                        + ") : « Ce qu'on attend de vous », « Décisions », « Vos engagements ».");
            }
            sections.add(new TeamsBlockCard.Section(sectionTitle,
                    readLines(section.get("lines"), where + ".lines")));
            index++;
        }
        return sections;
    }

    private static List<TeamsBlockCard.Line> readLines(JsonNode linesNode, String where) {
        if (linesNode == null || !linesNode.isArray() || linesNode.isEmpty()) {
            throw new TeamsBlockRejectedException("Donne au moins une ligne dans " + where + ".");
        }
        List<TeamsBlockCard.Line> lines = new ArrayList<>();
        int index = 0;
        for (JsonNode line : linesNode) {
            lines.add(readLine(line, where + "[" + index + "]"));
            index++;
        }
        return lines;
    }

    private static TeamsBlockCard.Line readLine(JsonNode node, String where) {
        String text = trim(node.path("text").asText(""), MAX_TEXT_CHARS, where + ".text");
        if (text.isEmpty()) {
            throw new TeamsBlockRejectedException("Ligne vide en " + where + " : retire-la.");
        }
        String messageId = trim(node.path("messageId").asText(""), MAX_MESSAGE_ID_CHARS,
                where + ".messageId");
        String webUrl = trim(node.path("webUrl").asText(""), MAX_URL_CHARS, where + ".webUrl");
        TeamsBlockCard.Line line = new TeamsBlockCard.Line(text,
                trim(node.path("author").asText(""), MAX_AUTHOR_CHARS, where + ".author"),
                trim(node.path("at").asText(""), MAX_AT_CHARS, where + ".at"),
                messageId, webUrl, readCertainty(node, where));
        if (!line.hasSource()) {
            // LA règle de valeur du volet : ce qui est affirmé doit être vérifiable d'un clic.
            throw new TeamsBlockRejectedException("La ligne " + where + " n'a pas de source. "
                    + "Chaque ligne doit porter le message d'où elle vient — son `messageId` et, "
                    + "quand tu l'as, son `webUrl`. Une ligne que le lecteur ne peut pas ouvrir "
                    + "n'a pas sa place dans un compte rendu : retire-la, ou trouve sa source.");
        }
        return line;
    }

    private static TeamsBlockCard.Certainty readCertainty(JsonNode node, String where) {
        String raw = node.path("certainty").asText("").trim();
        if (raw.isEmpty()) {
            // Le défaut penche du côté qui n'affirme rien.
            return TeamsBlockCard.Certainty.A_CONFIRMER;
        }
        try {
            return TeamsBlockCard.Certainty.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new TeamsBlockRejectedException("Certitude inconnue en " + where + " : « " + raw
                    + " ». Il n'y en a que deux, et ce sont des MOTS, jamais un score ni un "
                    + "pourcentage : EXPLICITE (c'est écrit noir sur blanc) ou A_CONFIRMER (c'est "
                    + "ta lecture de ce qui est écrit).");
        }
    }

    private static List<TeamsBlockCard.Moment> readMoments(JsonNode node,
            Predicate<String> imageIsKnown) {
        JsonNode momentsNode = node.get("moments");
        if (momentsNode == null || !momentsNode.isArray() || momentsNode.isEmpty()) {
            throw new TeamsBlockRejectedException("Donne au moins un moment.");
        }
        if (momentsNode.size() > MAX_MOMENTS) {
            throw new TeamsBlockRejectedException("Au plus " + MAX_MOMENTS + " moments par bloc ; "
                    + "garde ceux qui portent une décision.");
        }
        List<TeamsBlockCard.Moment> moments = new ArrayList<>();
        int index = 0;
        for (JsonNode moment : momentsNode) {
            String where = "moments[" + index + "]";
            String at = trim(moment.path("at").asText(""), MAX_AT_CHARS, where + ".at");
            if (at.isEmpty()) {
                // C'est l'horodatage qui rapproche l'image de la phrase : sans lui, ce n'est plus
                // un moment, c'est une galerie et une transcription côte à côte.
                throw new TeamsBlockRejectedException("Le moment " + where + " n'a pas d'heure. "
                        + "C'est l'horodatage qui pose l'image à côté de la phrase : sans lui, "
                        + "il n'y a pas de moment.");
            }
            String quote = trim(moment.path("quote").asText(""), MAX_TEXT_CHARS, where + ".quote");
            if (quote.isEmpty()) {
                throw new TeamsBlockRejectedException("Le moment " + where + " n'a pas de citation : "
                        + "donne la phrase prononcée pendant que cette image était affichée.");
            }
            String imageId = trim(moment.path("imageId").asText(""), MAX_IMAGE_ID_CHARS,
                    where + ".imageId");
            if (!imageId.isEmpty() && !imageIsKnown.test(imageId)) {
                // Inconnue et « appartenant à quelqu'un d'autre » sont INDISCERNABLES : on ne dit
                // pas à un compte qu'une image existe ailleurs.
                throw new TeamsBlockRejectedException("L'image « " + imageId + " » du moment " + where
                        + " n'existe pas pour ce terminal. N'invente pas d'identifiant d'image : "
                        + "rends le moment sans image plutôt qu'avec une image qui n'existe pas.");
            }
            moments.add(new TeamsBlockCard.Moment(at, quote,
                    trim(moment.path("speaker").asText(""), MAX_AUTHOR_CHARS, where + ".speaker"),
                    imageId,
                    trim(moment.path("webUrl").asText(""), MAX_URL_CHARS, where + ".webUrl")));
            index++;
        }
        return moments;
    }

    private static String required(JsonNode node, String field, int max, String advice) {
        String value = trim(node.path(field).asText(""), max, field);
        if (value.isEmpty()) {
            throw new TeamsBlockRejectedException("`" + field + "` est obligatoire. " + advice);
        }
        return value;
    }

    private static String optional(JsonNode node, String field, int max) {
        return trim(node.path(field).asText(""), max, field);
    }

    /** Ne coupe pas : refuse. Un compte rendu tronqué perd des engagements sans le dire. */
    private static String trim(String value, int max, String where) {
        String text = value == null ? "" : value.strip();
        if (text.length() > max) {
            throw new TeamsBlockRejectedException("Le champ " + where + " dépasse " + max
                    + " caractères (" + text.length() + ") : raccourcis-le. Rien n'est coupé "
                    + "automatiquement — une phrase tronquée peut dire le contraire de la phrase.");
        }
        return text;
    }
}
