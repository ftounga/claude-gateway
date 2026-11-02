package fr.claudegateway.jarvis.fastbrain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Les garde-fous de justesse du cerveau rapide</b> (F-235 / SF-235-03, D9, D11) — déterministes, sans appel
 * modèle, appliqués <b>phrase par phrase avant la synthèse</b>.
 *
 * <ul>
 *   <li><b>Ancrage</b> (D9a) : tout nombre (≥ 2 chiffres, montant, pourcentage, heure), toute clé de ticket
 *       ({@code ABC-123}), tout nom propre absent <b>à la fois</b> du contexte et de la parole ⇒ la phrase n'est pas
 *       dite, la demande est déléguée.</li>
 *   <li><b>Texte qui imite un appel d'outil</b> (D9c) ⇒ délégation.</li>
 *   <li><b>Délégation forcée</b> (D9d) : « vérifie », « demande à Opus », « réfléchis-y », « c'est faux » (liste fermée).</li>
 *   <li><b>Formules creuses</b> (D11d) dites <b>seules</b> ⇒ retirées ; <b>répétition</b> (D11c) d'une phrase des 5
 *       dernières réponses ⇒ retirée.</li>
 * </ul>
 */
public final class JarvisFastGuards {

    /** Formules interdites quand elles sont seules (D11d) — normalisées. */
    static final List<String> FILLERS = List.of("je m en occupe", "je t ecoute", "je vous ecoute", "bien recu",
            "d accord je regarde", "tres bien", "je regarde", "une seconde", "laissez moi verifier",
            "je m en charge", "c est note", "entendu");

    /** Ce que le PO dit pour forcer Opus (D9d) — normalisé, liste fermée. */
    static final List<String> FORCE = List.of("verifie", "verifie ca", "verifie le", "verifie la", "verifiez",
            "demande a opus", "demande le a opus", "pose la question a opus", "reflechis y", "reflechis",
            "reflechis bien", "c est faux", "c est pas ca", "ce n est pas ca", "non c est faux", "faux");

    /** Noms propres toujours permis (ils ne sont pas des faits). */
    static final Set<String> ALWAYS_ALLOWED = Set.of("monsieur", "jarvis", "opus", "jira", "confluence", "ok",
            "a", "je", "vous", "il", "elle", "le", "la", "les", "un", "une", "sur", "pour", "c", "j", "l", "d",
            "qu", "n", "s", "et", "en", "au", "aux", "du", "de", "des", "ce", "cette", "ces", "mon", "ma", "mes",
            "votre", "vos", "son", "sa", "ses", "oui", "non", "bien", "bonjour", "bonsoir", "merci", "avec",
            "tout", "rien", "voici", "voila", "parfait", "pas", "plus", "nous", "on", "lundi", "mardi", "mercredi",
            "jeudi", "vendredi", "samedi", "dimanche", "aujourd", "hier", "demain", "ici", "chantier",
            "sujet", "client", "ticket", "tickets", "etape", "deux", "trois", "quatre", "cinq");

