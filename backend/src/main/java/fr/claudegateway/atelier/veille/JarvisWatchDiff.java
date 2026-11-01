package fr.claudegateway.atelier.veille;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import fr.claudegateway.atelier.veille.JarvisWatchItems.Item;

/**
 * <b>Ce qui a bougé entre deux passages</b> (F-230 / SF-230-02, D3) : la comparaison, élément par élément, de la
 * liste normalisée avec l'instantané précédent. Calcul pur — c'est lui qui rend un passage gratuit quand rien n'a
 * changé, et c'est <b>lui seul</b> que l'IA lira (D4).
 */
public final class JarvisWatchDiff {

    /** Nature d'un changement, liste fermée. */
    public enum Kind {
        /** Entré dans le périmètre (nouveau ticket, nouvellement assigné…). */
        NEW,
        /** Sorti du périmètre (passé en « done », désassigné…). */
        GONE,
        /** Statut changé. */
        STATUS,
        /** Assigné changé. */
        ASSIGNEE,
        /** Nouveau dernier commentaire. */
        COMMENT,
        /** Autre modification (titre, date de mise à jour). */
        UPDATED
    }

    /** Un changement : {@code before}/{@code after} portent la valeur qui a changé (vides pour NEW/GONE). */
    public record Change(Kind kind, String id, String title, String before, String after) {
    }

    private JarvisWatchDiff() {
    }

    /** Les changements, dans l'ordre : nouveaux, modifiés, sortis. Vide = rien n'a bougé. */
    public static List<Change> between(List<Item> previous, List<Item> current) {
        Map<String, Item> before = index(previous);
        Map<String, Item> after = index(current);
        List<Change> news = new ArrayList<>();
        List<Change> changed = new ArrayList<>();
        List<Change> gone = new ArrayList<>();
        for (Item item : after.values()) {
            Item old = before.get(item.id());
            if (old == null) {
                news.add(new Change(Kind.NEW, item.id(), item.title(), "", item.status()));
                continue;
            }
            if (!Objects.equals(old.status(), item.status())) {
                changed.add(new Change(Kind.STATUS, item.id(), item.title(), old.status(), item.status()));
            }
            if (!Objects.equals(old.assignee(), item.assignee())) {
                changed.add(new Change(Kind.ASSIGNEE, item.id(), item.title(), old.assignee(), item.assignee()));
            }
            if (!Objects.equals(old.lastComment(), item.lastComment()) && !item.lastComment().isEmpty()) {
                changed.add(new Change(Kind.COMMENT, item.id(), item.title(), "", item.lastComment()));
            }
            boolean other = !Objects.equals(old.title(), item.title())
                    || !Objects.equals(old.updatedAt(), item.updatedAt());
            boolean alreadySaid = Objects.equals(old.status(), item.status())
                    && Objects.equals(old.assignee(), item.assignee())
                    && Objects.equals(old.lastComment(), item.lastComment());
            if (other && alreadySaid) {
                changed.add(new Change(Kind.UPDATED, item.id(), item.title(), old.title(), item.title()));
            }
        }
        for (Item old : before.values()) {
            if (!after.containsKey(old.id())) {
                gone.add(new Change(Kind.GONE, old.id(), old.title(), old.status(), ""));
            }
        }
        List<Change> all = new ArrayList<>(news.size() + changed.size() + gone.size());
        all.addAll(news);
        all.addAll(changed);
        all.addAll(gone);
        return List.copyOf(all);
    }

    /** « 3 changements — 1 nouveau, 2 changements de statut » : la phrase sans IA (repli de SF-230-03). */
    public static String plainSummary(String watchName, List<Change> changes) {
        Map<Kind, Integer> counts = new LinkedHashMap<>();
        for (Change change : changes) {
            counts.merge(change.kind(), 1, Integer::sum);
        }
        StringBuilder line = new StringBuilder(watchName == null || watchName.isBlank() ? "Veille" : watchName.strip());
        line.append(" : ").append(changes.size()).append(changes.size() == 1 ? " changement" : " changements");
        String sep = " — ";
        for (Map.Entry<Kind, Integer> entry : counts.entrySet()) {
            line.append(sep).append(entry.getValue()).append(' ').append(label(entry.getKey(), entry.getValue()));
            sep = ", ";
        }
        String result = line.append('.').toString();
        return result.length() <= 500 ? result : result.substring(0, 499) + "…";
    }

    private static String label(Kind kind, int count) {
        boolean many = count > 1;
        return switch (kind) {
            case NEW -> many ? "nouveaux" : "nouveau";
            case GONE -> many ? "sortis du périmètre" : "sorti du périmètre";
            case STATUS -> many ? "changements de statut" : "changement de statut";
            case ASSIGNEE -> many ? "changements d'assigné" : "changement d'assigné";
            case COMMENT -> many ? "nouveaux commentaires" : "nouveau commentaire";
            case UPDATED -> many ? "mises à jour" : "mise à jour";
        };
    }

    private static Map<String, Item> index(List<Item> items) {
        Map<String, Item> map = new LinkedHashMap<>();
        if (items != null) {
            for (Item item : items) {
                map.putIfAbsent(item.id(), item);
            }
        }
        return map;
    }
}
