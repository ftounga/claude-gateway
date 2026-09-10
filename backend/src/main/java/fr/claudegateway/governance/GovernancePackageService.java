package fr.claudegateway.governance;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.governance.dto.GovernanceControlView;
import fr.claudegateway.governance.dto.GovernanceFileDetail;
import fr.claudegateway.governance.dto.GovernanceFileView;
import fr.claudegateway.governance.dto.GovernancePackageAdminView;
import fr.claudegateway.governance.dto.GovernancePackageFileRequest;
import fr.claudegateway.governance.dto.GovernancePackageRequest;
import fr.claudegateway.governance.dto.GovernancePackageView;

/**
 * Le catalogue de gouvernance publié (F-51 / SF-51-01) : ce que l'admin rédige, et ce que tout
 * utilisateur connecté peut lire une fois publié.
 *
 * <p><b>Deux publics, deux lectures.</b> L'admin voit tout — brouillons compris, contenu des fichiers
 * compris. L'utilisateur ne voit que les paquets <b>publiés</b>, et pour chaque fichier apporté
 * seulement <b>son chemin et son genre</b> : c'est ce dont il a besoin pour décider, puisque
 * l'exigence de la feature est que l'écran annonce <i>ce qui sera écrit et où</i> avant l'activation.</p>
 *
 * <p><b>Pourquoi la validation est stricte, et pourquoi elle a lieu ici.</b> Un paquet est rédigé par
 * une personne et appliqué sur la machine d'autres personnes. Les deux données qui portent ce
 * pouvoir — le <b>chemin</b> d'un fichier et l'<b>identifiant</b> d'un contrôle — sont donc validées
 * à la rédaction : un chemin qui sort du projet est refusé, un contrôle que le serveur ne fournit pas
 * est refusé. Rien, dans ce mécanisme, ne charge de code : un paquet cite des contrôles, il n'en
 * apporte pas (limite héritée de F-50).</p>
 *
 * <p><b>Isolation.</b> Ces tables ne portent pas {@code user_id} : un paquet est un contenu produit,
 * comme un plan tarifaire. L'écriture passe par {@link AdminService#assertAdmin()} — la garde unique
 * du produit — et la lecture publique est bornée aux paquets publiés. Ce qui appartient à un
 * utilisateur (sa sélection, ses activations) arrive en SF-51-02.</p>
 */
@Service
public class GovernancePackageService {

    /** Un slug est lisible et stable : minuscules, chiffres, tirets. */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /** Nombre maximal de fichiers apportés par un paquet. */
    public static final int MAX_FILES = 50;

    /** Volume total de fichiers d'un paquet : au-delà, ce n'est plus une gouvernance, c'est un dépôt. */
    public static final int MAX_TOTAL_CONTENT = 256_000;

    private final GovernancePackageRepository packages;
    private final GovernancePackageFileRepository files;
    private final GovernanceControlRegistry controls;
    private final AdminService adminService;

    public GovernancePackageService(GovernancePackageRepository packages,
            GovernancePackageFileRepository files, GovernanceControlRegistry controls,
            AdminService adminService) {
        this.packages = packages;
        this.files = files;
        this.controls = controls;
        this.adminService = adminService;
    }

    // ------------------------------------------------------------------ admin

    /** Tous les paquets, brouillons compris, contenu compris. ADMIN uniquement. */
    @Transactional(readOnly = true)
    public List<GovernancePackageAdminView> listForAdmin() {
        adminService.assertAdmin();
        List<GovernancePackage> all = packages.findAllByOrderByNameAsc();
        Map<UUID, List<GovernancePackageFile>> byPackage = filesOf(all);
        return all.stream().map(pkg -> adminView(pkg, byPackage.getOrDefault(pkg.getId(), List.of())))
                .toList();
    }

    /** Les contrôles que le serveur fournit — la seule liste qu'un paquet a le droit de citer. */
    @Transactional(readOnly = true)
    public List<GovernanceControlView> listControls() {
        adminService.assertAdmin();
        return controls.all().stream()
                .map(control -> new GovernanceControlView(control.id(), control.kind().name(),
                        control.description(), true))
                .toList();
    }

