package fr.claudegateway.governance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Où un élément durable se promeut</b> — les fichiers de carte réellement posés sur un poste
 * (F-93 / SF-93-01).
 *
 * <p>C'est la pièce que F-52 n'avait pas. Ses contrôles de fin de tour savaient <i>compter</i> une
 * dette et <i>nommer</i> ce qui restait à promouvoir, mais ils envoyaient tout vers la carte du
 * <b>projet</b> : un cluster, un VPN, un bastion n'appartiennent pas au projet — ils appartiennent au
 * <b>poste</b>, et doivent lui survivre. F-92 a créé la destination ; ce service la <b>nomme</b>.</p>
 *
 * <p><b>Réelles, jamais écrites en dur.</b> Les destinations sont les fichiers de genre
 * {@link GovernanceFileKind#MAP} apportés par les paquets <b>actifs</b> sur le poste. Un message qui
 * citerait six noms de fichiers gravés dans le code mentirait le jour où un paquet en apporte un
 * septième — et un message qui ment ne se corrige pas.</p>
 *
 * <p><b>Il lit les dépôts, pas les services</b>, et ce n'est pas un raccourci : un contrôle de F-50
 * est un composant du registre, le registre est une dépendance de
 * {@link GovernancePackageService}, et {@link GovernanceActivationService} en dépend à son tour.
 * Passer par ces services ferait un <b>cycle de beans</b> — le contexte refuse de démarrer, et le
 * premier test d'intégration le dit. Les lectures nécessaires sont trois requêtes sans logique
 * métier : elles se font ici.</p>
 *
 * <p><b>Aucun appel au runner.</b> Tout se lit en base. Ce service est interrogé <b>à chaque fin de
 * tour</b> par un contrôle : un aller-retour réseau vers la machine de l'utilisateur y serait payé
 * par chaque réponse, pour n'apprendre qu'une liste de noms que le catalogue connaît déjà.</p>
 *
 * <p><b>Il ne lève jamais.</b> Projet effacé, poste supprimé, paquet dépublié, projet d'autrui :
 * toutes ces situations rendent une liste <b>vide</b>, et l'appelant retombe sur une formulation
 * générique. Un contrôle de fin de tour qui échouerait sur une carte qu'il n'a pas su lister
 * prendrait le message d'un utilisateur en otage pour une raison qui ne le regarde pas.</p>
 *
 * <p><b>Isolation.</b> Le poste d'un projet est résolu par {@link GovernanceHostScope#hostOf(UUID,
 * UUID)}, qui passe par {@code workspaceService.requireOwned} ; les activations sont ensuite lues
 * par {@code user_id} <b>et</b> {@code host_id}. Le projet d'un autre utilisateur ne rend rien.</p>
 */
@Service
public class GovernanceMapDestinations {

    private static final Logger log = LoggerFactory.getLogger(GovernanceMapDestinations.class);

    /**
     * Destinations citées dans un message correctif. Un poste peut porter plusieurs paquets ; un
     * refus qui listerait trente fichiers cesserait d'être une action corrective.
     */
    public static final int MAX_CITED = 12;

    /** Ce qu'on dit quand la carte de ce poste n'a pas pu être listée — et qui reste vrai partout. */
    public static final String GENERIC =
            "la carte du poste — les fichiers « .md » posés à la racine, à côté des dossiers de projets";

    private final GovernanceActivationRepository activations;
    private final GovernancePackageRepository packages;
    private final GovernancePackageFileRepository files;
    private final GovernanceHostScope hostScope;

    public GovernanceMapDestinations(GovernanceActivationRepository activations,
            GovernancePackageRepository packages, GovernancePackageFileRepository files,
            GovernanceHostScope hostScope) {
        this.activations = activations;
        this.packages = packages;
        this.files = files;
        this.hostScope = hostScope;
    }

