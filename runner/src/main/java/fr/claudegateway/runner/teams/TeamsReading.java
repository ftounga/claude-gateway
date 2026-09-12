package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>L'enveloppe que rend toute lecture Teams</b> (F-87 / SF-87-01) : ce qui a été lu, <b>ce qui ne
 * l'a pas été</b>, la fenêtre réellement couverte, et ce que vaut la lecture.
 *
 * <p>Aucun outil du volet ne rend une liste nue. C'est la traduction en code de la règle qui prime :
 * « 47 messages lus, 3 non reconnus, du 5 au 12 septembre » — un trou se voit, un trou silencieux ne
 * se voit jamais.</p>
 *
 * @param <T> genre d'objet lu ({@link TeamsMessage}, {@link TeamsConversation}…)
 */
public record TeamsReading<T>(List<T> items, List<TeamsGap> gaps, TeamsReadWindow window,
        TeamsHealth health) {

    public TeamsReading {
        items = items == null ? List.of() : List.copyOf(items);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        health = health == null ? TeamsHealth.full(0) : health;
    }

    /** Lecture vide et sans défaut : rien à lire n'est pas une panne. */
    public static <T> TeamsReading<T> empty(TeamsReadWindow window) {
        return new TeamsReading<>(List.of(), List.of(), window, TeamsHealth.full(0));
    }

    /** Lecture refusée : rien n'a été reconnu, et la santé le dit. */
    public static <T> TeamsReading<T> unreadable(TeamsReadWindow window, TeamsGap gap,
            TeamsHealth health) {
        return new TeamsReading<>(List.of(), List.of(gap), window, health);
    }

    /** Vrai si <b>rien</b> n'a manqué. Faux dès qu'un seul manque existe. */
    public boolean complete() {
        return gaps.isEmpty() && (window == null || window.fullyCovered());
    }

    /** Nombre d'éléments que l'on sait ne pas avoir lus. */
    public int missedCount() {
        return gaps.stream().mapToInt(TeamsGap::count).sum();
    }

    /**
     * La phrase du cadrage, écrite à côté de chaque résultat :
     * « 47 messages lus, 3 non reconnus, du 5 au 12 septembre ».
     *
     * @param noun nom de ce qui est lu, au pluriel (« messages », « conversations »)
     */
    public String summary(String noun) {
        String label = noun == null || noun.isBlank() ? "éléments" : noun.strip();
        StringBuilder text = new StringBuilder();
        text.append(items.size()).append(' ').append(label).append(" lus");
        int missed = missedCount();
        if (missed > 0) {
            text.append(", ").append(missed).append(" non lus");
        }
        if (window != null) {
            text.append(", ").append(window.describe());
        }
        text.append('.');
        if (window != null && window.capReached()) {
            text.append(" Plafond de ").append(window.cap())
                    .append(" atteint : ce qui précède n'a pas été lu — demandez une période"
                            + " explicite pour remonter plus loin.");
        }
        if (!gaps.isEmpty()) {
            List<String> described = new ArrayList<>();
            gaps.forEach(gap -> described.add(gap.describe()));
            text.append(" Ce qui n'a pas pu être lu : ").append(String.join(" ; ", described))
                    .append('.');
        }
        if (health.verdict() != TeamsHealthVerdict.FULL) {
            text.append(' ').append(health.describe());
        }
        return text.toString();
    }

    /** Confort : « éléments » quand le genre n'apporte rien. */
    public String summary() {
        return summary("éléments");
    }

    /** Mêmes éléments, un manque de plus. Les manques identiques se cumulent au lieu de se répéter. */
    public TeamsReading<T> withGap(TeamsGap gap) {
        if (gap == null) {
            return this;
        }
        List<TeamsGap> merged = new ArrayList<>();
        boolean folded = false;
        for (TeamsGap existing : gaps) {
            if (!folded && existing.kind() == gap.kind() && existing.where().equals(gap.where())
                    && existing.detail().equals(gap.detail())) {
                merged.add(new TeamsGap(existing.kind(), existing.where(), existing.detail(),
                        existing.count() + gap.count()));
                folded = true;
            } else {
                merged.add(existing);
            }
        }
        if (!folded) {
            merged.add(gap);
        }
        return new TeamsReading<>(items, merged, window, health);
    }
}
