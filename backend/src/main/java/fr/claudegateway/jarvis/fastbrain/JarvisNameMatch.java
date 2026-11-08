package fr.claudegateway.jarvis.fastbrain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * <b>Un nom dit, rapproché d'un nom connu</b> (F-253 / SF-253-02, SF-253-03) : la retranscription écrit « Kajip »,
 * « agénor », « data platform » ; l'univers du compte connaît « CAGIP », « agenor », « data-platform ». Pur, sans IA :
 * minuscules, accents, ponctuation et espaces retirés, puis un repli phonétique français léger (c/q → k, g → j, y → i,
 * h muet, lettres doublées) et une distance d'édition bornée (≤ 1, ≤ 2 à partir de 8 lettres).
 *
 * <p>Ordre de préférence : égalité exacte, puis égalité phonétique, puis inclusion (le nom dit contient le nom connu ou
 * l'inverse, ≥ 4 lettres), puis distance. Deux candidats au même rang ⇒ {@link Result#ambiguous()}.</p>
 */
public final class JarvisNameMatch {

    private JarvisNameMatch() {
    }

    /** Le résultat : l'élément trouvé, ou les candidats ambigus, ou rien. */
    public record Result<T>(T found, List<T> ambiguous) {

        public boolean none() {
            return found == null && ambiguous.isEmpty();
        }
    }

    /** Rapproche {@code said} d'un élément de {@code items} nommé par {@code name}. Ne lève jamais. */
    public static <T> Result<T> match(String said, List<T> items, Function<T, String> name) {
        String wanted = compact(said);
        if (wanted.isEmpty() || items == null || items.isEmpty()) {
            return new Result<>(null, List.of());
        }
        String wantedSound = sound(wanted);
        for (int rank = 0; rank < 4; rank++) {
            List<T> hits = new ArrayList<>();
            for (T item : items) {
                String known = compact(name.apply(item));
                if (!known.isEmpty() && matches(rank, wanted, wantedSound, known)) {
                    hits.add(item);
                }
            }
            if (hits.size() == 1) {
                return new Result<>(hits.get(0), List.of());
            }
            if (hits.size() > 1) {
                // Au même rang, le plus proche en longueur l'emporte s'il est seul (« helio » ⇒ « helios »).
                int best = hits.stream().mapToInt(h -> Math.abs(compact(name.apply(h)).length() - wanted.length()))
                        .min().orElse(0);
                List<T> closest = hits.stream()
                        .filter(h -> Math.abs(compact(name.apply(h)).length() - wanted.length()) == best).toList();
                return closest.size() == 1 ? new Result<>(closest.get(0), List.of())
                        : new Result<>(null, List.copyOf(hits));
            }
        }
        return new Result<>(null, List.of());
    }

    private static boolean matches(int rank, String wanted, String wantedSound, String known) {
        return switch (rank) {
            case 0 -> wanted.equals(known);
            case 1 -> wantedSound.equals(sound(known));
            case 2 -> Math.min(wanted.length(), known.length()) >= 4
                    && (wanted.contains(known) || known.contains(wanted));
            default -> {
                String k = sound(known);
                int max = Math.max(wantedSound.length(), k.length()) >= 8 ? 2 : 1;
                yield Math.min(wantedSound.length(), k.length()) >= 4 && distance(wantedSound, k, max) <= max;
            }
        };
    }

    /** Minuscules, sans accents ni rien d'autre que lettres et chiffres. */
    static String compact(String text) {
        return JarvisFastGuards.normalize(text).replace(" ", "");
    }

    /** Le repli phonétique (sur un nom déjà compacté). */
    static String sound(String compact) {
        String s = compact.replace("ph", "f").replace("qu", "k").replace("ck", "k").replace('q', 'k').replace('c', 'k')
                .replace('g', 'j').replace('y', 'i').replace("h", "");
        StringBuilder out = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (out.isEmpty() || out.charAt(out.length() - 1) != ch) {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /** Distance de Levenshtein, coupée au-delà de {@code max} (rend {@code max + 1}). */
    static int distance(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) {
            return max + 1;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int best = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                best = Math.min(best, cur[j]);
            }
            if (best > max) {
                return max + 1;
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
