package fr.claudegateway.governance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import fr.claudegateway.governance.control.IntegritePosteControl;
import fr.claudegateway.governance.control.JugeIndependantControl;
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

    private static final String SUMMARY = "Le travail est jetable, le savoir est durable : une "
            + "carte à la racine du poste où la connaissance s'accumule d'un projet à l'autre, des "
            + "livrables qui ne disent pas quel outil les a écrits, la promotion de tout élément "
            + "durable vers cette carte — en disant dans quel fichier —, et un juge de fin de tour "
            + "qui alerte plutôt que de laisser passer.";

    /** Racine des ressources du paquet. Ce sont des documents : ils se relisent comme tels. */
    private static final String ROOT = "governance/" + SLUG + "/";

    /** Le fichier de règles, qui rejoint la consigne système — il n'est pas déposé sur le disque. */
    private static final String RULES_RESOURCE = "regles.md";

    /**
     * Les empreintes des contenus que ce paquet a <b>déjà publiés</b> (F-96 / SF-96-02).
     *
     * <p>Sans elles, un poste activé avant F-96 — qui ne porte aucune empreinte de dépôt — verrait
     * tous ses artefacts classés « modifiés localement » : la mise à jour ne toucherait que les
     * postes nés après elle, c'est-à-dire <b>pas</b> ceux qui portent la dette.</p>
     */
    private static final String KNOWN_DIGESTS_RESOURCE = "empreintes-anterieures.txt";

    /**
     * Les contrôles cités, <b>dans cet ordre</b>. L'ordre compte : le premier blocage l'emporte
     * (F-50), et réclamer le marqueur avant de compter la dette rend la correction lisible.
     *
     * <p><b>Le juge indépendant vient en dernier</b> (F-94 / SF-94-03), et ce n'est pas cosmétique :
     * c'est le seul qui coûte un appel. Rangé après les contrôles gratuits, il n'est consulté que si
     * la forme est déjà bonne — l'ordre <b>est</b> le garde-fou de dépense.</p>
     *
     * <p><b>L'intégrité du poste vient juste avant lui</b> (F-95 / SF-95-03) : elle coûte des
     * allers-retours vers la machine, mais pas un appel au fournisseur. Le même raisonnement la
     * range donc après les contrôles gratuits et <b>avant</b> celui qui se paie.</p>
     */
    private static final List<String> CONTROL_IDS = List.of(
            CommitSansTraceLlmControl.ID, JugeFinDeTourControl.ID, PromotionDetteBloquanteControl.ID,
            IntegritePosteControl.ID, JugeIndependantControl.ID);

    /** Un fichier apporté : sa ressource, son chemin dans le projet, son genre. */
    private record SeededFile(String resource, String path, GovernanceFileKind kind) {
    }

    /**
     * Les fichiers déposés, dans l'ordre où l'écran les annoncera.
     *
     * <p><b>La carte d'abord</b> (F-92 / SF-92-01) : ce sont les seuls qui se posent à la
     * <b>racine du poste</b>, et ce sont eux qui font exister la destination du savoir. Les annoncer
     * en tête dit la bonne chose à qui lit l'annonce avant d'activer — le reste est l'outillage d'un
     * projet, la carte est ce qui lui survit.</p>
     *
     * <p>Les chemins de carte sont <b>plats</b> : la carte, ce sont les fichiers <b>de la racine</b>,
     * quelle que soit cette racine (« dev » chez un poste, « infra » chez un autre). Aucun dossier
     * n'est créé — un dossier serait pris pour un projet, et c'est précisément la confusion que cette
     * disposition évite.</p>
     */
    private static final List<SeededFile> FILES = List.of(
            new SeededFile("carte/README.md", "README.md", GovernanceFileKind.MAP),
            new SeededFile("carte/acces.md", "acces.md", GovernanceFileKind.MAP),
            new SeededFile("carte/reseau.md", "reseau.md", GovernanceFileKind.MAP),
            new SeededFile("carte/plateformes.md", "plateformes.md", GovernanceFileKind.MAP),
            new SeededFile("carte/donnees.md", "donnees.md", GovernanceFileKind.MAP),
            new SeededFile("carte/exploitation.md", "exploitation.md", GovernanceFileKind.MAP),
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

    /**
     * Le bean lui-même, pour appeler {@link #seed()} <b>à travers le proxy</b> (F-92 / SF-92-04).
     *
     * <p>{@link #seedOnStartup()} n'est pas transactionnel — il ne peut pas l'être, il attrape tout
     * pour ne jamais empêcher le démarrage. S'il appelait {@code seed()} directement
     * ({@code this.seed()}), l'auto-invocation <b>court-circuiterait le proxy Spring</b> et
     * {@code @Transactional} ne s'appliquerait pas : la requête modifiante {@code deleteByPackageId}
     * du chemin de mise à jour lèverait alors {@code InvalidDataAccessApiUsageException} à <b>chaque
     * démarrage</b>, et le paquet resterait figé sans ses fichiers de carte. Passer par le bean
     * injecté rétablit la transaction. {@link ObjectProvider} évite le cycle d'initialisation d'une
     * auto-référence directe ; en test unitaire (construction manuelle) il est {@code null} et l'on
     * retombe sur {@code this}.</p>
     */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<GovernancePackageSeeder> self;

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
            // À TRAVERS LE PROXY : sans cela, l'auto-invocation priverait seed() de sa transaction.
            transactionalSelf().seed();
        } catch (RuntimeException ex) {
            // Un catalogue vide ne casse rien (F-51) ; un démarrage raté, si. La STACK COMPLÈTE est
            // journalisée : sans elle, un échec en production (par ex. le défaut de transaction que
            // SF-92-04 corrige) ne se lit que par sa classe, et la cause reste invisible.
            log.warn("Paquet « {} » non semé.", SLUG, ex);
        }
    }

    /** Le bean proxifié quand Spring l'a injecté ; {@code this} en test unitaire (proxy absent). */
    private GovernancePackageSeeder transactionalSelf() {
        return self != null ? self.getObject() : this;
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
        String missingRule = ruleMissingFrom(rules);
        if (missingRule != null) {
            // Un paquet qui annoncerait une règle absente de son propre texte serait pire qu'un
            // paquet incomplet : il aurait l'air complet. Les trois invariants de la racine (F-93 /
            // SF-93-01) sont déclarés une fois dans GovernanceHostRule, et le document DOIT les
            // citer par leur identifiant — c'est ce par quoi F-95 s'y branchera.
            log.warn("Paquet « {} » non semé : la règle « {} » ne figure pas dans « {} ».", SLUG,
                    missingRule, RULES_RESOURCE);
            return false;
        }
        if (rules != null && rules.length() > GovernancePackage.MAX_RULES_LENGTH) {
            // Ce texte part dans la consigne système à CHAQUE tour : la borne de F-51 vaut aussi
            // pour ce que le produit livre lui-même, sinon elle ne veut plus rien dire.
            log.warn("Paquet « {} » non semé : ses règles dépassent {} caractères.", SLUG,
                    GovernancePackage.MAX_RULES_LENGTH);
            return false;
        }
        // Le registre des empreintes déjà publiées, tel que le produit le livre. Une ressource
        // absente n'est PAS fatale : ce n'est pas un fichier déposé, et le « tout ou rien » ne s'y
        // applique pas — le paquet reste utile, simplement sans rattrapage.
        Map<String, List<String>> declared = declaredDigests();

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
            GovernancePackageFile built = GovernancePackageFile.builder().position(position++)
                    .path(path)
                    // TOUT ce que le produit livre est un ARTEFACT GÉNÉRÉ (F-96 / SF-96-01) : un
                    // skill, un gabarit, un fichier de carte. Le produit les a écrits, il a donc le
                    // droit de les corriger — mais SEULEMENT là où ils sont restés intacts. Un
                    // gabarit rempli a été touché : il redevient du contenu utilisateur, et rien ne
                    // l'écrase plus jamais.
                    .generated(true)
                    .kind(file.kind()).content(content).build();
            built.setKnownDigestList(declared.getOrDefault(path, List.of()));
            desired.add(built);
        }
        List<String> controlIds = knownControls();

        Optional<GovernancePackage> existing = packages.findBySlug(SLUG);
        if (existing.isEmpty()) {
            create(rules, controlIds, desired);
            return true;
        }
        // Le registre d'empreintes SURVIT à la republication : « efface puis réécrit » le perdrait
        // à chaque démarrage, c'est-à-dire toujours. Et le contenu qu'on remplace y entre.
        carryDigests(existing.get().getId(), desired);
        return updateIfChanged(existing.get(), rules, controlIds, desired);
    }

    /**
     * Reporte sur les fichiers à écrire les empreintes déjà connues du chemin, et <b>y ajoute celle
     * du contenu qu'on remplace</b> (F-96 / SF-96-02).
     *
     * <p>Sans ce report, le registre serait remis à zéro à chaque republication — et la
     * reconnaissance d'un artefact d'avant-hier ne fonctionnerait jamais.</p>
     */
    private void carryDigests(java.util.UUID packageId, List<GovernancePackageFile> desired) {
        Map<String, GovernancePackageFile> stored = files
                .findByPackageIdOrderByPositionAsc(packageId).stream()
                .collect(java.util.stream.Collectors.toMap(GovernancePackageFile::getPath,
                        file -> file, (first, second) -> first));
        for (GovernancePackageFile file : desired) {
            GovernancePackageFile previous = stored.get(file.getPath());
            if (previous == null) {
                continue;
            }
            List<String> replaced = GovernanceDigest.sameContent(previous.getContent(),
                    file.getContent()) ? List.of()
                            : List.of(GovernanceDigest.of(previous.getContent()));
            file.setKnownDigestList(GovernanceKnownDigests.merge(replaced,
                    previous.knownDigestList(), file.knownDigestList()));
        }
    }

    /**
     * Les empreintes déclarées par la ressource, par chemin de dépôt.
     *
     * <p>Une ligne mal formée est <b>ignorée</b> plutôt que fatale : une empreinte manquante coûte
     * un fichier non reconnu — donc <b>conservé</b> —, là où un démarrage raté coûte le produit.</p>
     */
    private Map<String, List<String>> declaredDigests() {
        String raw = readResource(KNOWN_DIGESTS_RESOURCE);
        if (raw == null) {
            log.debug("Aucune empreinte antérieure déclarée pour « {} ».", SLUG);
            return Map.of();
        }
        Map<String, List<String>> byPath = new java.util.LinkedHashMap<>();
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length != 2 || parts[0].length() != GovernanceDigest.LENGTH) {
                log.debug("Ligne d'empreinte ignorée dans « {} ».", KNOWN_DIGESTS_RESOURCE);
                continue;
            }
            String path = GovernancePath.normalizeOrNull(parts[1]);
            if (path == null) {
                continue;
            }
            byPath.computeIfAbsent(path, key -> new ArrayList<>()).add(parts[0]);
        }
        return byPath;
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
                    || a.isGenerated() != b.isGenerated()
                    || !a.knownDigestList().equals(b.knownDigestList())
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
                    .kind(file.getKind()).content(file.getContent())
                    .generated(file.isGenerated())
                    .knownDigests(GovernanceKnownDigests.join(file.knownDigestList())).build());
        }
    }

    /**
     * Le premier identifiant de {@link GovernanceHostRule} que le texte de règles ne cite pas.
     *
     * <p>Un texte <b>absent</b> n'est pas jugé ici : le « tout ou rien » de {@link #seed()} s'en
     * charge déjà, et deux messages pour la même cause en rendraient un des deux trompeur.</p>
     *
     * @return l'identifiant manquant, ou {@code null} si les trois sont là
     */
    static String ruleMissingFrom(String rules) {
        if (rules == null) {
            return null;
        }
        for (String id : GovernanceHostRule.ids()) {
            if (!rules.contains(id)) {
                return id;
            }
        }
        return null;
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
