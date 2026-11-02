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
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String n = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.replaceAll("[^a-z0-9]+", " ").strip();
    }
}
