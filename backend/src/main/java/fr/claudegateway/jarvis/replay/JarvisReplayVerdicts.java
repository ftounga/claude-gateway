package fr.claudegateway.jarvis.replay;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fr.claudegateway.jarvis.replay.JarvisReplay.Bilan;
import fr.claudegateway.jarvis.replay.JarvisReplay.Delegation;
import fr.claudegateway.jarvis.replay.JarvisReplay.Show;
import fr.claudegateway.jarvis.replay.JarvisReplay.Turn;
import fr.claudegateway.jarvis.replay.JarvisReplay.Verdict;

/**
 * <b>Les verdicts du rejeu</b> (F-255 / SF-255-02, cadrage §3) — <b>sans IA</b> : des règles écrites, sur ce que le
 * rejeu a assemblé. Un tour peut porter plusieurs verdicts ; le bilan les compte par séance.
 */
public final class JarvisReplayVerdicts {

    /** Au-delà, le premier son est lent (seuil du PO : 3 s après la fin de parole). */
    public static final int SLOW_FIRST_SOUND_MS = 3_000;
    /** Une réponse identique à l'une des réponses de ce nombre de tours précédents est une répétition. */
    public static final int REPEAT_WINDOW = 5;

    public static final String ENTENDU = "ENTENDU";
    public static final String PERDU = "PERDU";
    public static final String REPETE = "REPETE";
    public static final String LENT = "LENT";
    public static final String MAUVAIS_SUJET = "MAUVAIS_SUJET";
    public static final String MONTRE = "MONTRE";
    public static final String VOICI_SANS_AFFICHAGE = "VOICI_SANS_AFFICHAGE";
    public static final String DELEGATION_MUETTE = "DELEGATION_MUETTE";
    public static final String REPLI_TRANSCRIPTION = "REPLI_TRANSCRIPTION";
    public static final String MESURE_ABSENTE = "MESURE_ABSENTE";

    /** Une réponse qui annonce un affichage (« nous voici chez CAGIP » n'en est pas une). */
    static final Pattern ANNOUNCES_SHOW = Pattern.compile(
            "(?<!\\bnous )(?<!\\bme )\\b(voici|voila|je vous (le |la |les )?montre|a l ecran|s affiche)\\b");
    /** Une réponse qui annonce un changement de sujet ; le sujet est cherché juste après. */
    static final Pattern ANNOUNCES_SWITCH = Pattern.compile(
            "\\b(nous (passons|voici|restons|sommes|allons|repartons)|passons|allons|repartons|basculons|restons) "
                    + "(sur|chez|dans|a|au) (.*)");

    private JarvisReplayVerdicts() {
    }

    /**
     * Juge les tours d'une même fenêtre, dans l'ordre : numérotés à partir de 1, verdicts posés.
     *
     * @param names    les sujets du compte (identifiant → nom)
     * @param threadId le fil Jarvis du compte (jamais un « sujet courant »), ou {@code null}
     */
    public static List<Turn> judge(List<Turn> turns, Map<UUID, String> names, UUID threadId) {
        List<Turn> judged = new ArrayList<>();
        List<String> replies = new ArrayList<>();
        UUID current = null;
        for (int i = 0; i < turns.size(); i++) {
            Turn t = turns.get(i);
            List<Verdict> v = new ArrayList<>();
            String userText = t.heard().userText() != null ? t.heard().userText() : t.heard().finalText();
            boolean lost = t.heard().rejected() != null || (t.heard().chars() != null && t.heard().chars() == 0
                    && userText == null);
            if (lost) {
                v.add(new Verdict(PERDU, t.heard().rejected() == null ? "transcription vide"
                        : "parole refusée : " + t.heard().rejected()));
            } else if ((t.heard().chars() != null && t.heard().chars() > 0) || userText != null) {
                v.add(new Verdict(ENTENDU, null));
            }
            // Répété : la même réponse que l'un des tours précédents de la fenêtre.
            String reply = norm(t.reply());
            if (!reply.isEmpty()) {
                int back = replies.lastIndexOf(reply);
                if (back >= 0 && replies.size() - back <= REPEAT_WINDOW) {
                    long times = replies.stream().filter(reply::equals).count() + 1;
                    v.add(new Verdict(REPETE, "même réponse, " + times + "ᵉ fois"));
                }
                replies.add(reply);
            }
            Integer firstSound = t.steps().firstSoundMs();
            if (firstSound != null && firstSound > SLOW_FIRST_SOUND_MS) {
                v.add(new Verdict(LENT, String.format(Locale.FRANCE, "premier son à %.1f s", firstSound / 1000.0)));
            }
            // Mauvais sujet : la délégation part ailleurs que le sujet courant (dernier sujet nommé par le PO ou annoncé
            // par Jarvis, sinon le sujet de l'écran), sauf si la demande nomme elle-même la cible.
            UUID named = lastNamed(userText, names, threadId);
            UUID expected = named != null ? named : current != null ? current
                    : t.screenSubject() == null ? null : t.screenSubject().id();
            for (Delegation d : t.delegations()) {
                UUID target = d.subject() == null ? null : d.subject().id();
                if (target == null || target.equals(threadId) || expected == null || target.equals(expected)) {
                    continue;
                }
                if (names(d.label(), names, target) || names(userText, names, target)) {
                    continue;
                }
                v.add(new Verdict(MAUVAIS_SUJET, "parti vers « " + nameOf(target, names) + " », sujet courant « "
                        + nameOf(expected, names) + " »" + (t.screenSubject() == null ? ""
                                : ", écran « " + nameOf(t.screenSubject().id(), names) + " »")));
            }
            UUID announced = announced(t.reply(), names, threadId);
            if (named != null) {
                current = named;
            }
            if (announced != null) {
                current = announced;
            }
            // Ce qui a été montré, et « Voici » sans rien montrer.
            List<Show> applied = t.shows().stream().filter(s -> !s.refused()).toList();
            if (!applied.isEmpty()) {
                v.add(new Verdict(MONTRE, String.join(", ", applied.stream().map(Show::action).toList())));
            } else if (ANNOUNCES_SHOW.matcher(norm(t.reply())).find()) {
                Show refused = t.shows().stream().filter(Show::refused).findFirst().orElse(null);
                v.add(new Verdict(VOICI_SANS_AFFICHAGE, refused == null ? "aucun ordre d'écran"
                        : "refusé : " + refused.action() + (refused.reason() == null ? "" : " " + refused.reason())));
            }
            if (!t.delegations().isEmpty() && t.saidChars() != null && t.saidChars() == 0) {
                v.add(new Verdict(DELEGATION_MUETTE, "délégation partie, rien n'a été dit"));
            }
            if (!t.streamFallbacks().isEmpty()) {
                v.add(new Verdict(REPLI_TRANSCRIPTION, String.join(", ", t.streamFallbacks())));
            }
            if (!t.measured()) {
                v.add(new Verdict(MESURE_ABSENTE, "aucune mesure de tour reçue"));
            }
            judged.add(new Turn(i + 1, t.turnId(), t.pageId(), t.correlation(), t.at(), t.listenMode(), t.heard(),
                    t.steps(), t.brain(), t.speculation(), t.delegationReason(), t.emptyDiagnostic(), t.saidChars(),
                    t.reply(), t.screenSubject(), t.delegations(), t.shows(), t.streamFallbacks(), t.streamError(),
                    t.measured(), List.copyOf(v), t.events()));
        }
        return judged;
    }

