package fr.claudegateway.governance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.promptsource.PromptSourceStore;
import fr.claudegateway.atelier.skills.SkillCatalogService;
import fr.claudegateway.atelier.skills.SkillEntry;
import fr.claudegateway.governance.dto.GovernanceEffectiveView;
import fr.claudegateway.governance.dto.GovernanceEffectiveView.PackageLag;
import fr.claudegateway.governance.dto.GovernanceEffectiveView.RulesFile;
import fr.claudegateway.governance.dto.GovernanceEffectiveView.SkillView;
import fr.claudegateway.governance.dto.GovernanceEffectiveView.SubjectRules;

/**
 * <b>Ce qui s'applique vraiment</b> sur un poste (F-177 / SF-177-04, décision D6).
 *
 * <p>Trois questions, une lecture : quelles <b>règles du client</b> valent (le {@code GOUVERNANCE.md} de
 * la racine, lu sur la machine ; ceux des sujets, tels que la consigne les a lus au dernier tour — sans
 * aller-retour par sujet) ; quels <b>skills</b> existent et d'où ils viennent (déposés par un paquet,
 * ou écrits chez le client) ; et quels <b>paquets</b> sont réellement déposés, à quelle version — le
 * retard que personne ne voyait (« fichiers en v6, paquet en v17 », « jamais déposé »).</p>
 *
 * <p><b>Isolation</b> : le poste est résolu possédé par l'appelant ({@link GovernanceHostScope}) ;
 * activations, empreintes et projets sont lus sous {@code user_id}. N'écrit rien.</p>
 */
@Service
public class GovernanceEffectiveService {

    private static final Logger log = LoggerFactory.getLogger(GovernanceEffectiveService.class);

    static final String RULES_FILE = PromptSourceStore.GOVERNANCE_FILE;
    static final int EXCERPT_CHARS = 4_000;

    private final GovernanceHostScope hostScope;
    private final GovernanceHostFiles hostFiles;
    private final GovernanceActivationRepository activations;
    private final GovernanceDepositedFileRepository deposited;
    private final GovernancePackageService packageService;
    private final WorkspaceService workspaceService;
    private PromptSourceStore promptSourceStore;
    private SkillCatalogService skillCatalog;

    public GovernanceEffectiveService(GovernanceHostScope hostScope, GovernanceHostFiles hostFiles,
            GovernanceActivationRepository activations, GovernanceDepositedFileRepository deposited,
            GovernancePackageService packageService, WorkspaceService workspaceService) {
        this.hostScope = hostScope;
        this.hostFiles = hostFiles;
        this.activations = activations;
        this.deposited = deposited;
        this.packageService = packageService;
        this.workspaceService = workspaceService;
    }

    @Autowired(required = false)
    public void setPromptSourceStore(PromptSourceStore promptSourceStore) {
        this.promptSourceStore = promptSourceStore;
    }

    @Autowired(required = false)
    public void setSkillCatalog(SkillCatalogService skillCatalog) {
        this.skillCatalog = skillCatalog;
    }

