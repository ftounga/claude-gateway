package fr.claudegateway.jarvis.fastbrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.pieces.JarvisPieceService;
import fr.claudegateway.jarvis.pieces.Piece;

/**
 * <b>Les ordres d'écran vérifiés avant que le modèle parle</b> (F-272 / SF-272-02) — démo du 17/11 : le cerveau rapide
 * recevait « ok » pour {@code piece} et {@code vue}, disait « Voici… », puis l'écran refusait (NOT_FOUND : la pièce
 * était dans un autre sujet que celui de l'écran ; NOT_APPLICABLE : « en 3D » sans schéma ouvert) et Jarvis disait
 * « Pardon ».
 *
 * <p>Ici, confronté aux pièces du compte (lecture seule, index SF-237-01, {@code user_id} et {@code requireOwned}) :</p>
 * <ul>
 * <li>pièces nommées : gardées si elles existent ; cherchées aussi dans le sujet que l'outil {@code pieces} vient de
 * lire ; le sujet trouvé est <b>écrit dans l'ordre</b> (l'écran ne cherche plus dans le sien) ; aucune : refus avec les
 * pièces disponibles ;</li>
 * <li>galerie par portée : refus si rien ne correspond, ou si aucun sujet n'est en vue ;</li>
 * <li>pilotage d'une architecture ({@code vue}, {@code focus}, {@code flux}…) sans schéma ouvert : le schéma
 * d'architecture le plus récent du sujet est <b>ouvert d'abord</b> (ordre {@code piece} placé avant) ; aucun : refus,
 * « propose d'en dessiner un ».</li>
 * </ul>
 * <p>Le résultat d'outil rendu au modèle est ce qui va apparaître, ou le vrai refus. Ne lève jamais : une lecture en
 * échec laisse passer l'ordre tel quel (comportement d'avant).</p>
 */
@Component
public class JarvisShowVerifier {

    private static final Logger log = LoggerFactory.getLogger(JarvisShowVerifier.class);

    /** Les actions qui ouvrent une galerie (et peuvent nommer des pièces). */
    static final Set<String> GALLERY = Set.of("galerie", "piece", "comparer", "raconter", "planche");
    /** Les actions qui pilotent une architecture ouverte (F-238 / SF-238-10). */
    static final Set<String> ARCH = Set.of("focus", "flux", "replier", "deplier", "eclater", "rassembler", "vue");
    /** Préfixes de pièces hors de l'index (courriel, agenda) : ouvertes par la passerelle, jamais vérifiées ici. */
    static final List<String> OUTSIDE_INDEX = List.of("courriel:", "agenda:");
    /** La ligne d'une architecture ouverte dans le bloc « ## Écran » (jarvis-piece-arch.ts, archScreenLine). */
    static final String ARCH_LINE = "\nArchitecture « ";
    /** Pièces proposées au plus dans un refus. */
    static final int MAX_CANDIDATES = 5;

    /** Ce qui part à l'écran (dans l'ordre) et ce que le modèle reçoit. */
    public record Verified(List<JarvisShowCommand> commands, String toolResult, boolean refused) {

        static Verified pass(JarvisShowCommand command, String result) {
            return new Verified(List.of(command), result, false);
        }

        static Verified refuse(String result) {
            return new Verified(List.of(), result, true);
        }
    }

    private final JarvisPieceService pieces;
    private final WorkspaceRepository workspaces;

    public JarvisShowVerifier(JarvisPieceService pieces, WorkspaceRepository workspaces) {
        this.pieces = pieces;
        this.workspaces = workspaces;
    }

    /**
     * Vérifie un ordre déjà validé dans sa forme ({@code parsed.command() != null}).
     *
     * @param current     le sujet en vue à l'écran, ou {@code null}
     * @param readSubject le sujet que l'outil {@code pieces} a lu dans cette réponse, ou {@code null}
     * @param screen      le bloc « ## Écran » envoyé par l'écran, ou {@code null}
     */
    public Verified verify(JarvisShowCommand.Parsed parsed, UUID userId, UUID current, UUID readSubject,
            String screen) {
        JarvisShowCommand cmd = parsed.command();
        String ok = parsed.toolResult();
        if (cmd == null || userId == null) {
            return new Verified(cmd == null ? List.of() : List.of(cmd), ok, cmd == null);
        }
        try {
            if (GALLERY.contains(cmd.action())) {
                return gallery(cmd, ok, userId, current, readSubject);
            }
            if (ARCH.contains(cmd.action())) {
                return architecture(cmd, ok, userId, current, readSubject, screen);
            }
        } catch (RuntimeException e) {
            log.debug("Ordre d'écran non vérifié ({})", e.getClass().getSimpleName());
        }
        return Verified.pass(cmd, ok);
    }

