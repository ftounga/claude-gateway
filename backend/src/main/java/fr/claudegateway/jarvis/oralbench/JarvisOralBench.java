package fr.claudegateway.jarvis.oralbench;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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
            boolean confirmation, String annexKeyword) {
    }

    /** Ce que la réponse d'Opus a dit, lu sans modèle. */
    public record Reading(String oral, int words, String invitation, boolean genericInvitation, int essentialLines,
            boolean asksConfirmation) {
    }

    private JarvisOralBench() {
    }

    /** Le jeu figé. */
    public static List<Scenario> load() {
        try (InputStream in = JarvisOralBench.class.getResourceAsStream("/jarvis/oral-bench-scenarios.json")) {
            JsonNode root = JSON.readTree(in);
            List<Scenario> scenarios = new ArrayList<>();
            for (JsonNode s : root.get("scenarios")) {
                scenarios.add(new Scenario(s.get("id").asText(), s.get("category").asText(), s.get("said").asText(),
                        s.get("context").asText(), s.path("invitation").asText("any"),
                        s.path("confirmation").asBoolean(false),
                        s.hasNonNull("annexKeyword") ? s.get("annexKeyword").asText() : null));
            }
            return List.copyOf(scenarios);
        } catch (Exception e) {
            throw new IllegalStateException("Jeu du banc de la parole illisible", e);
        }
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
