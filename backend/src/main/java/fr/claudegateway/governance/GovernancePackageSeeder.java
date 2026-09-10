package fr.claudegateway.governance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.governance.control.CommitSansTraceLlmControl;
import fr.claudegateway.governance.control.JugeFinDeTourControl;
import fr.claudegateway.governance.control.PromotionDetteBloquanteControl;

/**
 * Le <b>premier paquet de gouvernance</b>, semé au démarrage (F-52 / SF-52-03).
 *
 * <p>F-50 a posé les crochets, F-51 le catalogue, et ni l'un ni l'autre n'apporte de contenu : un
 * utilisateur qui ouvrait l'écran de gouvernance voyait une liste vide. Ce semeur fait exister le
 * contenu — les règles du PO, transposées — de la seule façon reproductible : livré <b>avec le
 * produit</b>, versionné avec lui, présent dans toute installation neuve. Le saisir à la main dans
 * l'écran d'administration l'aurait rendu absent partout ailleurs.</p>
 *
 * <p><b>Publié, jamais activé.</b> Le paquet écrit des fichiers sur la machine de l'utilisateur et
 * bloque des fins de tour : le subir sans l'avoir choisi serait exactement ce que F-51 a écarté en
 * faisant de la composition un geste. Aucune sélection, aucune activation n'est créée ici.</p>
 *
 * <p><b>Idempotent, et prudent avec l'admin.</b> Contenu identique → aucune écriture, pas même un
 * incrément de version : un redémarrage ne doit pas faire croire à une nouvelle rédaction. Contenu
 * différent → mise à jour et version incrémentée. Et un paquet <b>dépublié à la main reste
 * dépublié</b> : le drapeau n'est posé qu'à la création (arbitrage A5 du cadrage). Ranger son
 * catalogue est une décision d'admin, le produit ne la reprend pas à chaque démarrage.</p>
 *
 * <p><b>Tout ou rien.</b> Une ressource manquante fait renoncer entièrement : un paquet à moitié semé
 * — des règles sans gabarits, un contrôle sans sa note — serait pire qu'un catalogue vide, parce
 * qu'il aurait l'air complet.</p>
 *
 * <p><b>Isolation.</b> Les deux tables écrites ici ne portent pas de {@code user_id} : un paquet est
 * un contenu produit, comme un plan tarifaire (F-51 / SF-51-01). Le semeur n'écrit dans aucune table
 * qui en porte un.</p>
 */
@Component
public class GovernancePackageSeeder {

    private static final Logger log = LoggerFactory.getLogger(GovernancePackageSeeder.class);

    /** Identifiant du paquet. Immuable : c'est ce qui le reconnaît d'une version à l'autre. */
    public static final String SLUG = "savoir-durable";

    private static final String NAME = "Le savoir durable";

    private static final String SUMMARY = "Le travail est jetable, le savoir est durable : des "
            + "livrables qui ne disent pas quel outil les a écrits, la promotion de tout élément "
            + "durable vers la carte du projet, et un juge de fin de tour qui alerte plutôt que de "
            + "laisser passer.";

    /** Racine des ressources du paquet. Ce sont des documents : ils se relisent comme tels. */
    private static final String ROOT = "governance/" + SLUG + "/";

    /** Le fichier de règles, qui rejoint la consigne système — il n'est pas déposé sur le disque. */
    private static final String RULES_RESOURCE = "regles.md";

    /**
     * Les contrôles cités, <b>dans cet ordre</b>. L'ordre compte : le premier blocage l'emporte
     * (F-50), et réclamer le marqueur avant de compter la dette rend la correction lisible.
     */
    private static final List<String> CONTROL_IDS = List.of(
            CommitSansTraceLlmControl.ID, JugeFinDeTourControl.ID, PromotionDetteBloquanteControl.ID);

    /** Un fichier apporté : sa ressource, son chemin dans le projet, son genre. */
    private record SeededFile(String resource, String path, GovernanceFileKind kind) {
    }