    // ------------------------------------------------------------------ galerie / pièces nommées

    private Verified gallery(JarvisShowCommand cmd, String ok, UUID userId, UUID current, UUID readSubject) {
        UUID asked = uuid(cmd.scope() == null ? null : cmd.scope().subjectId());
        if (cmd.scope() != null && cmd.scope().subjectId() != null && asked == null) {
            return Verified.refuse("refusé : sujet inconnu « " + cmd.scope().subjectId()
                    + " » ; donne l'identifiant tel que dans le contexte");
        }
        List<String> named = cmd.pieces().stream().filter(id -> OUTSIDE_INDEX.stream().noneMatch(id::startsWith))
                .toList();
        if (cmd.pieces().size() != named.size()) {
            return Verified.pass(cmd, ok); // courriel / agenda : la passerelle les ouvre elle-même
        }
        UUID subject = asked != null ? asked : current;
        if (named.isEmpty()) {
            return scoped(cmd, ok, userId, subject);
        }
        boolean appui = "appui".equals(cmd.reason());
        List<UUID> tried = new ArrayList<>();
        for (UUID candidate : new UUID[] {subject, appui ? null : readSubject}) {
            if (candidate == null || tried.contains(candidate)) {
                continue;
            }
            tried.add(candidate);
            List<Piece> all = all(userId, candidate);
            List<String> found = named.stream().filter(id -> has(all, id)).toList();
            if (!found.isEmpty()) {
                int missing = named.size() - found.size();
                String result = missing == 0 ? ok : "ok (" + missing + " pièce(s) introuvable(s) non montrée(s))";
                return Verified.pass(withPieces(cmd, found, candidate), result);
            }
        }
        if (subject == null && readSubject == null) {
            return Verified.refuse("refusé : aucun sujet en vue ; demande à Monsieur de quel sujet il parle");
        }
        UUID where = subject != null ? subject : readSubject;
        return Verified.refuse("refusé : " + (named.size() > 1 ? "ces pièces n'existent" : "cette pièce n'existe")
                + " pas dans " + name(userId, where) + candidates(all(userId, where), kindOf(named.get(0))));
    }

    private Verified scoped(JarvisShowCommand cmd, String ok, UUID userId, UUID subject) {
        JarvisShowCommand.Scope scope = cmd.scope();
        if (subject == null) {
            if (scope != null && scope.since() != null) {
                return Verified.pass(cmd, ok); // « depuis le … » : tous les sujets
            }
            return Verified.refuse("refusé : aucun sujet en vue ; demande à Monsieur de quel sujet il parle");
        }
        Piece.Category category = category(scope == null ? null : scope.category());
        Piece.Kind kind = kind(scope == null ? null : scope.type());
        Integer chantier = scope == null ? null : scope.chantier();
        long matching = all(userId, subject).stream()
                .filter(p -> chantier == null || chantier.equals(p.chantier()))
                .filter(p -> category == null || category == p.category())
                .filter(p -> kind == null || kind == p.kind())
                .count();
        if (matching == 0) {
            String what = kind == Piece.Kind.ARCHITECTURE ? "aucun schéma d'architecture"
                    : kind == Piece.Kind.DIAGRAM ? "aucun schéma" : "aucune pièce de ce genre";
            return Verified.refuse("rien à montrer : " + what + " dans " + name(userId, subject)
                    + (chantier != null ? " (chantier " + chantier + ")" : "")
                    + (kind == Piece.Kind.ARCHITECTURE || kind == Piece.Kind.DIAGRAM
                            ? " ; propose d'en dessiner un" : ""));
        }
        return Verified.pass(cmd, ok);
    }

    // ------------------------------------------------------------------ architecture

