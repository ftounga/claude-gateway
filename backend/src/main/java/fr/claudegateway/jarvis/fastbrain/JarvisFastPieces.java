package fr.claudegateway.jarvis.fastbrain;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.jarvis.pieces.JarvisPieceService;
import fr.claudegateway.jarvis.pieces.Piece;

/**
 * <b>Les pièces, côté cerveau rapide</b> (F-237 / SF-237-08, cadrage §6.2) : l'outil {@code pieces} exécuté par la
 * passerelle, et le bloc {@code ## Pièces} joint au dernier message. Lecture seule, sur l'index SF-237-01 —
 * {@code user_id} et {@code requireOwned} y sont appliqués ; un sujet d'un autre compte est « inconnu ». Jamais de
 * binaire : du texte borné.
 */
@Component
public class JarvisFastPieces {

    private static final Logger log = LoggerFactory.getLogger(JarvisFastPieces.class);
    /** Lignes au plus dans un résultat d'outil. */
    static final int MAX_LINES = 12;
    /** Description au plus par ligne. */
    static final int MAX_CAPTION = 200;
    /** Le bloc {@code ## Pièces} au plus. */
    static final int MAX_BLOCK = 600;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE d MMMM HH:mm", Locale.FRENCH)
            .withZone(ZoneId.of("Europe/Paris"));

    static final String UNKNOWN_SUBJECT = "Sujet inconnu.";
    static final String NONE = "Aucune pièce.";
    static final String FAILED = "Lecture impossible pour le moment.";

    private final JarvisPieceService pieces;

    public JarvisFastPieces(JarvisPieceService pieces) {
        this.pieces = pieces;
    }

    /** L'outil {@code pieces} : la liste compacte, ≤ {@value #MAX_LINES} lignes. Ne lève jamais. */
    public String read(UUID userId, UUID currentSubject, JsonNode input) {
        UUID subject = currentSubject;
        String asked = text(input, "sujet");
        if (!asked.isEmpty()) {
            try {
                subject = UUID.fromString(asked);
            } catch (IllegalArgumentException e) {
                return UNKNOWN_SUBJECT;
            }
        }
        JsonNode site = input == null ? null : input.get("chantier");
        Integer chantier = site != null && site.isIntegralNumber() ? site.asInt() : null;
        String search = JarvisFastGuards.normalize(text(input, "recherche"));
        try {
            JarvisPieceService.Result result = pieces.list(userId, subject, new JarvisPieceService.Filter(chantier,
                    category(text(input, "categorie")), kind(text(input, "type")), null, null, null,
                    JarvisPieceService.MAX_LIMIT));
            List<Piece> kept = result.pieces().stream()
                    .filter(p -> search.isEmpty() || JarvisFastGuards.normalize(p.title() + " "
                            + (p.caption() == null ? "" : p.caption())).contains(search))
                    .toList();
            if (kept.isEmpty()) {
                return NONE;
            }
            StringBuilder out = new StringBuilder();
            out.append(kept.size()).append(kept.size() > 1 ? " pièces" : " pièce");
            if (kept.size() > MAX_LINES) {
                out.append(" (les ").append(MAX_LINES).append(" premières)");
            }
            for (Piece p : kept.subList(0, Math.min(MAX_LINES, kept.size()))) {
                out.append('\n').append(line(p));
            }
            return out.toString();
        } catch (WorkspaceNotFoundException e) {
            return UNKNOWN_SUBJECT;
        } catch (RuntimeException e) {
            log.warn("Cerveau rapide : lecture des pièces en échec ({})", e.getClass().getSimpleName());
            return FAILED;
        }
    }

    /** Une ligne : {@code id | type | « titre » | description | date | chantier | disponibilité}. */
    static String line(Piece p) {
        StringBuilder b = new StringBuilder();
        b.append(p.id()).append(" | ").append(kindWord(p.kind())).append(" | « ").append(clip(p.title(), 120))
                .append(" »");
        if (p.caption() != null && !p.caption().isBlank()) {
            b.append(" | ").append(clip(p.caption(), MAX_CAPTION));
        }
        if (p.at() != null) {
            b.append(" | ").append(WHEN.format(p.at()));
        }
        if (p.chantier() != null) {
            b.append(" | chantier ").append(p.chantier());
            if (p.chantierLink() == Piece.ChantierLink.WINDOW) {
                b.append(" (d'après la date)");
            }
        } else {
            b.append(" | sujet");
        }
        if (p.available() == Piece.Availability.HOST_OFFLINE) {
            b.append(" | sur le poste, hors ligne");
        } else if (p.available() == Piece.Availability.PURGED) {
            b.append(" | purgée");
        }
        return b.toString();
    }