    /** Le bilan d'une séance jugée. */
    public static Bilan bilan(List<Turn> turns) {
        int n = turns.size();
        int heard = count(turns, ENTENDU);
        int lost = count(turns, PERDU);
        int fallbacks = count(turns, REPLI_TRANSCRIPTION);
        List<Integer> sounds = turns.stream().map(t -> t.steps().firstSoundMs()).filter(Objects::nonNull).sorted()
                .toList();
        return new Bilan(n, heard, lost, rate(lost, n), count(turns, REPETE), count(turns, LENT),
                percentile(sounds, 50), percentile(sounds, 90), count(turns, MAUVAIS_SUJET), count(turns, MONTRE),
                count(turns, VOICI_SANS_AFFICHAGE), turns.stream().mapToInt(t -> t.delegations().size()).sum(),
                count(turns, DELEGATION_MUETTE), fallbacks, rate(fallbacks, n), count(turns, MESURE_ABSENTE));
    }

    private static int count(List<Turn> turns, String code) {
        return (int) turns.stream().filter(t -> t.verdicts().stream().anyMatch(v -> code.equals(v.code()))).count();
    }

    private static Double rate(int part, int whole) {
        return whole == 0 ? null : Math.round(1000.0 * part / whole) / 1000.0;
    }

    /** Rang le plus proche : la valeur au rang ⌈p·n⌉. */
    static Integer percentile(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) {
            return null;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    // ---- Sujets nommés ----

    /** Le dernier sujet du compte nommé dans le texte (par position), hors fil Jarvis ; {@code null} sinon. */
    static UUID lastNamed(String text, Map<UUID, String> names, UUID threadId) {
        String t = " " + norm(text) + " ";
        UUID found = null;
        int at = -1;
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            String name = norm(e.getValue());
            if (e.getKey().equals(threadId) || name.length() < 3) {
                continue;
            }
            int i = t.lastIndexOf(" " + name + " ");
            if (i > at) {
                at = i;
                found = e.getKey();
            }
        }
        return found;
    }

    private static boolean names(String text, Map<UUID, String> names, UUID target) {
        String name = norm(names.get(target));
        return !name.isEmpty() && (" " + norm(text) + " ").contains(" " + name + " ");
    }

    /** Le sujet qu'une réponse annonce (« Nous passons sur data-platform »), sinon {@code null}. */
    static UUID announced(String reply, Map<UUID, String> names, UUID threadId) {
        Matcher m = ANNOUNCES_SWITCH.matcher(norm(reply));
        if (!m.find()) {
            return null;
        }
        String after = m.group(4);
        String head = after.length() > 40 ? after.substring(0, 40) : after;
        UUID best = null;
        int at = Integer.MAX_VALUE;
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            String name = norm(e.getValue());
            if (e.getKey().equals(threadId) || name.length() < 3) {
                continue;
            }
            int i = (" " + head + " ").indexOf(" " + name + " ");
            if (i >= 0 && i < at) {
                at = i;
                best = e.getKey();
            }
        }
        return best;
    }

    private static String nameOf(UUID id, Map<UUID, String> names) {
        String name = names.get(id);
        return name == null ? id.toString().substring(0, 8) : name;
    }

    /** Minuscules, sans accents ni ponctuation, espaces simples. */
    static String norm(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return n.replaceAll("[^a-z0-9]+", " ").trim();
    }
}