    /** Crée un paquet, non publié, en version 1. ADMIN uniquement. */
    @Transactional
    public GovernancePackageAdminView create(GovernancePackageRequest request) {
        adminService.assertAdmin();
        if (request == null) {
            throw new InvalidGovernancePackageException("Corps de requête absent.");
        }
        String slug = normalizeSlug(request.slug());
        packages.findBySlug(slug).ifPresent(existing -> {
            throw new GovernancePackageConflictException(
                    "Un paquet porte déjà l'identifiant « " + slug + " ». Choisissez-en un autre.");
        });
        GovernancePackage pkg = GovernancePackage.builder()
                .slug(slug)
                .version(1)
                .published(false)
                .build();
        applyContent(pkg, request);
        GovernancePackage saved = packages.save(pkg);
        List<GovernancePackageFile> stored = replaceFiles(saved.getId(), request.files());
        return adminView(saved, stored);
    }

    /**
     * Remplace <b>intégralement</b> le contenu d'un paquet et incrémente sa version.
     *
     * <p>Le remplacement partiel n'existe pas : un paquet composite, moitié d'une rédaction moitié
     * d'une autre, ne serait plus attribuable à une version. Le {@code slug} est ignoré — il est
     * immuable, parce qu'il est ce qui identifie un paquet d'une version à l'autre.</p>
     */
    @Transactional
    public GovernancePackageAdminView update(UUID id, GovernancePackageRequest request) {
        adminService.assertAdmin();
        if (request == null) {
            throw new InvalidGovernancePackageException("Corps de requête absent.");
        }
        GovernancePackage pkg = require(id);
        applyContent(pkg, request);
        pkg.setVersion(pkg.getVersion() + 1);
        GovernancePackage saved = packages.save(pkg);
        List<GovernancePackageFile> stored = replaceFiles(saved.getId(), request.files());
        return adminView(saved, stored);
    }

    /** Publie (ou dépublie) un paquet. Dépublier le retire du catalogue, sans toucher à rien d'autre. */
    @Transactional
    public GovernancePackageAdminView setPublished(UUID id, boolean published) {
        adminService.assertAdmin();
        GovernancePackage pkg = require(id);
        pkg.setPublished(published);
        if (published && pkg.getPublishedAt() == null) {
            pkg.setPublishedAt(OffsetDateTime.now());
        }
        GovernancePackage saved = packages.save(pkg);
        return adminView(saved, files.findByPackageIdOrderByPositionAsc(saved.getId()));
    }

    /**
     * Supprime un paquet — refusé tant qu'il est publié.
     *
     * <p>Un paquet publié a pu être retenu et activé par des utilisateurs. L'effacer d'un geste
     * romprait leurs projets sans qu'ils aient rien demandé ; la dépublication, elle, est le geste
     * réversible qui le retire du catalogue en laissant vivre l'existant (décision D6 du cadrage).</p>
     */
    @Transactional
    public void delete(UUID id) {
        adminService.assertAdmin();
        GovernancePackage pkg = require(id);
        if (pkg.isPublished()) {
            throw new GovernancePackageConflictException(
                    "Ce paquet est publié. Dépubliez-le avant de le supprimer.");
        }
        files.deleteByPackageId(pkg.getId());
        packages.delete(pkg);
    }

    // ----------------------------------------------------------- utilisateur

    /** Le catalogue publié, tel qu'un utilisateur connecté le lit. */
    @Transactional(readOnly = true)
    public List<GovernancePackageView> listPublished() {
        List<GovernancePackage> published = packages.findByPublishedTrueOrderByNameAsc();
        Map<UUID, List<GovernancePackageFile>> byPackage = filesOf(published);
        return published.stream()
                .map(pkg -> publicView(pkg, byPackage.getOrDefault(pkg.getId(), List.of())))
                .toList();
    }

    /**
     * Un paquet <b>publié</b>, par identifiant.
     *
     * <p>Un paquet non publié rend 404, jamais 403 : pour un utilisateur, il n'existe pas — et un 403
     * lui apprendrait qu'un brouillon existe sous cet identifiant.</p>
     */
    @Transactional(readOnly = true)
    public GovernancePackage requirePublished(UUID id) {
        GovernancePackage pkg = require(id);
        if (!pkg.isPublished()) {
            throw new GovernancePackageNotFoundException("Paquet introuvable.");
        }
        return pkg;
    }

    /** Le paquet, publié ou non. Réservé aux appelants qui ont déjà tranché la visibilité. */
    @Transactional(readOnly = true)
    public GovernancePackage require(UUID id) {
        if (id == null) {
            throw new GovernancePackageNotFoundException("Paquet introuvable.");
        }
        return packages.findById(id)
                .orElseThrow(() -> new GovernancePackageNotFoundException("Paquet introuvable."));
    }

    /** Les fichiers d'un paquet, dans l'ordre de rédaction. */
    @Transactional(readOnly = true)
    public List<GovernancePackageFile> filesOf(UUID packageId) {
        return files.findByPackageIdOrderByPositionAsc(packageId);
    }

