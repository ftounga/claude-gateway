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

    private static final Pattern IGNORANCE = Pattern.compile(
            "^(je l ignore|j ignore|je ne (le )?sais pas|je n en sais rien)\\b.*"
                    + "|.*\\bje n en sais rien\\b.*"
                    + "|^je n ai (rien|aucune information|aucune trace|aucune indication) (sur|concernant|a propos|"
                    + "au sujet|pour|de prevu)\\b.*"
                    + "|.*\\b(rien|aucune mention|aucune trace) dans (vos|mes|nos) (dossiers|fichiers|notes|echanges)\\b.*");

    /**
     * F-240 / SF-240-06 — une phrase qui avoue ignorer (« Je l'ignore », « je n'ai rien sur… », « rien dans vos
     * dossiers ») : une information absente se <b>délègue</b>, elle ne s'avoue pas (banc prod du 03/11, P02).
     */
    public static boolean claimsIgnorance(String sentence) {
        String n = normalize(sentence).replaceAll("\\bmonsieur\\b", "").replaceAll("\\s+", " ").strip();
        return IGNORANCE.matcher(n).matches();
    }

    /** F-240 / SF-240-06 — les sources que Jarvis ne lit pas aujourd'hui (F-243 n'est que cadrée), normalisées. */
    static final List<String> MISSING_SOURCES = List.of("agenda", "agendas", "calendrier", "calendriers",
            "emploi du temps");

    /**
     * F-240 / SF-240-06 — une phrase qui nomme une source qu'aucune ligne du contexte ne fournit (« Je peux consulter
     * votre agenda ») : Jarvis n'offre jamais une capacité ou une source inexistante. Présente dans le contexte, elle
     * est permise.
     */
    public static boolean namesMissingSource(String sentence, String context) {
        String n = " " + normalize(sentence) + " ";
        String known = " " + normalize(context) + " ";
        return MISSING_SOURCES.stream().anyMatch(s -> n.contains(" " + s + " ") && !known.contains(" " + s + " "));
    }

    /**
     * F-240 / SF-240-07 — les marqueurs d'un lien de cause ou de dépendance. Groupe 1 : une conjonction (le lien
     * commence au marqueur) ; groupe 2 : un verbe ou un nom de dépendance (le lien est toute la proposition).
     */
    private static final Pattern LINK = Pattern.compile("(?iu)(?<![\\p{L}])(?:"
            + "(parce qu|puisqu|car(?![\\p{L}])|[àa] cause d|si bien qu|c['’]est pourquoi)"
            + "|(d[ée]pend\\p{L}*|conditionn\\p{L}*|feu vert|votre aval))");
    private static final Pattern CLAUSE_BOUNDARY = Pattern.compile("[,;:—–]");
    private static final Pattern TRAILING_JOIN = Pattern.compile(
            "(?iu)(?:[\\s,;:—–-]|(?<![\\p{L}])(?:et|mais|donc|ou|qui|que|dont)(?![\\p{L}]))+$");

    /** La racine normalisée d'un marqueur, cherchée telle quelle dans le contexte. */
    private static String linkStem(String marker) {
        String n = normalize(marker);
        for (String stem : List.of("depend", "conditionn")) {
            if (n.startsWith(stem)) {
                return stem;
            }
        }
        return n;
    }

    /**
     * F-240 / SF-240-07 — <b>un lien de cause ou de dépendance inventé</b> (banc prod du 03/11, P08 : « il reste à
     * envoyer, et sa validation du portail en dépend » quand le contexte ne lie pas les deux) : rend la phrase sans
     * la partie liée (à partir de la conjonction, ou de la proposition qui porte le verbe de dépendance), terminée par
     * un point ; la phrase telle quelle si aucun marqueur n'est inventé (présent dans le contexte ou la parole, il est
     * permis) ; une chaîne vide si ce qui resterait n'a pas trois mots (hors « Monsieur »).
     */
    public static String withoutInventedLink(String sentence, String context, String said) {
        if (sentence == null || sentence.isBlank() || !namesAFact(sentence)) {
            return sentence;
        }
        String known = " " + normalize(context) + " " + normalize(said) + " ";
        Matcher m = LINK.matcher(sentence);
        while (m.find()) {
            String stem = linkStem(m.group());
            if (known.contains("car".equals(stem) ? " car " : " " + stem)) {
                continue;
            }
            return m.group(2) != null ? beforeClauseOf(sentence, m.start()) : keptBefore(sentence, m.start());
        }
        return sentence;
    }

    /** Ce qui précède la proposition qui contient la position {@code at}, ou une chaîne vide (moins de trois mots). */
    static String beforeClauseOf(String sentence, int at) {
        int cut = 0;
        Matcher b = CLAUSE_BOUNDARY.matcher(sentence.substring(0, at));
        while (b.find()) {
            cut = b.start();
        }
        return keptBefore(sentence, cut);
    }

    /** Ce qui précède {@code cut}, sans liaison pendante, terminé par un point ; vide sous trois mots (hors Monsieur). */
    private static String keptBefore(String sentence, int cut) {
        String kept = TRAILING_JOIN.matcher(sentence.substring(0, cut)).replaceAll("").strip();
        String words = normalize(kept).replaceAll("\\bmonsieur\\b", " ").strip();
        if (words.isEmpty() || words.split("\\s+").length < 3) {
            return "";
        }
        return kept + ".";
    }

    /**
     * F-240 / SF-240-07 — une <b>remarque</b> de l'utilisateur (« Grosse journée qui s'annonce. ») à laquelle la phrase
     * répond en nommant une source absente (« … ; l'agenda, lui, n'a pas encore rendu son verdict », pré-banc du 03/11,
     * C06) : rien n'est à chercher, donc rien à déléguer. Rend la phrase sans la proposition qui nomme la source, ou
     * une chaîne vide. Un aveu d'ignorance n'est jamais récupéré.
     */
    public static String withoutMissingSource(String sentence, String context) {
        if (sentence == null || claimsIgnorance(sentence)) {
            return "";
        }
        String known = " " + normalize(context) + " ";
        Matcher w = WORD.matcher(sentence);
        while (w.find()) {
            String n = normalize(w.group()).replaceFirst("^[a-z] ", ""); // « l'agenda » ⇒ « agenda »
            boolean source = MISSING_SOURCES.contains(n) || ("emploi".equals(n)
                    && normalize(sentence.substring(w.start())).startsWith("emploi du temps"));
            String named = "emploi".equals(n) ? "emploi du temps" : n;
            if (source && !known.contains(" " + named + " ")) {
                return beforeClauseOf(sentence, w.start());
            }
        }
        return sentence;
    }

    /** F-240 / SF-240-07 — la parole est une remarque, pas une question ni une demande (aucun « ? »). */
    public static boolean isRemark(String said) {
        return said != null && !said.isBlank() && !said.contains("?");
    }

    /**
     * F-240 / SF-240-07 — la phrase nomme un fait du travail (un nom propre autre que « Monsieur » ou « Jarvis », un
     * nombre, un chantier) : seul un tel fait peut être lié à tort à un autre. Une petite conversation (« le gris
     * s'habille en conséquence ») n'en nomme aucun et n'est pas touchée (pré-banc du 03/11, P06).
     */
    static boolean namesAFact(String sentence) {
        if (sentence.chars().anyMatch(Character::isDigit) || normalize(sentence).contains("chantier")) {
            return true;
        }
        return properNouns(sentence).stream().map(JarvisFastGuards::normalize)
                .anyMatch(w -> !w.equals("monsieur") && !w.equals("jarvis"));
    }

    /** F-240 / SF-240-07 — les questions de politesse ou de conversation, normalisées (règle F-239 : une seule). */
    static final List<String> COURTESY_QUESTIONS = List.of("du nouveau", "quoi de neuf", "bien dormi", "bien repose",
            "bien reposee", "et vous", "comment allez vous", "comment vas tu", "ca va", "vous allez bien", "en forme",
            "la forme", "commence bien", "commence t elle bien", "commence t il bien", "s annonce bien",
            "se passe bien", "s est bien passe", "s est bien passee", "bon week end", "passe un bon", "bonne nuit");
    private static final List<String> NOT_COURTESY = List.of("confirm", "lequel", "laquelle", "lesquels",
            "lesquelles", "voulez vous que", "souhaitez vous que", "desirez vous que", "pardon", "repeter", "je la lance",
            "je le lance", "je les lance", "dois je", "s agit il", "est ce bien");

    /**
     * F-240 / SF-240-07 — une question de politesse ou de conversation (« Du nouveau depuis notre dernier
     * entretien ? », « Bien dormi ? », « Et vous ? ») — jamais une clarification ni une confirmation (« Lequel,
     * Monsieur ? », « Vous confirmez ? », « Voulez-vous que je génère la preuve ? »).
     */
    public static boolean isCourtesyQuestion(String sentence) {
        if (sentence == null || !sentence.strip().replaceAll("[»\"')\\s]+$", "").endsWith("?")) {
            return false;
        }
        String n = " " + normalize(sentence) + " ";
        if (NOT_COURTESY.stream().anyMatch(w -> n.contains(" " + w))) {
            return false;
        }
        return COURTESY_QUESTIONS.stream().anyMatch(q -> n.contains(" " + q + " "));
    }

    /** F-240 / SF-240-07 — vrai si le contexte dit qu'une question de politesse a déjà été posée aujourd'hui. */
    public static boolean courtesyAlreadyAsked(String context) {
        return context != null && context.contains("Question de politesse déjà posée : oui");
    }

    private static final java.util.regex.Pattern MORE_REQUEST =java.util.regex.Pattern.compile(
            "\\b(dis m en|dites m en|dis moi en|dites moi en|m en dire|en dire|me dire) (un peu )?plus\\b"
                    + "|\\bdevelopp|\\bdetaill|\\bplus de details?\\b|\\bdavantage de details?\\b"
                    + "|^(et )?(la suite|ensuite|apres|quoi d autre|encore)$");
    private static final java.util.regex.Pattern OPUS_ANSWER = java.util.regex.Pattern
            .compile("(?m)^- (?:\\d{1,2}:\\d{2} )?Opus : \\S");

    private static final java.util.regex.Pattern LAST_OPUS = java.util.regex.Pattern
            .compile("(?m)^- (?:\\d{1,2}:\\d{2} )?Opus : (.+)$");

    /** Au-delà, la parole n'est plus « la suite » mais une nouvelle demande. */
    static final int MAX_MORE_WORDS = 8;

    /**
     * F-240 / SF-240-04 (D8) — <b>une demande de développement</b> de la réponse précédente (« dis-m'en plus »,
     * « développe le point Helios », « et ensuite ? ») : courte (≤ {@value #MAX_MORE_WORDS} mots), sans chiffre ni
     * identifiant (« détaille le ticket LDIC-223 » est une nouvelle demande), jamais un ordre de vérifier.
     */
    public static boolean isMoreRequest(String text) {
        String n = normalize(text).replaceAll("^(jarvis|monsieur) ", "").replaceAll(" (jarvis|monsieur)$", "").strip();
        if (n.isEmpty() || n.matches(".*\\d.*") || n.split(" ").length > MAX_MORE_WORDS || forcesDelegation(text)) {
            return false;
        }
        return MORE_REQUEST.matcher(n).find();
    }

    /** F-240 / SF-240-04 (D8) — les derniers échanges du contexte portent une réponse d'Opus. */
    public static boolean hasOpusAnswer(String context) {
        return context != null && OPUS_ANSWER.matcher(context).find();
    }

    /**
     * F-240 / SF-240-05 — la dernière réponse d'Opus des derniers échanges du fil (ce qui suit « Opus : »), ou
     * {@code null}.
     */
    public static String lastOpusAnswer(String context) {
        if (context == null) {
            return null;
        }
        java.util.regex.Matcher m = LAST_OPUS.matcher(context);
        String last = null;
        while (m.find()) {
            last = m.group(1).strip();
        }
        return last == null || last.isEmpty() ? null : last;
    }

    /** F-240 / SF-240-05 — les identifiants de tickets d'un texte, distincts, dans l'ordre d'apparition. */
    public static List<String> tickets(String text) {
        if (text == null) {
            return List.of();
        }
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = TICKET.matcher(text);
        while (m.find()) {
            found.add(m.group());
        }
        return List.copyOf(found);
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

    /** F-240 / SF-240-05 — relevées au banc (« Dites-moi par où vous souhaitez commencer. »), en conclusion. */
    static final List<String> CLOSING_PROMPTS = List.of("dites moi par ou", "dites moi par quoi",
            "dites moi quand vous", "dites moi si vous", "je reste attentif", "je suis attentif",
            // F-240 / SF-240-07 — relevées au banc (P03 : « Nous avançons dès que vous le souhaitez. »).
            "nous avancons des que", "nous avancerons des que", "on avance des que", "des que vous le souhaitez",
            "quand vous le souhaitez", "quand vous voudrez");

    private static final Pattern START_QUESTION = Pattern.compile(
            "^(?:alors |et |bien )?(?:par (?:ou|quoi|lequel|laquelle)|avec quoi) (?:souhaitez|voulez|desirez|preferez)"
                    + " vous (?:commencer|demarrer|attaquer)(?: .*)?$"
                    + "|^(?:alors |et )?par (?:ou|quoi) (?:commencons nous|commencons|on commence)(?: .*)?$");
    private static final Pattern ASKS_TO_START = Pattern.compile(
            "\\b(?:commenc\\w*|on s y met|au travail|attaqu\\w*|demarr\\w*|par ou|par quoi|la suite|on passe|on y va)\\b");

    /**
     * F-240 / SF-240-05 — <b>une relance ajoutée en conclusion</b> : « Dites-moi par où vous souhaitez commencer. »,
     * ou « Par quoi souhaitez-vous commencer ? » quand l'utilisateur ne parle ni de commencer ni d'enchaîner
     * (« Journée chargée aujourd'hui. ») — jugées robotiques au banc (P03, 3 passages sur 7). La porte ne la retire
     * qu'APRÈS une autre phrase dite : elle ne vide jamais une réponse (sinon délégation d'office, banc du 03/11).
     */
    public static boolean isClosingPrompt(String sentence, String said) {
        String n = normalize(sentence).replaceAll("\\bmonsieur\\b", "").replaceAll("\\s+", " ").strip();
        for (String prompt : CLOSING_PROMPTS) {
            if (n.equals(prompt) || n.startsWith(prompt + " ")) {
                return true;
            }
        }
        return START_QUESTION.matcher(n).matches() && !ASKS_TO_START.matcher(normalize(said)).find();
    }

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
        // F-237 / SF-237-15 — une suite de nombres ne franchit jamais une fin de ligne (« 14:00\n3. » recollait en
        // « 14003 ») ; toute sous-suite contiguë compte (« lundi 14:00 » dans « 2 : Terraform — lundi 14:00 ») et
        // les zéros de tête sont ignorés (« 9h55 » = « 09:55 »).
        String wanted = stripZeros(digits);
        Matcher m = NUMBER_RUN.matcher(known);
        while (m.find()) {
            String[] tokens = m.group().split("[^0-9]+");
            for (int from = 0; from < tokens.length; from++) {
                StringBuilder joined = new StringBuilder();
                for (int to = from; to < tokens.length && joined.length() <= digits.length() + 2; to++) {
                    joined.append(tokens[to]);
                    if (joined.toString().equals(digits) || stripZeros(joined.toString()).equals(wanted)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Une suite de nombres d'une même ligne, séparés par espace, tabulation, « . », « , », « : » ou « h ». */
    private static final Pattern NUMBER_RUN = Pattern.compile("\\d+(?:[ \\t\\u00A0\\u202F.,:h]+\\d+)*");

    private static String stripZeros(String digits) {
        String s = digits.replaceFirst("^0+(?=\\d)", "");
        return s;
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
