package fr.claudegateway.jarvis.oralbench;

import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.jarvis.JarvisOralNote;
import fr.claudegateway.jarvis.JarvisReplies;
import fr.claudegateway.jarvis.JarvisSpokenWords;
import fr.claudegateway.jarvis.fastbrain.JarvisFastGuards;

/**
 * <b>Le banc de la parole — la partie pure</b> (F-240 / SF-240-02, D12) : le jeu (8 scénarios de délégation,
 * contexte figé), la lecture déterministe d'une réponse d'Opus (mots de la ligne 🎙, invitation « ↪ », lignes de
 * l'essentiel, confirmation demandée) et la porte.
 *
 * <p><b>Porte</b> : p90 des mots de la ligne 🎙 ≤ {@value #MAX_P90_WORDS} ; verdict d'abord dans au moins
 * {@value #MIN_VERDICT_FIRST} scénarios sur 8 (juge) ; zéro invitation générique ; invitation présente dans le scénario
 * « point annexe important », absente dans le scénario « confirmation » ; tous les scénarios joués et jugés.</p>
 */
public final class JarvisOralBench {

    static final int MAX_P90_WORDS = 30;
    static final int MIN_VERDICT_FIRST = 7;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ESSENTIAL = Pattern.compile("<<\\s*essentiel\\s*>>(.*?)<<\\s*/\\s*essentiel\\s*>>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MARKUP = Pattern.compile("^[#>*\\-+\\s]+|[*_`]+");

    /** Un scénario : la parole, le contexte figé, ce qu'on attend de l'invitation et de la confirmation. */
    public record Scenario(String id, String category, String said, String context, String invitation,
            boolean confirmation, String annexKeyword, java.util.Map<String, String> portrait) {

        public Scenario(String id, String category, String said, String context, String invitation,
                boolean confirmation, String annexKeyword) {
            this(id, category, said, context, invitation, confirmation, annexKeyword, null);
        }

        /** F-241 / SF-241-03 — le portrait FICTIF du scénario (jamais celui d'un compte), ou {@code null}. */
        public fr.claudegateway.jarvis.portrait.JarvisPortraitService.Snapshot fixture() {
            return portrait == null ? null : fr.claudegateway.jarvis.portrait.JarvisPortraitNote.fixture(portrait,
                    List.of(), java.time.OffsetDateTime.parse("2026-10-01T09:00:00Z"));
        }
    }

    /**
     * F-241 / SF-241-03 (D18, D12 a) — les défauts personnels d'une réponse d'Opus, lus sans modèle : un encart mail ou
     * message qui reprend le portrait (fuite), ou une ligne 🎙 qui évoque un sujet interdit non amené.
     */
    public static List<String> personalFailures(Scenario s, String reply, Reading reading) {
        List<String> failures = new ArrayList<>();
        fr.claudegateway.jarvis.portrait.JarvisPortraitService.Snapshot p = s.fixture();
        if (p == null) {
            return failures;
        }
        for (fr.claudegateway.atelier.sends.MessageBlocks.Block b
                : fr.claudegateway.atelier.sends.MessageBlocks.parse(reply == null ? "" : reply)) {
            String text = (b.subject() == null ? "" : b.subject() + "\n") + b.body();
            if (fr.claudegateway.jarvis.portrait.JarvisPortraitNote.leaks(text, p)) {
                failures.add(s.id() + " : élément personnel dans un encart (fuite du portrait)");
                break;
            }
        }
        if (reading != null && reading.oral() != null && fr.claudegateway.jarvis.portrait.JarvisPortraitNote.blocked(
                reading.oral(), s.said(), fr.claudegateway.jarvis.portrait.JarvisPortraitNote.forbiddenTerms(p))) {
            failures.add(s.id() + " : sujet interdit évoqué");
        }
        return failures;
    }

    /** Ce que la réponse d'Opus a dit, lu sans modèle. */
    public record Reading(String oral, int words, String invitation, boolean genericInvitation, int essentialLines,
            boolean asksConfirmation) {
    }

    private JarvisOralBench() {
    }

    /**
     * F-240 / SF-240-06 — le jeu daté par rapport à {@code today} : chaque jeton de date de la demande et du contexte
     * est résolu (voir {@link #dated}). Le banc ne dépend ainsi jamais du jour où il tourne.
     */
    public static List<Scenario> load(LocalDate today) {
        return load().stream().map(s -> new Scenario(s.id(), s.category(), dated(s.said(), today),
                dated(s.context(), today), s.invitation(), s.confirmation(), s.annexKeyword(), s.portrait()))
                .toList();
    }

    private static final Pattern DATE_TOKEN = Pattern.compile("\\{([JOM])([+-]\\d{1,2})?(?::([a-z0-9]+))?}");
    private static final Pattern ANY_TOKEN = Pattern.compile("\\{[^{}\\s]*}");
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

    /**
     * F-240 / SF-240-06 — résout les jetons de date d'un texte du jeu par rapport à {@code today} : {@code {J±n}} (jour
     * calendaire, « jj/MM »), {@code {O±n}} (n-ième jour ouvré, lundi→vendredi), suffixe {@code :jour} (« lundi ») ;
     * {@code {M±n}} (nom du mois, « octobre »), {@code {M±n:jj}} (« jj/MM » de ce mois, jour borné). Un jeton inconnu
     * ou mal formé fait échouer le chargement : jamais un jeton dit tel quel au modèle.
     */
    public static String dated(String text, LocalDate today) {
        if (text == null) {
            return null;
        }
        Matcher m = DATE_TOKEN.matcher(text);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(b, Matcher.quoteReplacement(resolve(m.group(1),
                    m.group(2) == null ? 0 : Integer.parseInt(m.group(2)), m.group(3), today, m.group())));
        }
        m.appendTail(b);
        String out = b.toString();
        if (ANY_TOKEN.matcher(out).find()) {
            throw new IllegalStateException("Jeton de date inconnu dans le jeu : " + text);
        }
        return out;
    }

    private static String resolve(String kind, int n, String suffix, LocalDate today, String token) {
        if ("M".equals(kind)) {
            YearMonth month = YearMonth.from(today).plusMonths(n);
            if (suffix == null) {
                return month.getMonth().getDisplayName(TextStyle.FULL, Locale.FRENCH);
            }
            if (!suffix.matches("\\d{1,2}")) {
                throw new IllegalStateException("Jeton de date inconnu dans le jeu : " + token);
            }
            return month.atDay(Math.min(Math.max(1, Integer.parseInt(suffix)), month.lengthOfMonth()))
                    .format(DAY_MONTH);
        }
        LocalDate date = "J".equals(kind) ? today.plusDays(n) : workingDay(today, n);
        if (suffix == null) {
            return date.format(DAY_MONTH);
        }
        if (!"jour".equals(suffix)) {
            throw new IllegalStateException("Jeton de date inconnu dans le jeu : " + token);
        }
        return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRENCH);
    }