    /** Vue publique d'un paquet, fichiers déjà lus. */
    public GovernancePackageView publicView(GovernancePackage pkg, List<GovernancePackageFile> pkgFiles) {
        return new GovernancePackageView(pkg.getId(), pkg.getSlug(), pkg.getName(), pkg.getSummary(),
                pkg.getVersion(), pkg.getRules(), controlViews(pkg),
                pkgFiles.stream()
                        .map(file -> new GovernanceFileView(file.getPath(), file.getKind().name()))
                        .toList());
    }

    // -------------------------------------------------------------- internes

    /** Valide et pose le contenu soumis sur l'entité — sans toucher au slug ni à la version. */
    private void applyContent(GovernancePackage pkg, GovernancePackageRequest request) {
        String name = required(request.name(), "nom");
        if (name.length() > GovernancePackage.MAX_NAME_LENGTH) {
            throw new InvalidGovernancePackageException(
                    "Nom trop long (max " + GovernancePackage.MAX_NAME_LENGTH + " caractères).");
        }
        String summary = trimToNull(request.summary());
        if (summary != null && summary.length() > GovernancePackage.MAX_SUMMARY_LENGTH) {
            throw new InvalidGovernancePackageException(
                    "Résumé trop long (max " + GovernancePackage.MAX_SUMMARY_LENGTH + " caractères).");
        }
        String rules = trimToNull(request.rules());
        if (rules != null && rules.length() > GovernancePackage.MAX_RULES_LENGTH) {
            throw new InvalidGovernancePackageException(
                    "Règles trop longues (max " + GovernancePackage.MAX_RULES_LENGTH + " caractères).");
        }
        List<String> controlIds = validateControls(request.controlIds());
        List<GovernancePackageFileRequest> requestedFiles =
                request.files() == null ? List.of() : request.files();
        validateFiles(requestedFiles);

        // Un paquet qui n'apporte rien serait annoncé à l'écran comme n'écrivant rien et ne changeant
        // rien : c'est une ligne de catalogue qui ment sur sa propre utilité.
        if (rules == null && controlIds.isEmpty() && requestedFiles.isEmpty()) {
            throw new InvalidGovernancePackageException(
                    "Un paquet doit apporter au moins une règle, un contrôle ou un fichier.");
        }
        pkg.setName(name);
        pkg.setSummary(summary);
        pkg.setRules(rules);
        pkg.setControlIdList(controlIds);
    }

    /** Chaque identifiant cité doit exister dans le produit ; les doublons sont écrasés. */
    private List<String> validateControls(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        List<String> unique = new ArrayList<>();
        for (String raw : requested) {
            String id = trimToNull(raw);
            if (id == null || unique.contains(id)) {
                continue;
            }
            if (!controls.exists(id)) {
                // Refus explicite : un paquet publié avec un contrôle imaginaire promettrait un
                // verrou qui ne se refermerait jamais.
                throw new InvalidGovernancePackageException("Contrôle inconnu : « " + id + " ».");
            }
            unique.add(id);
        }
        if (unique.size() > GovernanceControlRegistry.MAX_CONTROLS_PER_PACKAGE) {
            throw new InvalidGovernancePackageException("Trop de contrôles (max "
                    + GovernanceControlRegistry.MAX_CONTROLS_PER_PACKAGE + ").");
        }
        String joined = GovernancePackage.joinControlIds(unique);
        if (joined != null && joined.length() > GovernancePackage.MAX_CONTROL_IDS_LENGTH) {
            throw new InvalidGovernancePackageException("Liste de contrôles trop longue.");
        }
        return unique;
    }

    /** Vérifie chemins, genres et tailles — avant que quoi que ce soit ne parte chez quelqu'un. */
    private void validateFiles(List<GovernancePackageFileRequest> requested) {
        if (requested.size() > MAX_FILES) {
            throw new InvalidGovernancePackageException("Trop de fichiers (max " + MAX_FILES + ").");
        }
        Set<String> seen = new HashSet<>();
        long total = 0L;
        for (GovernancePackageFileRequest file : requested) {
            if (file == null) {
                throw new InvalidGovernancePackageException("Fichier vide dans la liste.");
            }
            String path = GovernancePath.normalizeOrNull(file.path());
            if (path == null) {
                throw new InvalidGovernancePackageException("Chemin de fichier invalide : « "
                        + trimToNull(file.path()) + " ». Un chemin doit être relatif au projet.");
            }
            if (!seen.add(path)) {
                throw new InvalidGovernancePackageException("Chemin en double : « " + path + " ».");
            }
            parseKind(file.kind());
            String content = file.content() == null ? "" : file.content();
            if (content.length() > GovernancePackageFile.MAX_CONTENT_LENGTH) {
                throw new InvalidGovernancePackageException("Fichier trop volumineux : « " + path
                        + " » (max " + GovernancePackageFile.MAX_CONTENT_LENGTH + " caractères).");
            }
            total += content.length();
        }
        if (total > MAX_TOTAL_CONTENT) {
            throw new InvalidGovernancePackageException(
                    "Contenu total trop volumineux (max " + MAX_TOTAL_CONTENT + " caractères).");
        }
    }