    /** Les fichiers déposés, dans l'ordre où l'écran les annoncera. */
    private static final List<SeededFile> FILES = List.of(
            new SeededFile("GOUVERNANCE.md", "GOUVERNANCE.md", GovernanceFileKind.TEMPLATE),
            new SeededFile("PLAN-ACTION.md", "PLAN-ACTION.md", GovernanceFileKind.TEMPLATE),
            new SeededFile("STATE.md", "STATE.md", GovernanceFileKind.TEMPLATE),
            new SeededFile("explique.md", ".claude/skills/explique.md", GovernanceFileKind.SKILL),
            new SeededFile("plan-dashboard.md", ".claude/skills/plan-dashboard.md",
                    GovernanceFileKind.SKILL));

    private final GovernancePackageRepository packages;
    private final GovernancePackageFileRepository files;
    private final GovernanceControlRegistry registry;
    private final boolean enabled;

    public GovernancePackageSeeder(GovernancePackageRepository packages,
            GovernancePackageFileRepository files, GovernanceControlRegistry registry,
            @Value("${app.governance.seed-first-package:true}") boolean enabled) {
        this.packages = packages;
        this.files = files;
        this.registry = registry;
        this.enabled = enabled;
    }

    /** Sème au démarrage, sans jamais empêcher le produit de démarrer. */
    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        try {
            seed();
        } catch (RuntimeException ex) {
            // Un catalogue vide ne casse rien (F-51) ; un démarrage raté, si.
            log.warn("Paquet « {} » non semé : {}", SLUG, ex.getClass().getSimpleName());
        }
    }

    /**
     * Crée, met à jour ou laisse tel quel le paquet du produit.
     *
     * @return {@code true} si quelque chose a été écrit
     */
    @Transactional
    public boolean seed() {
        if (!enabled) {
            log.info("Paquet « {} » non semé : semeur désactivé.", SLUG);
            return false;
        }
        String rules = readResource(RULES_RESOURCE);
        if (rules != null && rules.length() > GovernancePackage.MAX_RULES_LENGTH) {
            // Ce texte part dans la consigne système à CHAQUE tour : la borne de F-51 vaut aussi
            // pour ce que le produit livre lui-même, sinon elle ne veut plus rien dire.
            log.warn("Paquet « {} » non semé : ses règles dépassent {} caractères.", SLUG,
                    GovernancePackage.MAX_RULES_LENGTH);
            return false;
        }
        List<GovernancePackageFile> desired = new ArrayList<>(FILES.size());
        int position = 0;
        for (SeededFile file : FILES) {
            String path = GovernancePath.normalizeOrNull(file.path());
            String content = readResource(file.resource());
            if (path == null || rules == null || content == null
                    || content.length() > GovernancePackageFile.MAX_CONTENT_LENGTH) {
                // Tout ou rien : un paquet à moitié semé aurait l'air complet.
                log.warn("Paquet « {} » non semé : ressource « {} » absente, trop volumineuse ou "
                        + "chemin invalide.", SLUG, file.resource());
                return false;
            }
            desired.add(GovernancePackageFile.builder().position(position++).path(path)
                    .kind(file.kind()).content(content).build());
        }
        List<String> controlIds = knownControls();

        Optional<GovernancePackage> existing = packages.findBySlug(SLUG);
        if (existing.isEmpty()) {
            create(rules, controlIds, desired);
            return true;
        }
        return updateIfChanged(existing.get(), rules, controlIds, desired);
    }

    private void create(String rules, List<String> controlIds, List<GovernancePackageFile> desired) {
        GovernancePackage pkg = GovernancePackage.builder()
                .slug(SLUG).name(NAME).summary(SUMMARY).rules(rules)
                .version(1)
                // Publié à la création, et à la création SEULEMENT : voir updateIfChanged.
                .published(true).publishedAt(OffsetDateTime.now())
                .build();
        pkg.setControlIdList(controlIds);
        GovernancePackage saved = packages.save(pkg);
        writeFiles(saved.getId(), desired);
        log.info("Paquet « {} » publié en version 1 ({} contrôle(s), {} fichier(s)).", SLUG,
                controlIds.size(), desired.size());
    }

    private boolean updateIfChanged(GovernancePackage pkg, String rules, List<String> controlIds,
            List<GovernancePackageFile> desired) {
        if (isUpToDate(pkg, rules, controlIds, desired)) {
            return false; // Ni écriture, ni incrément : personne n'a rien touché.
        }
        pkg.setName(NAME);
        pkg.setSummary(SUMMARY);
        pkg.setRules(rules);
        pkg.setControlIdList(controlIds);
        pkg.setVersion(pkg.getVersion() + 1);
        // `published` n'est PAS touché : un paquet que l'admin a rangé reste rangé (A5).
        GovernancePackage saved = packages.save(pkg);
        writeFiles(saved.getId(), desired);
        log.info("Paquet « {} » mis à jour en version {}.", SLUG, saved.getVersion());
        return true;
    }

    /** Vrai si le paquet stocké porte déjà exactement ce que le produit livre. */
    private boolean isUpToDate(GovernancePackage pkg, String rules, List<String> controlIds,
            List<GovernancePackageFile> desired) {
        if (!NAME.equals(pkg.getName()) || !SUMMARY.equals(pkg.getSummary())
                || !Objects.equals(rules, pkg.getRules())
                || !controlIds.equals(pkg.controlIdList())) {
            return false;
        }
        List<GovernancePackageFile> stored = files.findByPackageIdOrderByPositionAsc(pkg.getId());
        if (stored.size() != desired.size()) {
            return false;
        }
        for (int i = 0; i < stored.size(); i++) {
            GovernancePackageFile a = stored.get(i);
            GovernancePackageFile b = desired.get(i);
            if (!a.getPath().equals(b.getPath()) || a.getKind() != b.getKind()
                    || !Objects.equals(a.getContent(), b.getContent())) {
                return false;
            }
        }
        return true;
    }

    /** Efface puis réécrit : le contenu livré par le produit fait foi, comme à la publication. */
    private void writeFiles(java.util.UUID packageId, List<GovernancePackageFile> desired) {
        files.deleteByPackageId(packageId);
        for (GovernancePackageFile file : desired) {
            files.save(GovernancePackageFile.builder()
                    .packageId(packageId).position(file.getPosition()).path(file.getPath())
                    .kind(file.getKind()).content(file.getContent()).build());
        }
    }

    /**
     * Les contrôles que le serveur fournit réellement.
     *
     * <p>Un identifiant retiré du produit est <b>ignoré</b>, pas fatal : le paquet reste utile pour
     * ce qu'il apporte encore, et un démarrage ne doit pas échouer sur une ligne de catalogue.</p>
     */
    private List<String> knownControls() {
        List<String> known = new ArrayList<>(CONTROL_IDS.size());
        for (String id : CONTROL_IDS) {
            if (registry.exists(id)) {
                known.add(id);
            } else {
                log.warn("Contrôle « {} » cité par le paquet « {} » mais absent du produit : ignoré.",
                        id, SLUG);
            }
        }
        return List.copyOf(known);
    }

    /**
     * Lit une ressource du paquet, ou {@code null} si elle manque.
     *
     * <p>Visible du paquet pour qu'un test puisse simuler une ressource absente : c'est le seul mode
     * d'échec qu'on ne sait pas provoquer autrement, et c'est celui dont dépend la règle du « tout ou
     * rien ».</p>
     */
    String readResource(String name) {
        ClassPathResource resource = new ClassPathResource(ROOT + name);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("Ressource « {} » illisible ({}).", name, ex.getClass().getSimpleName());
            return null;
        }
    }
}