    /**
     * Les fichiers de carte attendus sur ce poste, <b>indexés par chemin</b> et dans l'ordre où les
     * paquets ont été activés.
     *
     * <p>Deux paquets peuvent apporter le même chemin : le <b>premier activé gagne</b>, exactement
     * comme au dépôt, qui ne remplace jamais ce qui est déjà là.</p>
     */
    @Transactional(readOnly = true)
    public Map<String, GovernancePackageFile> filesOf(UUID userId, GovernanceHostRef host) {
        if (userId == null || host == null) {
            return Map.of();
        }
        return index(activationsOf(userId, host));
    }

    /**
     * Les chemins de carte où le projet {@code workspaceId} peut promouvoir — ceux de <b>son
     * poste</b>.
     *
     * @return les chemins, éventuellement vides ; <b>jamais</b> {@code null}, et aucune exception
     */
    @Transactional(readOnly = true)
    public List<String> pathsForProject(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return List.of();
        }
        Optional<GovernanceHostRef> host = hostOf(userId, workspaceId);
        return host.map(ref -> List.copyOf(filesOf(userId, ref).keySet())).orElseGet(List::of);
    }

    /**
     * Les destinations d'un projet, telles qu'un <b>message correctif</b> les cite.
     *
     * @return la liste des fichiers, ou {@link #GENERIC} quand elle n'a pas pu être établie
     */
    @Transactional(readOnly = true)
    public String citedForProject(UUID userId, UUID workspaceId) {
        return cite(pathsForProject(userId, workspaceId));
    }

    /** Cite une liste de destinations, bornée — ou la formulation générique si elle est vide. */
    public static String cite(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return GENERIC;
        }
        StringBuilder cited = new StringBuilder();
        int count = 0;
        for (String path : paths) {
            if (count >= MAX_CITED) {
                cited.append('…');
                break;
            }
            if (count > 0) {
                cited.append(", ");
            }
            cited.append(path);
            count++;
        }
        return cited.toString();
    }

    // -------------------------------------------------------------- internes

    /** Le poste d'un projet <b>possédé</b>, ou rien : un projet d'autrui n'a pas de carte ici. */
    private Optional<GovernanceHostRef> hostOf(UUID userId, UUID workspaceId) {
        try {
            return Optional.ofNullable(hostScope.hostOf(userId, workspaceId));
        } catch (RuntimeException ex) {
            log.debug("Poste d'un projet non résolu ({})", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Les activations du poste, ou aucune : lister une carte ne fait jamais échouer un tour. */
    private List<GovernanceActivation> activationsOf(UUID userId, GovernanceHostRef host) {
        try {
            return activations.findByUserIdAndHostIdOrderByCreatedAtAsc(userId, host.hostId());
        } catch (RuntimeException ex) {
            log.debug("Activations du poste non lues ({})", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Les fichiers {@code MAP} des paquets encore publiés, dédoublonnés par chemin. */
    private Map<String, GovernancePackageFile> index(List<GovernanceActivation> active) {
        Map<String, GovernancePackageFile> expected = new LinkedHashMap<>();
        for (GovernanceActivation activation : active) {
            Optional<GovernancePackage> pkg = published(activation.getPackageId());
            if (pkg.isEmpty()) {
                continue; // Paquet dépublié ou effacé depuis : sa carte n'est plus attendue.
            }
            for (GovernancePackageFile file : filesOf(pkg.get().getId())) {
                if (file.getKind() != GovernanceFileKind.MAP) {
                    continue;
                }
                String path = GovernancePath.normalizeOrNull(file.getPath());
                if (path != null) {
                    expected.putIfAbsent(path, file);
                }
            }
        }
        return expected;
    }

    /** Le paquet s'il est encore <b>publié</b> : un brouillon n'apporte pas de destination. */
    private Optional<GovernancePackage> published(UUID packageId) {
        if (packageId == null) {
            return Optional.empty();
        }
        try {
            return packages.findById(packageId).filter(GovernancePackage::isPublished);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** Les fichiers d'un paquet, dans l'ordre de rédaction. */
    private List<GovernancePackageFile> filesOf(UUID packageId) {
        try {
            return files.findByPackageIdOrderByPositionAsc(packageId);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }
}
