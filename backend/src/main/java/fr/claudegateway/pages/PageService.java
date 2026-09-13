package fr.claudegateway.pages;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Ranger une page</b> (F-109 / SF-109-01) : la publier, la versionner, la relire — toujours pour son
 * propriétaire.
 *
 * <h2>Pas d'endpoint de dépôt</h2>
 *
 * <p>{@link #publish} est une méthode de service, appelée par l'outil de l'agent (SF-109-02). Une route
 * d'upload sans producteur serait une surface offerte pour rien (même doctrine que F-89 D2).</p>
 *
 * <h2>Les bornes</h2>
 *
 * <p>Une version pèse au plus 8 Mo, pièces jointes comprises ; un compte conserve au plus 500 Mo ; une page
 * garde ses dix dernières versions. Le quota est évalué <b>après</b> la purge que la publication va
 * provoquer : republier une page déjà à dix versions libère la plus ancienne avant de compter.</p>
 *
 * <h2>L'isolation</h2>
 *
 * <p>Toute lecture passe par {@code findByIdAndUserId} : une page d'un autre compte est
 * <b>introuvable</b>, indiscernable d'une page qui n'existe pas — republier sur son identifiant aussi.</p>
 */
@Service
public class PageService {

    /** Longueur maximale d'un titre. */
    public static final int MAX_TITLE_CHARS = 120;

    /** Longueur maximale d'une description. */
    public static final int MAX_DESCRIPTION_CHARS = 300;

    private final PageRepository pages;
    private final PageVersionRepository versions;
    private final PageStore store;
    private final PageLimits limits;

    public PageService(PageRepository pages, PageVersionRepository versions, PageStore store,
            PageLimits limits) {
        this.pages = pages;
        this.versions = versions;
        this.store = store;
        this.limits = limits;
    }

    /**
     * Publie une page, ou une nouvelle version d'une page existante du même compte.
     *
     * @param place       lieu de publication (propriétaire compris)
     * @param pageId      page à republier, ou {@code null} pour une nouvelle page
     * @param title       titre court et distinctif
     * @param description phrase de description, facultative
     * @param html        document HTML (UTF-8)
     * @param attachments pièces jointes par nom, éventuellement vide
     * @return la page et la version créée
     * @throws PageRejectedException      si une contrainte de contenu n'est pas tenue
     * @throws PageQuotaExceededException si le compte dépasserait sa borne
     * @throws PageNotFoundException      si {@code pageId} n'est pas une page de ce compte
     */
    @Transactional
    public PublishedPage publish(PagePlace place, UUID pageId, String title, String description,
            String html, Map<String, byte[]> attachments) {
        String cleanTitle = cleanTitle(title);
        String cleanDescription = cleanDescription(description);
        byte[] htmlBytes = html == null ? new byte[0] : html.getBytes(StandardCharsets.UTF_8);
        if (html == null || html.isBlank()) {
            throw new PageRejectedException("Le contenu HTML de la page est vide.");
        }
        Map<String, byte[]> files = checkAttachments(attachments);
        long size = htmlBytes.length + files.values().stream().mapToLong(content -> content.length).sum();
        if (size > limits.maxPageBytes()) {
            throw new PageRejectedException("Page trop volumineuse : " + megabytes(limits.maxPageBytes())
                    + " Mo au plus, pièces jointes comprises. Allège les images ou retire des pièces jointes.");
        }

        UUID userId = place.userId();
        Page page;
        List<PageVersion> kept;
        if (pageId == null) {
            page = Page.builder().id(UUID.randomUUID()).userId(userId).space(place.space())
                    .hostId(place.hostId()).workspaceId(place.workspaceId()).build();
            kept = List.of();
        } else {
            page = pages.findByIdAndUserId(pageId, userId).orElseThrow(PageNotFoundException::new);
            kept = versions.findByPageIdAndUserIdOrderByVersionAsc(page.getId(), userId);
        }

        int overflow = Math.max(0, kept.size() + 1 - limits.maxVersions());
        List<PageVersion> purged = kept.subList(0, Math.min(overflow, kept.size()));
        long freed = purged.stream().mapToLong(PageVersion::getSizeBytes).sum();
        long used = versions.sumSizeBytesByUserId(userId);
        if (used - freed + size > limits.maxAccountBytes()) {
            throw new PageQuotaExceededException(limits.maxAccountBytes());
        }

        int version = kept.isEmpty() ? 1 : kept.get(kept.size() - 1).getVersion() + 1;
        store.putVersion(userId, page.getId(), version, htmlBytes, files);

        page.setTitle(cleanTitle);
        page.setDescription(cleanDescription);
        page.setCurrentVersion(version);
        Page saved = pages.saveAndFlush(page);
        PageVersion created = versions.save(PageVersion.builder().id(UUID.randomUUID())
                .pageId(saved.getId()).userId(userId).version(version).sizeBytes(size)
                .attachmentCount(files.size()).build());

        for (PageVersion old : List.copyOf(purged)) {
            versions.delete(old);
            store.deleteVersion(userId, saved.getId(), old.getVersion());
        }
        return new PublishedPage(saved, created);
    }

    /** La page, si elle appartient au compte. */
    @Transactional(readOnly = true)
    public Page require(UUID userId, UUID pageId) {
        return pages.findByIdAndUserId(pageId, userId).orElseThrow(PageNotFoundException::new);
    }

    /** Les versions conservées d'une page du compte, la plus récente d'abord. */
    @Transactional(readOnly = true)
    public List<PageVersion> versions(UUID userId, UUID pageId) {
        Page page = require(userId, pageId);
        List<PageVersion> all = new java.util.ArrayList<>(
                versions.findByPageIdAndUserIdOrderByVersionAsc(page.getId(), userId));
        java.util.Collections.reverse(all);
        return List.copyOf(all);
    }

    /**
     * Le HTML d'une version d'une page du compte.
     *
     * @param version version voulue, ou {@code null} / {@code 0} pour la version courante
     */
    @Transactional(readOnly = true)
    public PageContent html(UUID userId, UUID pageId, Integer version) {
        Page page = require(userId, pageId);
        int wanted = resolveVersion(page, version);
        versions.findByPageIdAndUserIdAndVersion(page.getId(), userId, wanted)
                .orElseThrow(PageNotFoundException::new);
        byte[] content = store.html(userId, page.getId(), wanted).orElseThrow(PageNotFoundException::new);
        return new PageContent(page, wanted, content, "text/html; charset=utf-8");
    }

    /** Une pièce jointe d'une version d'une page du compte, ou vide. */
    @Transactional(readOnly = true)
    public Optional<PageContent> attachment(UUID userId, UUID pageId, Integer version, String name) {
        Optional<Page> page = pages.findByIdAndUserId(pageId, userId);
        if (page.isEmpty()) {
            return Optional.empty();
        }
        int wanted = resolveVersion(page.get(), version);
        Optional<String> type = PageAttachments.contentType(name);
        if (type.isEmpty() || versions.findByPageIdAndUserIdAndVersion(pageId, userId, wanted).isEmpty()) {
            return Optional.empty();
        }
        return store.attachment(userId, pageId, wanted, name)
                .map(content -> new PageContent(page.get(), wanted, content, type.get()));
    }

    /**
     * La version voulue d'une page du compte : la courante par défaut, sinon celle demandée si elle est
     * conservée (SF-109-04).
     */
    @Transactional(readOnly = true)
    public int requireVersion(UUID userId, UUID pageId, Integer version) {
        Page page = require(userId, pageId);
        int wanted = resolveVersion(page, version);
        versions.findByPageIdAndUserIdAndVersion(page.getId(), userId, wanted).orElseThrow(PageNotFoundException::new);
        return wanted;
    }

    /** Les pages du compte à un lieu, la plus récemment modifiée d'abord (SF-109-04). */
    @Transactional(readOnly = true)
    public List<Page> list(UUID userId, UUID hostId, PageSpace space) {
        return pages.findByUserIdAndHostIdAndSpaceOrderByUpdatedAtDesc(userId, hostId, space);
    }

    /** Renomme une page du compte (SF-109-04), aux règles de la publication. */
    @Transactional
    public Page rename(UUID userId, UUID pageId, String title) {
        Page page = require(userId, pageId);
        page.setTitle(cleanTitle(title));
        return pages.saveAndFlush(page);
    }

    /**
     * Supprime une page du compte : ses lignes (versions en cascade), puis ses objets (SF-109-04). Si le stockage
     * n'efface pas tout, l'exception remonte et la transaction est annulée — la page reste listée, rien n'est
     * perdu en silence (F-79).
     */
    @Transactional
    public void delete(UUID userId, UUID pageId) {
        Page page = require(userId, pageId);
        pages.delete(page);
        pages.flush();
        store.deletePage(userId, page.getId());
    }

    /** Supprime toutes les pages du compte à un lieu ; rend leur nombre (SF-109-04, clôture de mission). */
    @Transactional
    public int deletePlace(UUID userId, UUID hostId, PageSpace space) {
        List<Page> placed = list(userId, hostId, space);
        for (Page page : placed) {
            pages.delete(page);
        }
        pages.flush();
        for (Page page : placed) {
            store.deletePage(userId, page.getId());
        }
        return placed.size();
    }

    /**
     * Écrit l'archive ZIP des versions courantes d'un lieu (SF-109-04) : {@code {titre}/index.html} et ses pièces
     * jointes, <b>au fil de l'eau</b> — une page à la fois en mémoire, jamais l'archive entière.
     */
    @Transactional(readOnly = true)
    public void exportPlace(UUID userId, UUID hostId, PageSpace space, java.io.OutputStream out)
            throws java.io.IOException {
        java.util.Set<String> folders = new java.util.HashSet<>();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            for (Page page : list(userId, hostId, space)) {
                int version = page.getCurrentVersion();
                java.util.Optional<byte[]> html = store.html(userId, page.getId(), version);
                if (html.isEmpty()) {
                    continue;
                }
                String folder = uniqueFolder(slug(page.getTitle()), folders);
                zip.putNextEntry(new java.util.zip.ZipEntry(folder + "/index.html"));
                zip.write(html.get());
                zip.closeEntry();
                for (String name : store.attachmentNames(userId, page.getId(), version)) {
                    java.util.Optional<byte[]> content = store.attachment(userId, page.getId(), version, name);
                    if (content.isPresent()) {
                        zip.putNextEntry(new java.util.zip.ZipEntry(folder + "/" + name));
                        zip.write(content.get());
                        zip.closeEntry();
                    }
                }
            }
        }
    }

    /** Efface les objets de toutes les pages d'un compte (SF-109-04) ; les lignes tombent avec le compte. */
    public void purgeUser(UUID userId) {
        store.deleteAccount(userId);
    }

    /** {@code titre-de-la-page} : lisible, sans caractère qu'un système de fichiers refuserait. */
    static String slug(String title) {
        String slug = java.text.Normalizer.normalize(title == null ? "" : title, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-$", "");
        }
        return slug.isEmpty() ? "page" : slug;
    }

    private static String uniqueFolder(String base, java.util.Set<String> taken) {
        String folder = base;
        for (int i = 2; !taken.add(folder); i++) {
            folder = base + "-" + i;
        }
        return folder;
    }

    private static int resolveVersion(Page page, Integer version) {
        return version == null || version <= 0 ? page.getCurrentVersion() : version;
    }

    /** Titre nettoyé : une ligne, espaces repliés, borné. */
    static String cleanTitle(String title) {
        String clean = title == null ? "" : title.replaceAll("\\s+", " ").strip();
        if (clean.isEmpty()) {
            throw new PageRejectedException("Une page a besoin d'un titre court et distinctif.");
        }
        if (clean.length() > MAX_TITLE_CHARS) {
            throw new PageRejectedException("Titre trop long : " + MAX_TITLE_CHARS + " caractères au plus.");
        }
        return clean;
    }

    private static String cleanDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String clean = description.replaceAll("\\s+", " ").strip();
        if (clean.length() > MAX_DESCRIPTION_CHARS) {
            throw new PageRejectedException("Description trop longue : " + MAX_DESCRIPTION_CHARS
                    + " caractères au plus — une phrase suffit.");
        }
        return clean;
    }

    private Map<String, byte[]> checkAttachments(Map<String, byte[]> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return Map.of();
        }
        if (attachments.size() > limits.maxAttachments()) {
            throw new PageRejectedException("Trop de pièces jointes : " + limits.maxAttachments() + " au plus.");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : attachments.entrySet()) {
            if (!PageAttachments.isValidName(entry.getKey())) {
                throw new PageRejectedException("Nom de pièce jointe refusé : « " + entry.getKey()
                        + " ». Un nom plat (lettres, chiffres, point, tiret), sans dossier, d'extension parmi : "
                        + PageAttachments.acceptedExtensions() + ".");
            }
            files.put(entry.getKey(), entry.getValue() == null ? new byte[0] : entry.getValue());
        }
        return files;
    }

    private static long megabytes(long bytes) {
        return bytes / (1024 * 1024);
    }

    /**
     * Une page publiée et la version créée.
     *
     * @param page    la page, à jour
     * @param version la version qui vient d'être rangée
     */
    public record PublishedPage(Page page, PageVersion version) {
    }

    /**
     * Un contenu servi.
     *
     * @param page        la page
     * @param version     la version servie
     * @param content     les octets
     * @param contentType le type servi
     */
    public record PageContent(Page page, int version, byte[] content, String contentType) {
    }
}