    /** La vue d'un poste <b>déjà vérifié possédé</b>. */
    public GovernanceEffectiveView describe(UUID userId, GovernanceHostRef host) {
        // 1) Les paquets et leur dépôt réel — et, au passage, d'où vient chaque fichier déposé.
        Map<String, String> packageOfPath = new HashMap<>();
        List<PackageLag> packages = new ArrayList<>();
        for (GovernanceActivation activation : activations.findByUserIdAndHostIdOrderByCreatedAtAsc(userId,
                host.hostId())) {
            GovernancePackage pkg;
            try {
                pkg = packageService.require(activation.getPackageId());
            } catch (RuntimeException ex) {
                continue; // Paquet retiré du catalogue : rien à dire de son dépôt.
            }
            List<GovernanceDepositedFile> files = deposited.findByUserIdAndHostIdAndPackageId(userId,
                    host.hostId(), pkg.getId());
            for (GovernanceDepositedFile file : files) {
                packageOfPath.putIfAbsent(file.getPath(), pkg.getName());
            }
            packages.add(lagOf(pkg.getId(), pkg.getName(), pkg.getVersion(), files));
        }

        // 2) Les règles du poste, lues sur la machine.
        RulesFile hostRules = host.hosted()
                ? new RulesFile("SANS_MACHINE", null, false)
                : rulesOf(hostFiles.read(userId, host, RULES_FILE));

        // 3) Les sujets : leurs règles et leurs skills, tels que la consigne les a lus (cache).
        List<SubjectRules> subjects = new ArrayList<>();
        List<SkillView> skills = new ArrayList<>();
        List<Workspace> projects = hostScope.projectsOf(userId, host);
        for (Workspace project : projects) {
            subjects.add(new SubjectRules(project.getId(), project.getName(), cachedRules(userId, project)));
            for (String path : cachedTree(userId, project)) {
                String name = SkillCatalogService.nameOf(path);
                if (name != null) {
                    String pkgName = packageOfPath.get(path);
                    skills.add(new SkillView(name, path, SkillEntry.SUJET, project.getName(),
                            pkgName == null ? "CLIENT" : "PAQUET", pkgName));
                }
            }
        }

        // 4) Les skills du poste : ceux de la racine, via le terminal du poste.
        if (!host.hosted() && skillCatalog != null) {
            Optional<Workspace> terminal = workspaceService.findHostTerminal(userId, host.hostId());
            if (terminal.isPresent()) {
                try {
                    List<SkillView> hostSkills = new ArrayList<>();
                    for (SkillEntry entry : skillCatalog.catalog(userId, terminal.get())) {
                        String pkgName = packageOfPath.get(entry.path());
                        hostSkills.add(new SkillView(entry.name(), entry.path(), SkillEntry.POSTE, null,
                                pkgName == null ? "CLIENT" : "PAQUET", pkgName));
                    }
                    skills.addAll(0, hostSkills);
                } catch (RuntimeException ex) {
                    log.debug("Skills du poste non lus ({})", ex.getClass().getSimpleName());
                }
            }
        }
        return new GovernanceEffectiveView(host.ref(), hostRules, List.copyOf(subjects), List.copyOf(skills),
                List.copyOf(packages));
    }

    /** L'état de dépôt d'un paquet d'après les empreintes laissées sur le poste. */
    static PackageLag lagOf(UUID packageId, String name, int packageVersion, List<GovernanceDepositedFile> files) {
        if (files == null || files.isEmpty()) {
            return new PackageLag(packageId, name, packageVersion, null, null, 0, "JAMAIS_DEPOSE",
                    "Activé, jamais déposé sur ce poste : aucun fichier n'y a été écrit.");
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (GovernanceDepositedFile file : files) {
            min = Math.min(min, file.getPackageVersion());
            max = Math.max(max, file.getPackageVersion());
        }
        if (min >= packageVersion) {
            return new PackageLag(packageId, name, packageVersion, min, max, files.size(), "A_JOUR",
                    "À jour : fichiers en v" + packageVersion + ".");
        }
        String filesVersions = min == max ? "v" + min : "v" + min + " à v" + max;
        return new PackageLag(packageId, name, packageVersion, min, max, files.size(), "EN_RETARD",
                "En retard : fichiers en " + filesVersions + ", paquet en v" + packageVersion + ".");
    }

    private static RulesFile rulesOf(GovernanceHostFiles.HostFileRead read) {
        return switch (read.presence()) {
            case PRESENT -> excerpt("PRESENT", read.contentOrEmpty(), read.truncated());
            case ABSENT -> new RulesFile("ABSENT", null, false);
            case UNREACHABLE -> new RulesFile("INJOIGNABLE", null, false);
            case UNSUPPORTED -> new RulesFile("SANS_MACHINE", null, false);
            default -> new RulesFile("INCONNU", null, false);
        };
    }

    private RulesFile cachedRules(UUID userId, Workspace project) {
        if (promptSourceStore == null) {
            return new RulesFile("INCONNU", null, false);
        }
        try {
            if (!promptSourceStore.isPrimed(userId, project.getId())) {
                return new RulesFile("INCONNU", null, false);
            }
            return promptSourceStore.read(userId, project.getId(), RULES_FILE)
                    .map(content -> excerpt("PRESENT", content, false))
                    .orElse(new RulesFile("ABSENT", null, false));
        } catch (RuntimeException ex) {
            return new RulesFile("INCONNU", null, false);
        }
    }

    private List<String> cachedTree(UUID userId, Workspace project) {
        if (promptSourceStore == null) {
            return List.of();
        }
        try {
            return promptSourceStore.tree(userId, project.getId());
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static RulesFile excerpt(String state, String content, boolean truncatedAlready) {
        String body = content == null ? "" : content.strip();
        boolean cut = body.length() > EXCERPT_CHARS;
        return new RulesFile(state, cut ? body.substring(0, EXCERPT_CHARS) : body, cut || truncatedAlready);
    }
}