    /** Le n-ième jour ouvré (lundi→vendredi) après (n &gt; 0) ou avant (n &lt; 0) {@code today}. */
    static LocalDate workingDay(LocalDate today, int n) {
        LocalDate d = today;
        int step = n >= 0 ? 1 : -1;
        for (int left = Math.abs(n); left > 0;) {
            d = d.plusDays(step);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                left--;
            }
        }
        return d;
    }

    /** Le jeu figé, jetons de date non résolus ({@link #load(LocalDate)} pour le jouer). */
    public static List<Scenario> load() {
        try (InputStream in = JarvisOralBench.class.getResourceAsStream("/jarvis/oral-bench-scenarios.json")) {
            JsonNode root = JSON.readTree(in);
            List<Scenario> scenarios = new ArrayList<>();
            for (JsonNode s : root.get("scenarios")) {
                scenarios.add(new Scenario(s.get("id").asText(), s.get("category").asText(), s.get("said").asText(),
                        s.get("context").asText(), s.path("invitation").asText("any"),
                        s.path("confirmation").asBoolean(false),
                        s.hasNonNull("annexKeyword") ? s.get("annexKeyword").asText() : null, portrait(s)));
            }
            return List.copyOf(scenarios);
        } catch (Exception e) {
            throw new IllegalStateException("Jeu du banc de la parole illisible", e);
        }
    }

    private static java.util.Map<String, String> portrait(JsonNode s) {
        if (!s.hasNonNull("portrait")) {
            return null;
        }
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        s.get("portrait").fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        return map;
    }

    /** Le message du tour : le contexte figé (ce que les outils auraient rendu), la parole, la consigne orale réelle. */
    public static String message(Scenario s, String oralNoteAppended) {
        return "[Banc de la parole — résultats déjà obtenus par tes outils pour cette demande ; tu n'as aucun outil, "
                + "réponds avec ce qui suit.]\n" + s.context() + "\n\n" + oralNoteAppended;
    }

    /** Lit une réponse d'Opus : la ligne 🎙 (entière, sans coupe), l'invitation, l'essentiel, la confirmation. */
    public static Reading read(String reply) {
        String oral = null;
        String invitation = null;
        boolean inFence = false;
        for (String raw : (reply == null ? "" : reply).split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            if (oral == null && line.startsWith(JarvisReplies.ORAL_MARK)) {
                oral = clean(line.substring(JarvisReplies.ORAL_MARK.length()));
            } else if (invitation == null && line.startsWith(JarvisOralNote.INVITATION_MARK)) {
                invitation = clean(line.substring(JarvisOralNote.INVITATION_MARK.length()));
            }
        }
        int essentialLines = 0;
        Matcher m = ESSENTIAL.matcher(reply == null ? "" : reply);
        if (m.find()) {
            essentialLines = (int) m.group(1).lines().filter(l -> !l.isBlank()).count();
        }
        boolean asks = oral != null && oral.strip().toLowerCase().matches(".*(vous confirmez|tu confirmes)\\s*\\?\\s*$");
        return new Reading(oral, JarvisSpokenWords.count(oral), invitation,
                invitation != null && isGenericInvitation(invitation), essentialLines, asks);
    }

    /**
     * Une invitation générique (« voulez-vous plus de détails ? ») — même règle que l'écran
     * ({@code isGenericInvitation}, {@code jarvis-oral.ts}), qui l'écarte avant de la dire.
     */
    public static boolean isGenericInvitation(String text) {
        String n = JarvisFastGuards.normalize(text).replaceAll("\\bmonsieur\\b", "").replaceAll("\\s+", " ").strip();
        if (n.isEmpty()) {
            return true;
        }
        return JarvisFastGuards.isDetailOffer(text)
                || n.matches("^(je peux|je puis|puis je) (vous )?(en )?(detailler|donner le detail|preciser|developper|"
                        + "vous en dire plus|en dire plus|entrer dans le detail|continuer).*")
                || n.matches("^(souhaitez vous|voulez vous|desirez vous|vous voulez) (le|les|plus de|davantage de|"
                        + "d autres|des) (detail|precision|information|explication).*")
                || n.matches("^(souhaitez vous|voulez vous|desirez vous|vous voulez) (en savoir plus|que je (detaille|"
                        + "developpe|precise|continue|vous en dise plus|poursuive)|la suite|le reste).*")
                || n.matches("^(autre chose|d autres questions|une autre question|des questions)\\b.*")
                || n.matches(".*\\b(plus de details|en savoir plus|le detail complet|davantage de details)\\b.*");
    }

    /** Percentile au rang le plus proche (mots de la ligne 🎙). */
    public static Integer percentile(List<Integer> words, int p) {
        return JarvisSpokenWords.percentile(words.stream().sorted().toList(), p);
    }

    /**
     * La porte : {@code verdictFirst} (nombre de scénarios jugés « verdict d'abord ») et les lectures, dans l'ordre du
     * jeu. Rend la liste des échecs (vide = porte au vert).
     */
    public static List<String> gate(List<Scenario> scenarios, List<Reading> readings, int judged, int verdictFirst) {
        List<String> failures = new ArrayList<>();
        if (readings.size() < scenarios.size()) {
            failures.add((scenarios.size() - readings.size()) + " scénario(s) non joué(s)");
        }
        if (judged < readings.size()) {
            failures.add((readings.size() - judged) + " scénario(s) non jugé(s)");
        }
        List<Integer> words = readings.stream().filter(r -> r.oral() != null).map(Reading::words).toList();
        long missing = readings.stream().filter(r -> r.oral() == null).count();
        if (missing > 0) {
            failures.add(missing + " réponse(s) sans ligne 🎙");
        }
        Integer p90 = percentile(words, 90);
        if (p90 == null || p90 > MAX_P90_WORDS) {
            failures.add("p90 des mots dits " + p90 + " > " + MAX_P90_WORDS);
        }
        if (verdictFirst < Math.min(MIN_VERDICT_FIRST, scenarios.size())) {
            failures.add("verdict d'abord " + verdictFirst + "/" + scenarios.size() + " < " + MIN_VERDICT_FIRST);
        }
        for (int i = 0; i < readings.size(); i++) {
            Scenario s = scenarios.get(i);
            Reading r = readings.get(i);
            if (r.genericInvitation()) {
                failures.add(s.id() + " : invitation générique");
            }
            if ("required".equals(s.invitation()) && (r.invitation() == null || r.genericInvitation())) {
                failures.add(s.id() + " : invitation ciblée absente");
            }
            if ("forbidden".equals(s.invitation()) && r.invitation() != null) {
                failures.add(s.id() + " : invitation sur une confirmation");
            }
            if (s.confirmation() && !r.asksConfirmation()) {
                failures.add(s.id() + " : confirmation non demandée");
            }
        }
        return failures;
    }

    private static String clean(String line) {
        return MARKUP.matcher(line.replaceAll("<<\\s*/?\\s*[a-zA-Zéè]+\\s*>>", "")).replaceAll("").strip();
    }
}