    /**
     * Le bloc {@code ## Pièces} du sujet courant : un compteur par chantier (« chantier 3 : besoin initial, 4 entrées,
     * 0 preuve, 1 page, 2 vidéos »), ≤ {@value #MAX_BLOCK} caractères. Vide sans sujet ou sans pièce. Ne lève jamais.
     */
    public String block(UUID userId, UUID subjectId) {
        if (subjectId == null) {
            return "";
        }
        try {
            List<Piece> all = pieces.list(userId, subjectId, new JarvisPieceService.Filter(null, null, null, null,
                    null, null, JarvisPieceService.MAX_LIMIT)).pieces();
            return block(all);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Le bloc à partir des pièces (pur, testé). */
    static String block(List<Piece> all) {
        if (all.isEmpty()) {
            return "";
        }
        Map<Integer, List<Piece>> byChantier = new TreeMap<>((a, b) -> Integer.compare(b, a)); // les récents d'abord
        List<Piece> subjectLevel = new ArrayList<>();
        for (Piece p : all) {
            if (p.chantier() == null) {
                subjectLevel.add(p);
            } else {
                byChantier.computeIfAbsent(p.chantier(), k -> new ArrayList<>()).add(p);
            }
        }
        StringBuilder out = new StringBuilder("## Pièces");
        for (Map.Entry<Integer, List<Piece>> e : byChantier.entrySet()) {
            String next = "\nchantier " + e.getKey() + " : " + counts(e.getValue());
            if (out.length() + next.length() > MAX_BLOCK) {
                return out.toString();
            }
            out.append(next);
        }
        if (!subjectLevel.isEmpty()) {
            String next = "\nsujet (sans chantier) : " + counts(subjectLevel);
            if (out.length() + next.length() <= MAX_BLOCK) {
                out.append(next);
            }
        }
        return out.toString();
    }

    private static String counts(List<Piece> list) {
        boolean need = list.stream().anyMatch(p -> p.id().startsWith("need:"));
        int entries = (int) list.stream().filter(p -> p.category() == Piece.Category.ENTREE
                && !p.id().startsWith("need:")).count();
        int proofs = (int) list.stream().filter(p -> p.category() == Piece.Category.PREUVE).count();
        Map<Piece.Kind, Integer> productions = new EnumMap<>(Piece.Kind.class);
        for (Piece p : list) {
            if (p.category() == Piece.Category.PRODUCTION) {
                productions.merge(p.kind(), 1, Integer::sum);
            }
        }
        List<String> parts = new ArrayList<>();
        if (need) {
            parts.add("besoin initial");
        }
        parts.add(entries + (entries > 1 ? " entrées" : " entrée"));
        parts.add(proofs + (proofs > 1 ? " preuves" : " preuve"));
        productions.forEach((kind, n) -> parts.add(n + " " + kindWord(kind) + (n > 1 ? plural(kind) : "")));
        return String.join(", ", parts);
    }

    private static String plural(Piece.Kind kind) {
        return kind == Piece.Kind.AUDIO || kind == Piece.Kind.PDF ? "" : "s";
    }

    static String kindWord(Piece.Kind kind) {
        return switch (kind) {
            case IMAGE -> "image";
            case VIDEO -> "vidéo";
            case AUDIO -> "audio";
            case PDF -> "PDF";
            case PAGE -> "page";
            case SLIDES -> "présentation";
            case ARCHITECTURE -> "architecture";
            case DIAGRAM -> "schéma";
            case TEXT -> "texte";
            case FILE -> "fichier";
        };
    }

    static Piece.Category category(String said) {
        return switch (said) {
            case "entrees" -> Piece.Category.ENTREE;
            case "preuves" -> Piece.Category.PREUVE;
            case "productions" -> Piece.Category.PRODUCTION;
            default -> null;
        };
    }

    static Piece.Kind kind(String said) {
        return switch (said) {
            case "image" -> Piece.Kind.IMAGE;
            case "video" -> Piece.Kind.VIDEO;
            case "audio" -> Piece.Kind.AUDIO;
            case "pdf" -> Piece.Kind.PDF;
            case "page" -> Piece.Kind.PAGE;
            case "architecture" -> Piece.Kind.ARCHITECTURE;
            case "schema" -> Piece.Kind.DIAGRAM;
            case "diaporama" -> Piece.Kind.SLIDES;
            case "texte" -> Piece.Kind.TEXT;
            case "fichier" -> Piece.Kind.FILE;
            default -> null;
        };
    }

    private static String clip(String text, int max) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return t.length() <= max ? t : t.substring(0, max - 1).stripTrailing() + "…";
    }

    private static String text(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }
}
