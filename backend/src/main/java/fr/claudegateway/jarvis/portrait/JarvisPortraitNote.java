package fr.claudegateway.jarvis.portrait;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Le portrait tel que Jarvis le reçoit</b> (F-241 / SF-241-03, D5, D6, D10, D12a, D17, D18) — fonctions pures.
 *
 * <ul>
 *   <li>{@link #block} : le bloc « Ce que vous savez de Monsieur » — les bornes figées {@link #RULES} puis le portrait
 *   <b>entier</b>, daté (« mis à jour en mars 2026 »). Il ne dépend que du portrait : identique d'un appel à l'autre tant
 *   que rien ne change (préfixe caché du cerveau rapide ; message du tour Opus).</li>
 *   <li>{@link #importantDate} : la date qui compte aujourd'hui ou cette semaine — <b>volatile</b>, donc dans le
 *   message, jamais dans la consigne.</li>
 *   <li>{@link #forbiddenTerms} / {@link #blocked} : le filet déterministe des sujets « À ne pas aborder ».</li>
 *   <li>{@link #leaks} : le détecteur de livrable (un élément du portrait dans un mail ou un message à un tiers).</li>
 * </ul>
 */
public final class JarvisPortraitNote {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH);

    /** D10 — les bornes, texte figé, identiques pour le cerveau rapide et pour Opus. Rien de volatil. */
    public static final String RULES = """
            # Ce que vous savez de Monsieur
            Ce portrait, Monsieur l'a donné lui-même. Il sert à converser, rien d'autre. Tu juges seul du moment ; \
            les bornes suivantes ne se discutent pas :
            1. Avoir l'information ne veut pas dire s'en servir : la plupart de tes réponses n'en contiennent aucune.
            2. Au plus UNE allusion personnelle spontanée par jour. Répondre à ce que Monsieur amène lui-même n'est \
            pas une allusion spontanée : là, ta connaissance est attendue (prénom, âge, détail juste), avec chaleur.
            3. Jamais deux fois la même allusion ni le même trait d'humour.
            4. Jamais d'humour sur un sujet sensible (santé, famille, deuil, conflit, argent) que Monsieur n'a pas \
            ouvert lui-même dans cette conversation. La note « ton sobre » prime sur toute allusion légère.
            5. N'évoque JAMAIS ce qui figure sous « À ne pas aborder », même s'il s'en approche : réponds alors \
            sobrement, sans le citer.
            6. Une date qui compte peut être saluée une fois, le jour même ou à son approche.
            7. Une information datée peut être périmée (l'âge d'un enfant, un poste) : dans le doute, demande \
            plutôt qu'affirmer.
            8. Rien de ce portrait n'entre jamais dans un message, un courriel, une page ou un document destiné à \
            un tiers.""";

    /** D17 — la note figée d'un tour en présentation (dans le message, jamais dans la consigne). */
    public static final String PRESENTATION_NOTE = "[Jarvis — mode présentation : un public regarde ; aucun élément "
            + "personnel, aucune proposition de retenir. À une question personnelle : « Nous en parlerons en privé, "
            + "Monsieur. »]";

    /** L'en-tête du bloc joint au message d'un tour Opus en mode Jarvis (D6). */
    public static final String OPUS_HEADER = "[Jarvis — portrait de Monsieur, pour converser seulement]";

    private JarvisPortraitNote() {
    }

    /**
     * Un portrait <b>fictif</b> (bancs) : sections par clé, souvenirs en texte, tout daté de {@code at}. Jamais le
     * portrait réel d'un compte.
     */
    public static JarvisPortraitService.Snapshot fixture(java.util.Map<String, String> sections,
            List<String> souvenirs, OffsetDateTime at) {
        java.util.Map<PortraitSection, JarvisPortraitService.SectionText> map =
                new java.util.EnumMap<>(PortraitSection.class);
        if (sections != null) {
            sections.forEach((k, v) -> {
                PortraitSection section = PortraitSection.parse(k);
                if (section != null && v != null) {
                    map.put(section, new JarvisPortraitService.SectionText(v, at));
                }
            });
        }
        List<JarvisPortraitService.Souvenir> list = new ArrayList<>();
        if (souvenirs != null) {
            int n = 1;
            for (String text : souvenirs) {
                list.add(new JarvisPortraitService.Souvenir(new java.util.UUID(0L, n), n, text, false,
                        at.atZoneSameInstant(ZONE).toLocalDate(), "ECRAN"));
                n++;
            }
        }
        return new JarvisPortraitService.Snapshot(null, 0L, true, map, List.copyOf(list), null, null, null, 0);
    }

    // ---- Le bloc ----

    /** Vrai si le portrait doit servir : présent, allumé, non vide. */
    public static boolean usable(JarvisPortraitService.Snapshot s) {
        return s != null && s.enabled() && s.hasContent();
    }

    /** Le bloc : {@link #RULES} puis chaque section non vide et les souvenirs numérotés, datés. */
    public static String block(JarvisPortraitService.Snapshot s) {
        StringBuilder b = new StringBuilder(RULES);
        for (PortraitSection section : PortraitSection.values()) {
            JarvisPortraitService.SectionText t = s.sections().get(section);
            if (t == null || t.text() == null || t.text().isBlank()) {
                continue;
            }
            b.append("\n\n## ").append(section.title());
            if (section.sensitive()) {
                b.append(" — sensible");
            }
            if (t.updatedAt() != null) {
                b.append(" (mis à jour en ").append(monthYear(t.updatedAt())).append(')');
            }
            b.append('\n').append(t.text().strip());
        }
        if (!s.souvenirs().isEmpty()) {
            b.append("\n\n## Souvenirs");
            for (JarvisPortraitService.Souvenir m : s.souvenirs()) {
                b.append("\n- S").append(m.number()).append(" (noté en ")
                        .append(m.notedOn() == null ? "?" : MONTH_YEAR.format(m.notedOn()))
                        .append(m.sensitive() ? ", sensible" : "").append(") : ").append(m.text());
            }
        }
        return b.toString();
    }

    /** D6 — le bloc joint au message d'un tour Opus (après la consigne orale de F-240). */
    public static String appendForOpus(String consigne, JarvisPortraitService.Snapshot s) {
        if (!usable(s)) {
            return consigne;
        }
        return consigne + "\n\n" + OPUS_HEADER + "\n" + block(s);
    }

    private static String monthYear(OffsetDateTime at) {
        return MONTH_YEAR.format(at.atZoneSameInstant(ZONE).toLocalDate());
    }

    // ---- La date qui compte (volatile, dans le message) ----

    private static final Pattern DATE_LINE = Pattern
            .compile("^\\s*(\\d{1,2})\\s*/\\s*(\\d{1,2})(?:\\s*/\\s*(\\d{4}))?\\s*[—–:-]+\\s*(.+)$");

    /**
     * D3, D6 — la note « date qui compte » du jour : aujourd'hui, ou dans les 7 jours. {@code null} sans date proche.
     * Une date sans année est annuelle.
     */
    public static String importantDate(JarvisPortraitService.Snapshot s, LocalDate today) {
        if (!usable(s)) {
            return null;
        }
        List<String> notes = new ArrayList<>();
        for (String line : s.text(PortraitSection.DATES).split("\\R")) {
            Matcher m = DATE_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            try {
                int day = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                LocalDate date;
                if (m.group(3) != null) {
                    date = LocalDate.of(Integer.parseInt(m.group(3)), month, day);
                } else {
                    date = LocalDate.of(today.getYear(), month, day);
                    if (date.isBefore(today)) {
                        date = date.plusYears(1);
                    }
                }
                long days = ChronoUnit.DAYS.between(today, date);
                if (days == 0) {
                    notes.add("aujourd'hui : " + m.group(4).strip());
                } else if (days > 0 && days <= 7) {
                    notes.add("dans " + days + " jour" + (days > 1 ? "s" : "") + " : " + m.group(4).strip());
                }
            } catch (RuntimeException ignored) {
                // Ligne illisible : ignorée.
            }
        }
        return notes.isEmpty() ? null : "[Jarvis — date qui compte, " + String.join(" ; ", notes) + "]";
    }

    // ---- D12 (a) : les sujets à ne pas aborder ----

    private static final Set<String> STOP = Set.of("avec", "sans", "dans", "pour", "sujet", "sujets", "parler",
            "jamais", "aborder", "evoquer", "surtout", "rien", "tout", "toute", "toutes", "tous", "plus", "mais",
            "elle", "elles", "ils", "leur", "leurs", "notre", "votre", "cette", "celui", "celle", "chez", "entre",
            "monsieur", "jarvis", "merci", "bien", "fait", "faire", "etre", "avoir", "quand", "comme", "depuis",
            "sous", "aussi", "encore", "deja", "tres", "peu", "beaucoup", "autre", "autres", "meme", "dont", "ainsi");

    /** Les termes distinctifs de « À ne pas aborder » (normalisés, ≥ 4 lettres, sans mots vides). */
    public static Set<String> forbiddenTerms(JarvisPortraitService.Snapshot s) {
        Set<String> terms = new LinkedHashSet<>();
        if (s == null) {
            return terms;
        }
        for (String w : normalize(s.text(PortraitSection.INTERDITS)).split(" ")) {
            if (w.length() >= 4 && !STOP.contains(w)) {
                terms.add(w);
            }
        }
        return terms;
    }

    /**
     * Vrai si la phrase évoque un terme interdit que la parole du PO ne contient pas (lui seul peut ouvrir le
     * sujet). Un mot de la phrase « touche » le terme s'il lui est égal ou commence par lui (≥ 5 lettres).
     */
    public static boolean blocked(String sentence, String said, Set<String> terms) {
        if (terms == null || terms.isEmpty() || sentence == null) {
            return false;
        }
        Set<String> spoken = words(sentence);
        Set<String> heard = words(said);
        for (String term : terms) {
            if (touches(spoken, term) && !touches(heard, term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean touches(Set<String> words, String term) {
        for (String w : words) {
            if (w.equals(term) || (term.length() >= 5 && w.startsWith(term))) {
                return true;
            }
        }
        return false;
    }

    // ---- SF-241-04 (D11, D12 b/c) : les allusions ----

    /**
     * Les lignes-marques du <b>cerveau rapide seul</b> (Opus ne les reçoit pas : elles s'afficheraient). Texte figé,
     * joint après le bloc portrait : il fait partie du préfixe caché.
     */
    public static final String FAST_MARKS = """


            # Ce que tu dis de sa vie (lignes-marques, jamais dites)
            Quand ta réponse fait une allusion à la vie de Monsieur, ajoute une ligne seule « ✦ allusion: <sujet en \
            six mots au plus> » ; pour un trait d'humour : « ✦ humour: <résumé en six mots au plus> ». Ces lignes ne \
            sont jamais dites. Le message peut porter « [Jarvis — déjà dit récemment, ne pas répéter : …] » : ne \
            répète jamais ces allusions ni ces traits ; « allusion spontanée déjà faite aujourd'hui : oui » interdit \
            toute nouvelle allusion que Monsieur n'a pas amenée.

            # Retenir, oublier (lignes-marques, jamais dites ; jamais de délégation)
            - Monsieur dit « retiens que… », « souviens-toi que… » : dis « C'est noté : <le fait, court>. » et ajoute \
            la ligne seule « 📌 noter: <le fait, une phrase de 200 caractères au plus> ».
            - Il te confie une information personnelle, durable et utile (une naissance, un nouveau poste, un goût, \
            un congé) : tu peux proposer « Voulez-vous que je retienne que <fait> ? » avec la ligne « 📌 retenir: \
            <fait> ». Au plus UNE proposition par conversation ; jamais pour une tâche (« rappelle-moi de… », « n'oublie \
            pas de relancer… » ne sont pas des souvenirs) ; jamais ce qu'il dit d'un tiers sans lien avec sa vie ; \
            jamais dans la même réponse qu'une question « Vous confirmez ? » ou une invitation ↪.
            - « Oublie que… » : dis « C'est oublié. » et ajoute « 📌 oublier: S<n> » (le numéro du souvenir visé).
            - « Mettons à jour mon portrait » : ajoute la ligne « 📌 entretien ».
            - Le message peut porter « [Jarvis — relance du portrait possible …] » : tu peux alors, si le moment \
            s'y prête, poser une seule question « Du nouveau depuis notre dernier entretien ? » ; rien ne t'y oblige.""";

    // ---- SF-241-05 (D8, D15, D16) : le portrait vivant ----

    /** D15 — la question de relance, et la note volatile qui l'autorise. */
    public static final String CHECKIN_QUESTION = "Du nouveau depuis notre dernier entretien ?";
    public static final String CHECKIN_NOTE = "[Jarvis — relance du portrait possible : si le moment s'y prête, une "
            + "seule question, « " + CHECKIN_QUESTION + " » ; rien ne t'y oblige]";
    public static final int CHECKIN_DAYS = 90;

    /** D2 — la réponse au 31ᵉ souvenir, dite par l'écran. */
    public static final String NOTED = "C'est noté : ";
    public static final String FORGOTTEN = "C'est oublié.";
    public static final String NOTHING_TO_FORGET = "Je n'ai rien retenu de tel, Monsieur.";
    public static final String PRIVATE = "Nous en parlerons en privé, Monsieur.";

    private static final Pattern REMEMBER = Pattern.compile("(?iu)^\\s*(?:jarvis\\W+)?(?:retiens|retenez|"
            + "souviens[- ]toi|souvenez[- ]vous|garde en m[ée]moire)\\s+(?:bien\\s+)?(?:que|qu['’])\\s*(.+?)\\s*[.!]?$");
    private static final Pattern FORGET = Pattern.compile("(?iu)^\\s*(?:jarvis\\W+)?(?:oublie|oubliez)\\s+"
            + "(?:que|qu['’]|ce que je t['’]ai dit sur|ce que je vous ai dit sur)\\s*(.+?)\\s*[.!]?$");

    /** Une ligne-marque 📌 lue : {@code noter}, {@code retenir}, {@code oublier} ({@code number}) ou {@code entretien}. */
    public record Pin(String action, String text, Integer number) {
    }

    private static final Pattern PIN = Pattern.compile("(?iu)^\\s*\\x{1F4CC}\\s*(retenir|noter|oublier|entretien)"
            + "\\s*:?\\s*(.*)$");

    /** Lit une ligne 📌 ; illisible ⇒ {@code null}. */
    public static Pin pin(String line) {
        Matcher m = PIN.matcher(line == null ? "" : line.strip());
        if (!m.matches()) {
            return null;
        }
        String action = m.group(1).toLowerCase(Locale.ROOT);
        String rest = m.group(2).strip();
        if ("entretien".equals(action)) {
            return new Pin(action, null, null);
        }
        if ("oublier".equals(action)) {
            Matcher n = Pattern.compile("(?i)^S?\\s*(\\d{1,2})\\b").matcher(rest);
            return n.find() ? new Pin(action, null, Integer.parseInt(n.group(1))) : null;
        }
        String fact = JarvisPortraitRules.normalizeSouvenir(rest);
        if (fact.isEmpty()) {
            return null;
        }
        return new Pin(action, fact.length() > JarvisPortraitRules.MAX_SOUVENIR_CHARS
                ? fact.substring(0, JarvisPortraitRules.MAX_SOUVENIR_CHARS) : fact, null);
    }

    /** « Retiens que X » ⇒ X (le fait, sans ponctuation finale) ; sinon {@code null}. */
    public static String rememberedFact(String said) {
        Matcher m = REMEMBER.matcher(said == null ? "" : said);
        return m.matches() && !m.group(1).isBlank() ? m.group(1).strip() : null;
    }

    /** « Oublie que X » ⇒ X ; sinon {@code null}. */
    public static String forgetTarget(String said) {
        Matcher m = FORGET.matcher(said == null ? "" : said);
        return m.matches() && !m.group(1).isBlank() ? m.group(1).strip() : null;
    }

    /** Le souvenir que « oublie que X » vise : le plus de mots communs (≥ 4 lettres), au moins un ; sinon {@code null}. */
    public static JarvisPortraitService.Souvenir souvenirFor(String target, JarvisPortraitService.Snapshot s) {
        if (target == null || s == null) {
            return null;
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String w : words(target)) {
            if (w.length() >= 4 && !STOP.contains(w)) {
                wanted.add(w);
            }
        }
        JarvisPortraitService.Souvenir best = null;
        long bestScore = 0;
        for (JarvisPortraitService.Souvenir m : s.souvenirs()) {
            Set<String> have = words(m.text());
            long score = wanted.stream().filter(w -> have.contains(w)
                    || have.stream().anyMatch(h -> h.length() >= 5 && w.length() >= 5
                            && (h.startsWith(w) || w.startsWith(h)))).count();
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        return best;
    }

    /** D15 — vrai si la relance est due : ni relance ni entretien depuis {@value #CHECKIN_DAYS} jours. */
    public static boolean checkinDue(JarvisPortraitService.Snapshot s, OffsetDateTime now) {
        if (!usable(s)) {
            return false;
        }
        // La dernière fois que le portrait a été revu : relance, entretien ou écriture d'une section.
        OffsetDateTime last = null;
        List<OffsetDateTime> marks = new ArrayList<>();
        marks.add(s.lastCheckinAt());
        marks.add(s.lastInterviewAt());
        s.sections().values().forEach(t -> marks.add(t.updatedAt()));
        for (OffsetDateTime at : marks) {
            if (at != null && (last == null || at.isAfter(last))) {
                last = at;
            }
        }
        return last == null || !last.plusDays(CHECKIN_DAYS).isAfter(now);
    }

    /** Vrai si la phrase dite est la relance D15. */
    public static boolean isCheckin(String spoken) {
        return normalize(spoken).contains("du nouveau depuis notre dernier entretien");
    }

    /** Mots qui disent que le PO parle de sa propre vie : une allusion qui y répond n'est pas spontanée. */
    private static final Set<String> PERSONAL_WORDS = Set.of("fils", "fille", "enfant", "enfants", "petit", "petite",
            "bebe", "femme", "mari", "compagne", "compagnon", "epouse", "famille", "pere", "mere", "parents", "frere",
            "soeur", "creve", "fatigue", "fatiguee", "epuise", "vacances", "conge", "anniversaire", "malade", "sante",
            "hopital", "naissance", "mariage", "week", "weekend", "dimanche", "samedi", "sport", "course", "musique",
            "jazz", "lecture", "nuit", "nuits", "dormi", "dort", "maison", "chez");

    /** Les termes qui signent une allusion : noms propres des sections personnelles et des souvenirs. */
    public static Set<String> allusionTerms(JarvisPortraitService.Snapshot s) {
        if (s == null) {
            return Set.of();
        }
        StringBuilder personal = new StringBuilder();
        for (PortraitSection section : List.of(PortraitSection.ENTOURAGE, PortraitSection.MOMENT,
                PortraitSection.GOUTS, PortraitSection.DATES)) {
            personal.append(s.text(section)).append('\n');
        }
        s.souvenirs().forEach(m -> personal.append(m.text()).append('\n'));
        return properNames(personal.toString());
    }

    /**
     * Vrai si la parole du PO ouvre sa vie personnelle (un mot de la vie courante, ou un mot de ≥ 5 lettres de son
     * portrait) : la connaissance qui y répond est attendue, pas spontanée.
     */
    public static boolean personalSaid(String said, JarvisPortraitService.Snapshot s) {
        Set<String> heard = words(said);
        if (heard.stream().anyMatch(PERSONAL_WORDS::contains)) {
            return true;
        }
        if (s == null) {
            return false;
        }
        StringBuilder text = new StringBuilder();
        for (PortraitSection section : PortraitSection.values()) {
            if (section != PortraitSection.QUI && section != PortraitSection.INTERDITS) {
                text.append(s.text(section)).append(' ');
            }
        }
        s.souvenirs().forEach(m -> text.append(m.text()).append(' '));
        Set<String> portraitWords = words(text.toString());
        return heard.stream().anyMatch(w -> w.length() >= 5 && !STOP.contains(w) && portraitWords.contains(w));
    }

    /** Le terme d'allusion que la phrase reprend sans que le PO l'ait dit, ou {@code null}. */
    public static String allusionTerm(String sentence, String said, Set<String> terms) {
        if (terms == null || terms.isEmpty() || sentence == null) {
            return null;
        }
        Set<String> spoken = words(sentence);
        Set<String> heard = words(said);
        for (String term : terms) {
            if (spoken.contains(term) && !heard.contains(term)) {
                return term;
            }
        }
        return null;
    }

    /** D12 (c) — vrai si le terme figure (au mot près) dans un sujet récent du journal. */
    public static boolean repeats(String term, java.util.Collection<String> recentTopics) {
        if (term == null || recentTopics == null) {
            return false;
        }
        for (String topic : recentTopics) {
            if (topic.equals(term) || words(topic).contains(term)) {
                return true;
            }
        }
        return false;
    }

    // ---- D18 : le détecteur de livrable ----

    private static final Set<String> COMMON_CAPS = Set.of("monsieur", "madame", "jarvis", "bonjour", "merci",
            "janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre",
            "novembre", "decembre", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche");

    /**
     * Vrai si le texte d'un livrable (mail, message) reprend un élément du portrait : un nom propre d'une section
     * personnelle (entourage, moment, goûts, humour, interdits, dates) ou d'un souvenir, ou une suite de 4 mots en
     * commun. « Qui vous êtes » (métier, employeur) n'est pas compté : il est légitime dans un courrier professionnel.
     */
    public static boolean leaks(String text, JarvisPortraitService.Snapshot s) {
        if (text == null || text.isBlank() || s == null || !s.hasContent()) {
            return false;
        }
        StringBuilder personal = new StringBuilder();
        for (PortraitSection section : PortraitSection.values()) {
            if (section != PortraitSection.QUI) {
                personal.append(s.text(section)).append('\n');
            }
        }
        s.souvenirs().forEach(m -> personal.append(m.text()).append('\n'));
        Set<String> body = words(text);
        for (String name : properNames(personal.toString())) {
            if (body.contains(name)) {
                return true;
            }
        }
        Set<String> shingles = shingles(normalize(personal.toString()));
        for (String sh : shingles(normalize(text))) {
            if (shingles.contains(sh)) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern CAPITALIZED = Pattern.compile("(?<![.!?\\n]\\s)(?<!^)\\b(\\p{Lu}[\\p{L}'-]{2,})");

    /** Les noms propres (mots capitalisés hors début de phrase), normalisés, sans mots courants. */
    static Set<String> properNames(String text) {
        Set<String> names = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            Matcher m = CAPITALIZED.matcher(line);
            while (m.find()) {
                if (m.start() == 0) {
                    continue;
                }
                String n = normalize(m.group(1));
                if (n.length() >= 3 && !COMMON_CAPS.contains(n) && !STOP.contains(n)) {
                    names.add(n);
                }
            }
        }
        return names;
    }

    private static Set<String> shingles(String normalized) {
        Set<String> out = new LinkedHashSet<>();
        String[] w = normalized.isBlank() ? new String[0] : normalized.split(" ");
        for (int i = 0; i + 4 <= w.length; i++) {
            out.add(w[i] + " " + w[i + 1] + " " + w[i + 2] + " " + w[i + 3]);
        }
        return out;
    }

    static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : normalize(text).split(" ")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }

    /** Minuscules, sans accents, lettres et chiffres seulement, espaces repliés. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String n = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.replaceAll("[^a-z0-9]+", " ").strip();
    }
}
