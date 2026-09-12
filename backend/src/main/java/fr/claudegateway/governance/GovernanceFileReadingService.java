package fr.claudegateway.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.governance.dto.GovernanceFileComparison;
import fr.claudegateway.governance.dto.GovernanceFileComparison.ProjectFile;

/**
 * <b>Lire avant d'accepter</b> (F-75 / SF-75-02).
 *
 * <p>L'écran annonçait le chemin et le type de chaque fichier, jamais son <b>contenu</b> : on
 * demandait d'approuver un dépôt de fichiers <b>à l'aveugle, sur la machine d'un client</b>. Depuis
 * F-73, plus aucun confinement ne rattrape une surprise — ce que l'utilisateur a pu lire avant
 * d'activer est la seule chose qui reste entre lui et l'inattendu. Le contenu existait déjà dans le
 * modèle ; il ne manquait que de le rendre.</p>
 *
 * <p><b>Et le différentiel.</b> Le dépôt est idempotent : il ne remplace jamais un fichier présent.
 * Quand le fichier existe déjà, ce n'est donc pas celui du paquet qui s'appliquera — c'est celui qui
 * est en place. Le montrer est la seule façon de rendre l'annonce honnête.</p>
 *
 * <p><b>On ne lit que ce que le paquet apporte.</b> Un chemin absent du paquet est « introuvable »,
 * même s'il existe dans le dossier : ouvrir une lecture arbitraire du disque d'un client sous
 * couvert de gouvernance serait exactement l'inverse de ce que cette subfeature protège.</p>
 *
 * <p><b>Isolation.</b> Le poste est vérifié possédé par {@link GovernanceHostScope}, et chaque
 * dossier est lu par {@link GovernanceProjectFiles} à partir d'un {@link Workspace} déjà résolu sous
 * ce poste — jamais d'un identifiant reçu du client.</p>
 */
@Service
public class GovernanceFileReadingService {

    /**
     * Plafond de contenu rendu, en caractères. Un fichier plus long est coupé et la coupe est
     * <b>dite</b> : une troncature muette laisserait croire qu'on a tout lu.
     */
    public static final int MAX_CONTENT_CHARS = 200_000;

    /**
     * Nombre maximal de dossiers inspectés. Lire le même fichier sur cinquante machines pour
     * préparer un clic ferait payer un balayage de disque au prix d'une infobulle.
     */
    public static final int MAX_PROJECTS_INSPECTED = 20;

    private final GovernancePackageService packageService;
    private final GovernanceProjectFiles projectFiles;
    private final GovernanceHostScope hostScope;

    public GovernanceFileReadingService(GovernancePackageService packageService,
            GovernanceProjectFiles projectFiles, GovernanceHostScope hostScope) {
        this.packageService = packageService;
        this.projectFiles = projectFiles;
        this.hostScope = hostScope;
    }

    /**
     * Le contenu d'un fichier du paquet, et ce que chaque dossier du poste porte déjà sous ce chemin.
     *
     * @throws GovernancePackageNotFoundException si le paquet n'est pas publié, ou si le chemin
     *                                            n'appartient pas au paquet
     */
    @Transactional(readOnly = true)
    public GovernanceFileComparison read(UUID userId, GovernanceHostRef host, UUID packageId,
            String path) {
        GovernancePackage pkg = packageService.requirePublished(packageId);
        String wanted = GovernancePath.normalizeOrNull(path);
        GovernancePackageFile file = wanted == null ? null
                : packageService.filesOf(pkg.getId()).stream()
                        .filter(candidate -> wanted.equals(
                                GovernancePath.normalizeOrNull(candidate.getPath())))
                        .findFirst()
                        .orElse(null);
        if (file == null) {
            throw new GovernancePackageNotFoundException(
                    "Ce paquet n'apporte aucun fichier à ce chemin.");
        }

        String brought = file.getContent() == null ? "" : file.getContent();
        List<Workspace> projects = hostScope.projectsOf(userId, host);
        List<ProjectFile> compared = new ArrayList<>();
        int inspected = 0;
        for (Workspace workspace : projects) {
            if (inspected >= MAX_PROJECTS_INSPECTED) {
                break;
            }
            inspected++;
            compared.add(compare(userId, workspace, wanted, brought));
        }
        return new GovernanceFileComparison(wanted, file.getKind().name(), clamp(brought),
                brought.length() > MAX_CONTENT_CHARS, List.copyOf(compared),
                Math.max(0, projects.size() - inspected));
    }

    // -------------------------------------------------------------- internes

    private ProjectFile compare(UUID userId, Workspace workspace, String path, String brought) {
        Optional<Set<String>> present = projectFiles.listPaths(userId, workspace);
        if (present.isEmpty()) {
            // Machine éteinte : on ne prétend NI que le fichier existe, NI qu'il manque.
            return new ProjectFile(workspace.getId(), workspace.getName(), false, false, false, null,
                    false);
        }
        if (!present.get().contains(path)) {
            return new ProjectFile(workspace.getId(), workspace.getName(), true, false, false, null,
                    false);
        }
        String current = projectFiles.read(userId, workspace, path).orElse(null);
        if (current == null) {
            // Le chemin est là, mais illisible : on le dit « existant » — c'est ce qui compte pour
            // le dépôt, qui le laissera tel quel — sans inventer de contenu.
            return new ProjectFile(workspace.getId(), workspace.getName(), true, true, false, null,
                    false);
        }
        return new ProjectFile(workspace.getId(), workspace.getName(), true, true,
                current.equals(brought), clamp(current), current.length() > MAX_CONTENT_CHARS);
    }

    private static String clamp(String content) {
        return content.length() <= MAX_CONTENT_CHARS ? content
                : content.substring(0, MAX_CONTENT_CHARS);
    }
}