    private Verified architecture(JarvisShowCommand cmd, String ok, UUID userId, UUID current, UUID readSubject,
            String screen) {
        if (screen != null && screen.contains(ARCH_LINE)) {
            return Verified.pass(cmd, ok); // un schéma est ouvert : l'écran résout la cible contre son graphe
        }
        UUID subject = current != null ? current : readSubject;
        if (subject == null) {
            return Verified.refuse("refusé : aucun schéma d'architecture ouvert ni sujet en vue ; demande de quel "
                    + "sujet il s'agit");
        }
        Piece latest = latestArchitecture(userId, subject);
        if (latest == null && readSubject != null && !readSubject.equals(subject)) {
            subject = readSubject;
            latest = latestArchitecture(userId, subject);
        }
        if (latest == null) {
            return Verified.refuse("refusé : aucun schéma d'architecture dans " + name(userId, subject)
                    + " ; propose d'en dessiner un");
        }
        JarvisShowCommand open = new JarvisShowCommand("piece", List.of(latest.id()),
                new JarvisShowCommand.Scope(subject.toString(), null, null, null, null, null), null, null, null,
                null, null, null, cmd.reason(), null, null);
        return new Verified(List.of(open, cmd), "ok (schéma « " + latest.title() + " » ouvert"
                + ("vue".equals(cmd.action()) && cmd.view() != null ? ", vue " + cmd.view() : "") + ")", false);
    }

    private Piece latestArchitecture(UUID userId, UUID subject) {
        return all(userId, subject).stream().filter(p -> p.kind() == Piece.Kind.ARCHITECTURE)
                .max(java.util.Comparator.comparing(Piece::at, java.util.Comparator.nullsFirst(
                        java.util.Comparator.naturalOrder())))
                .orElse(null);
    }

    // ------------------------------------------------------------------ outils

    private List<Piece> all(UUID userId, UUID subject) {
        try {
            return pieces.listFresh(userId, subject);
        } catch (RuntimeException e) {
            return List.of(); // sujet d'autrui ou introuvable : « inconnu »
        }
    }

    /** Même pièce, version comprise ou non (« page:e2@v3 » ≈ « page:e2 »). */
    static boolean has(List<Piece> all, String id) {
        String base = id.split("@")[0];
        return all.stream().anyMatch(p -> p.id().equals(id) || p.id().split("@")[0].equals(base));
    }

    private static JarvisShowCommand withPieces(JarvisShowCommand cmd, List<String> ids, UUID subject) {
        JarvisShowCommand.Scope s = cmd.scope();
        JarvisShowCommand.Scope scope = new JarvisShowCommand.Scope(subject.toString(),
                s == null ? null : s.chantier(), s == null ? null : s.category(), s == null ? null : s.type(),
                s == null ? null : s.series(), s == null ? null : s.since());
        return new JarvisShowCommand(cmd.action(), List.copyOf(ids), scope, cmd.index(), cmd.time(), cmd.chapter(),
                cmd.page(), cmd.zoom(), cmd.target(), cmd.reason(), cmd.view(), cmd.client());
    }

    private String candidates(List<Piece> all, String prefix) {
        List<Piece> same = all.stream().filter(p -> p.id().startsWith(prefix)).toList();
        List<Piece> list = (same.isEmpty() ? all : same).stream().limit(MAX_CANDIDATES).toList();
        if (list.isEmpty()) {
            return " ; ce sujet n'a aucune pièce";
        }
        StringBuilder b = new StringBuilder(" ; pièces disponibles : ");
        for (int i = 0; i < list.size(); i++) {
            Piece p = list.get(i);
            b.append(i == 0 ? "" : " ; ").append(p.id()).append(" « ").append(p.title()).append(" »");
        }
        return b.toString();
    }

    private static String kindOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(0, colon + 1);
    }

    private String name(UUID userId, UUID subject) {
        return workspaces.findByIdAndUserId(subject, userId).map(Workspace::getName).map(n -> "« " + n + " »")
                .orElse("ce sujet");
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Piece.Category category(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "entrees" -> Piece.Category.ENTREE;
            case "preuves" -> Piece.Category.PREUVE;
            case "productions" -> Piece.Category.PRODUCTION;
            default -> null;
        };
    }

    private static Piece.Kind kind(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
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
}