    private static final Pattern NUMBER = Pattern.compile(
            "\\d+(?:[.,]\\d+)?\\s*(?:%|€|\\$|k€|euros?)|\\d{1,2}\\s*h(?:\\s*\\d{2})?\\b|\\d{1,2}:\\d{2}|\\d{2,}(?:[.,]\\d+)?");
    private static final Pattern TICKET = Pattern.compile("\\b[A-Z][A-Z0-9]{1,9}-\\d+\\b");
    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}'’\\-]*");
    private static final Pattern TOOL_TEXT = Pattern.compile(
            "(?is)deleguer\\s*\\(|\"demande\"\\s*:|\"annonce\"\\s*:|<\\s*/?\\s*(?:function|tool|invoke|thinking|parameter)"
                    + "|^\\s*[\\[{]");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?…]+[»\"')]*(?=\\s|$)");

    private JarvisFastGuards() {
    }

    private static final Pattern CHANTIER = Pattern.compile("\\bchantier (\\d{1,4})\\b");

    /**
     * F-239 / SF-239-13 — une phrase qui parle de ce que Jarvis sait (« le contexte », « mes notes », « mes données ») :
     * jugée robotique au banc (« Le contexte ne m'en dit pas plus ») ; retirée.
     */
    public static boolean isMetaTalk(String sentence) {
        String n = " " + normalize(sentence) + " ";
        return n.contains(" contexte ") || n.contains(" mes notes ") || n.contains(" mes donnees ")
                || n.contains(" les donnees dont je dispose ") || n.contains(" dans mes informations ");
    }

    /**
     * F-239 / SF-239-13 — une offre de détail ajoutée d'office (« Je peux détailler… », « Souhaitez-vous le détail ? ») :
     * elle allonge la réponse sans rien dire (banc prod : 4 phrases) ; retirée.
     */
    public static boolean isDetailOffer(String sentence) {
        String n = normalize(sentence).replaceAll("\\bmonsieur\\b", "").replaceAll("\\s+", " ").strip();
        return n.matches("^(je peux|je puis|puis je) (vous )?(en )?(detailler|donner le detail|preciser|developper|"
                + "vous en dire plus|entrer dans le detail).*")
                || n.matches("^(souhaitez vous|voulez vous|desirez vous) (le|les|plus de|davantage de) detail.*")
                || (n.contains("detail") && n.contains("si vous le souhaitez"));
    }

    /** Le texte normalisé : minuscules, sans accents, ponctuation en espaces, espaces simples. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String n = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.replaceAll("[^a-z0-9]+", " ").strip();
    }

    /** Vrai si la parole du PO force la délégation à Opus (D9d). */
    public static boolean forcesDelegation(String said) {
        String n = normalize(said);
        n = n.replaceFirst("^(non|jarvis|monsieur)\\s+", "").replaceFirst("\\s+(s il te plait|s il vous plait|stp|svp)$",
                "");
        return FORCE.contains(n);
    }

    /** Vrai si la phrase imite un appel d'outil (D9c). */
    public static boolean looksLikeToolCall(String sentence) {
        return sentence != null && TOOL_TEXT.matcher(sentence).find();
    }

    /** Vrai si la phrase n'est qu'une formule creuse (D11d) — éventuellement suivie de « Monsieur ». */
    public static boolean isFillerAlone(String sentence) {
        String n = normalize(sentence).replaceAll("\\bmonsieur\\b", "").replaceAll("\\s+", " ").strip();
        n = n.replaceFirst("^(alors|bon|bien|oui|parfait)\\s+", "");
        return !n.isEmpty() && FILLERS.contains(n);
    }

    /**
     * F-239 / SF-239-02 (D3) — les invitations à parler (« Je vous écoute… », « Je reste à votre disposition… »),
     * normalisées : une phrase qui <b>commence</b> par l'une d'elles est retirée, seule ou en conclusion.
     */
    static final List<String> INVITATIONS = List.of("je vous ecoute", "je t ecoute", "a votre ecoute",
            "je reste a votre ecoute", "je suis a votre ecoute", "je reste a l ecoute", "je suis a l ecoute",
            "je reste a votre disposition", "je suis a votre disposition", "a votre disposition", "n hesitez pas",
            // F-239 / SF-239-08 — relevées au banc de conversation.
            "je reste pret", "je suis pret", "je reste a l affut", "que puis je faire pour vous",
            "que puis je pour vous");

    /** F-239 / SF-239-02 (D3) — vrai si la phrase n'est qu'une invitation à parler (« Je vous écoute pour la suite »). */
    public static boolean isListeningInvitation(String sentence) {
        String n = normalize(sentence).replaceAll("\\bmonsieur\\b", "").replaceAll("\\s+", " ").strip();
        n = n.replaceFirst("^(et|alors|bien|bon|oui|bien sur|sinon)\\s+", "");
        for (String invitation : INVITATIONS) {
            if (n.equals(invitation) || n.startsWith(invitation + " ")) {
                return true;
            }
        }
        return false;
    }

    private static final String SMALL_TALK_UNIT = "(?:bonjour|bonsoir|salut|hello|coucou|merci|merci beaucoup|"
            + "merci bien|ca va|comment ca va|comment allez vous|comment vas tu|vous allez bien|bonne journee|"
            + "bonne soiree|bonne nuit|au revoir|a plus|a bientot|a demain|ok|d accord|parfait|tres bien|super|"
            + "genial|c est bon|c est tout|ce sera tout|a vous|a toi|aussi|monsieur|et vous|et toi|oui|bien)";
    private static final Pattern SMALL_TALK = Pattern.compile("^" + SMALL_TALK_UNIT + "(?: " + SMALL_TALK_UNIT + ")*$");

    /**
     * F-239 / SF-239-02 (D3) — une salutation ou une politesse (« Bonjour Jarvis », « Merci », « Ça va ? ») : jamais
     * un travail, donc <b>jamais déléguée</b>. Liste fermée, normalisée ; « Jarvis » est ignoré.
     */
    public static boolean isSmallTalk(String said) {
        String original = normalize(said);
        String n = original.replaceAll("\\bjarvis\\b", " ").replaceAll("\\s+", " ").strip();
        return n.isEmpty() ? !original.isEmpty() : SMALL_TALK.matcher(n).matches();
    }

    /** F-239 / SF-239-02 (D3) — la réponse courtoise fixe d'une salutation, quand le modèle n'en a pas donné de sûre. */
    public static String courtesyReply(String said) {
        String n = " " + normalize(said) + " ";
        if (n.replace(" jarvis ", " ").isBlank()) {
            return "Oui, Monsieur ?"; // « Jarvis » seul : il est appelé
        }
        if (n.contains(" bonsoir ") || n.contains(" bonne soiree ") || n.contains(" bonne nuit ")) {
            return n.contains(" bonsoir ") ? "Bonsoir, Monsieur." : "Bonne soirée, Monsieur.";
        }
        if (n.contains(" au revoir ") || n.contains(" bonne journee ") || n.contains(" a demain ")
                || n.contains(" a plus ") || n.contains(" a bientot ")) {
            return "Bonne journée, Monsieur.";
        }
        if (n.contains(" bonjour ") || n.contains(" salut ") || n.contains(" hello ") || n.contains(" coucou ")) {
            return "Bonjour, Monsieur.";
        }
        if (n.contains(" merci ")) {
            return "Avec plaisir, Monsieur.";
        }
        if (n.contains(" ca va ") || n.contains(" allez vous ") || n.contains(" vas tu ") || n.contains(" allez bien ")) {
            return "Fort bien, merci, Monsieur.";
        }
        return "Parfait.";
    }

    /** F-235 / SF-235-09 (D18) — les noms de l'œuvre que Jarvis n'imite jamais (normalisés). */
    static final List<String> WORK_NAMES = List.of("stark", "tony stark", "iron man", "ironman", "marvel",
            "avengers");

    /**
     * Vrai si la phrase cite l'œuvre (Stark, Iron Man, Marvel, Avengers) alors que ce nom n'est ni dans le contexte
     * ni dans la parole (D18) : la phrase est retirée.
     */
    public static boolean imitates(String sentence, String context, String said) {
        String n = " " + normalize(sentence) + " ";
        String known = " " + normalize((context == null ? "" : context) + " " + (said == null ? "" : said)) + " ";
        for (String name : WORK_NAMES) {
            String token = " " + name + " ";
            if (n.contains(token) && !known.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** Vrai si la phrase répète l'une des phrases déjà dites (D11c), à la normalisation près. */
    public static boolean isRepeat(String sentence, List<String> recentSentences) {
        String n = normalize(sentence);
        if (n.isEmpty() || recentSentences == null) {
            return false;
        }
        for (String previous : recentSentences) {
            if (n.equals(normalize(previous))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Le premier fait <b>non ancré</b> de la phrase (D9a), ou {@code null} si tout ce qu'elle affirme figure dans le
     * contexte ou dans la parole.
     */
    public static String ungroundedFact(String sentence, String context, String said) {
        if (sentence == null || sentence.isBlank()) {
            return null;
        }
        String known = (context == null ? "" : context) + "\n" + (said == null ? "" : said);
        Matcher numbers = NUMBER.matcher(sentence);
        while (numbers.find()) {
            String digits = numbers.group().replaceAll("[^0-9]", "");
            if (!digits.isEmpty() && !containsNumber(known, digits)) {
                return numbers.group();
            }
        }
        Matcher tickets = TICKET.matcher(sentence);
        while (tickets.find()) {
            if (!known.contains(tickets.group())) {
                return tickets.group();
            }
        }
        String knownNorm = " " + normalize(known) + " ";
        // F-239 / SF-239-13 — un numéro de chantier se recopie tel quel : « chantier 3 » doit figurer dans ce qui est
        // connu (banc prod du 02/11 : « chantier 3 du portail Orion » au lieu du chantier 1).
        Matcher chantiers = CHANTIER.matcher(normalize(sentence));
        while (chantiers.find()) {
            String n = chantiers.group(1);
            if (!knownNorm.contains(" chantier " + n + " ") && !knownNorm.contains(" chantier courant " + n + " ")) {
                return "chantier " + n;
            }
        }
        for (String name : properNouns(sentence)) {
            String n = normalize(name);
            if (n.isEmpty() || ALWAYS_ALLOWED.contains(n)) {
                continue;
            }
            if (!knownNorm.contains(" " + n + " ")) {
                return name;
            }
        }
        return null;
    }

    /**
     * Vrai si le nombre figure dans ce qui est connu : comme nombre entier, ou une fois les séparateurs retirés
     * (« 1 200 », « 14:30 » et « 14 h 30 » s'écrivent de plusieurs façons).
     */
    static boolean containsNumber(String known, String digits) {
        for (String token : known.split("[^0-9]+")) {
            if (token.equals(digits)) {
                return true;
            }
        }
        Matcher m = Pattern.compile("\\d+(?:[\\s.,:h]+\\d+)*").matcher(known);
        while (m.find()) {
            if (m.group().replaceAll("[^0-9]", "").equals(digits)) {
                return true;
            }
        }
        return false;
    }

    /** Les mots à majuscule qui ne commencent pas une phrase (noms propres probables). */
    static List<String> properNouns(String sentence) {
        List<String> names = new ArrayList<>();
        Matcher m = WORD.matcher(sentence);
        boolean start = true;
        int last = 0;
        while (m.find()) {
            String between = sentence.substring(last, m.start());
            if (between.matches(".*[.!?:«»\"…].*")) {
                start = true;
            }
            String word = m.group();
            if (!start && Character.isUpperCase(word.codePointAt(0))) {
                names.add(word);
            }
            start = false;
            last = m.end();
        }
        return names;
    }

    /** Découpe un texte en phrases complètes ; la fin sans ponctuation est rendue à part ({@link Split#rest()}). */
    public static Split split(String text) {
        List<String> sentences = new ArrayList<>();
        Matcher m = SENTENCE_END.matcher(text);
        int from = 0;
        while (m.find()) {
            String s = text.substring(from, m.end()).strip();
            if (!s.isEmpty()) {
                sentences.add(s);
            }
            from = m.end();
        }
        return new Split(sentences, text.substring(from));
    }

    /** Des phrases complètes et ce qui reste. */
    public record Split(List<String> sentences, String rest) {
    }
}