    /** Efface puis réécrit les fichiers du paquet : le contenu soumis fait foi. */
    private List<GovernancePackageFile> replaceFiles(UUID packageId,
            List<GovernancePackageFileRequest> requested) {
        files.deleteByPackageId(packageId);
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        List<GovernancePackageFile> stored = new ArrayList<>(requested.size());
        int position = 0;
        for (GovernancePackageFileRequest file : requested) {
            stored.add(files.save(GovernancePackageFile.builder()
                    .packageId(packageId)
                    .position(position++)
                    .path(GovernancePath.normalizeOrNull(file.path()))
                    .kind(parseKind(file.kind()))
                    .content(file.content() == null ? "" : file.content())
                    .build()));
        }
        return stored;
    }

    private static GovernanceFileKind parseKind(String raw) {
        String kind = trimToNull(raw);
        if (kind == null) {
            throw new InvalidGovernancePackageException("Genre de fichier absent (SKILL ou TEMPLATE).");
        }
        try {
            return GovernanceFileKind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new InvalidGovernancePackageException(
                    "Genre de fichier inconnu : « " + kind + " » (attendu SKILL ou TEMPLATE).");
        }
    }

    private static String normalizeSlug(String raw) {
        String slug = trimToNull(raw);
        if (slug == null) {
            throw new InvalidGovernancePackageException("Identifiant (slug) absent.");
        }
        slug = slug.toLowerCase(Locale.ROOT);
        if (slug.length() < GovernancePackage.MIN_SLUG_LENGTH
                || slug.length() > GovernancePackage.MAX_SLUG_LENGTH
                || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new InvalidGovernancePackageException("Identifiant invalide : "
                    + GovernancePackage.MIN_SLUG_LENGTH + " à " + GovernancePackage.MAX_SLUG_LENGTH
                    + " caractères, minuscules, chiffres et tirets uniquement.");
        }
        return slug;
    }

    private static String required(String raw, String field) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new InvalidGovernancePackageException("Champ obligatoire absent : " + field + ".");
        }
        return value;
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Les fichiers de plusieurs paquets, groupés — une seule lecture pour tout un catalogue. */
    private Map<UUID, List<GovernancePackageFile>> filesOf(List<GovernancePackage> pkgs) {
        if (pkgs.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = pkgs.stream().map(GovernancePackage::getId).toList();
        return files.findByPackageIdInOrderByPositionAsc(ids).stream()
                .collect(Collectors.groupingBy(GovernancePackageFile::getPackageId,
                        Collectors.toList()));
    }

    /**
     * Les contrôles cités par un paquet. Un identifiant que le produit ne fournit plus est rendu avec
     * {@code known = false} plutôt qu'omis : l'admin doit voir qu'un paquet promet un verrou qui
     * n'existe plus, au lieu de le chercher.
     */
    private List<GovernanceControlView> controlViews(GovernancePackage pkg) {
        Function<String, GovernanceControlView> view = id -> controls.find(id)
                .map(control -> new GovernanceControlView(control.id(), control.kind().name(),
                        control.description(), true))
                .orElseGet(() -> new GovernanceControlView(id, null,
                        "Contrôle absent de cette version du produit.", false));
        return pkg.controlIdList().stream().map(view).toList();
    }

    private GovernancePackageAdminView adminView(GovernancePackage pkg,
            List<GovernancePackageFile> pkgFiles) {
        return new GovernancePackageAdminView(pkg.getId(), pkg.getSlug(), pkg.getName(),
                pkg.getSummary(), pkg.getRules(), controlViews(pkg),
                pkgFiles.stream()
                        .map(file -> new GovernanceFileDetail(file.getPath(), file.getKind().name(),
                                file.getContent()))
                        .toList(),
                pkg.getVersion(), pkg.isPublished(), pkg.getPublishedAt(), pkg.getUpdatedAt());
    }
}
