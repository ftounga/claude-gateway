package fr.claudegateway.governance.integrite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.governance.GovernanceHostFiles;
import fr.claudegateway.governance.GovernanceHostFiles.HostFileRead;
import fr.claudegateway.governance.GovernanceHostFiles.Presence;
import fr.claudegateway.governance.GovernanceHostRef;
import fr.claudegateway.governance.GovernanceHostScope;
import fr.claudegateway.governance.GovernanceMapDestinations;
import fr.claudegateway.governance.GovernanceMapDigest;
import fr.claudegateway.governance.GovernancePackageFile;
import fr.claudegateway.governance.GovernanceProjectFiles;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * <b>L'intégrité d'un poste, lue sur la machine</b> (F-95 / SF-95-02) — l'équivalent
 * d'{@code infra-doctor}, réduit à ce qui a du sens dans le produit.
 *
 * <p>Le produit sait ce qu'un paquet <b>devrait</b> avoir déposé ; il ne sait pas ce que la racine
 * d'un poste <b>porte</b> aujourd'hui. Un fichier de carte a pu être supprimé, un dossier déplacé, un
 * dépôt cloné au mauvais endroit. <b>C'est la machine qui fait foi</b> — même doctrine que F-92 et
 * F-94 —, et c'est ce qui rend un constat vrai plutôt que plausible.</p>
 *
 * <p><b>Ce n'est pas un script pour autant.</b> F-50 a posé qu'un paquet porte des
 * <b>identifiants</b> de contrôles présents dans le produit, jamais du code : un catalogue ouvert
 * ferait s'exécuter, sur la machine de chaque utilisateur, du code publié par quelqu'un d'autre.
 * Cette inspection est donc un <b>composant du serveur</b> ; elle <i>lit</i> la machine avec les
 * outils du runner, et la seule commande qu'elle émet est une <b>constante</b> sans interpolation
 * ({@link #COMMANDE_STATUT}).</p>
 *
 * <p><b>Trois passes, et l'ordre est le message</b> : la carte d'abord (sans elle, rien d'autre ne
 * compte), puis les projets, puis les liens morts. Le rapport ne cite que ses premiers constats
 * (SF-95-01) : l'ordre décide donc de ce qu'un modèle lira en premier.</p>
 *
 * <p><b>Elle n'écrit rien, et ne lève jamais.</b> C'est le contrat d'un contrôle de F-50 — juger,
 * sans effet de bord — et la prudence de F-92 : machine muette, poste effacé, paquet dépublié
 * rendent un rapport <b>silencieux</b>, jamais une exception et <b>jamais</b> un rapport « sain ».
 * Afficher « tout va bien » sur un poste dont on n'a rien lu serait le pire des deux résultats.</p>
 *
 * <p><b>Le contenu n'est jamais journalisé</b> : ce sont les fichiers de l'utilisateur, sur la
 * machine d'un client. Le journal du runner dit <i>qu'on a lu</i>, jamais <i>ce qu'on a lu</i>.</p>
 *
 * <p><b>Aucune transaction ouverte ici</b>, et ce n'est pas un oubli. Chaque dépendance porte la
 * sienne ; une transaction englobante ne servirait à rien — l'inspection n'écrit pas — et elle
 * serait un <b>piège</b> : une lecture qui lève à l'intérieur (projet effacé, poste d'autrui) marque
 * la transaction « rollback-only », et le tour de l'utilisateur échouerait au moment du commit sur
 * une exception que ce service a pourtant rattrapée exprès. C'est un test d'intégration qui l'a
 * appris, pas une relecture.</p>
 *
 * <p><b>Isolation.</b> Le poste arrive <b>déjà vérifié possédé</b> ({@link GovernanceHostScope}),
 * les projets sont lus par {@code user_id} <b>et</b> {@code host_id}, et les fichiers passent par
 * les deux services qui reçoivent un poste ou un projet déjà vérifié.</p>
 */
@Service
public class IntegriteInspection {

    private static final Logger log = LoggerFactory.getLogger(IntegriteInspection.class);

    /**
     * La <b>seule</b> commande émise, et elle est constante.
     *
     * <p>Rien n'y est interpolé : le dossier du dépôt voyage dans le champ {@code cwd}, que le
     * runner résout lui-même. Un nom de dossier concaténé dans une ligne de commande serait une
     * injection de shell sur la machine d'un client — et {@code cmd.exe} comme PowerShell ne
     * citeraient pas de la même façon (SF-38-23, SF-38-27). {@code git} est le seul outil qui parle
     * la même langue sur les trois systèmes.</p>
     */
    static final String COMMANDE_STATUT = "git status --porcelain";

    /** Nom d'outil du journal pour cette lecture : une raison de lire, une ligne. */
    static final String OUTIL_STATUT = "governance_repo_status";

    /** Délai du {@code git status}. Assez pour un gros dépôt, trop court pour geler un tour. */
    static final long DELAI_STATUT_MS = 15_000L;

    /** Appels runner d'une inspection. Au-delà, ce n'est plus un contrôle, c'est un balayage. */
    static final int MAX_APPELS = 24;

    /** Projets inspectés par passage. Les suivants le seront au passage d'après. */
    static final int MAX_PROJETS = 8;

    /** Notes non versionnées citées par dépôt : au-delà, c'est le dépôt entier qu'il faut revoir. */
    static final int MAX_NOTES_CITEES = 5;

    /**
     * Taille de listage au-delà de laquelle on ne conclut <b>pas</b> l'absence d'un fichier.
     *
     * <p>Le listage du runner est borné (20 000 entrées, 512 Kio) : une arborescence qui en approche
     * est probablement <b>tronquée</b>, et conclure « {@code STATE.md} manque » d'une liste
     * incomplète fabriquerait une erreur là où il n'y a rien.</p>
     */
    static final int SEUIL_LISTAGE_INCOMPLET = 5_000;

    /** Le fichier d'état d'un sujet. */
    static final String STATE = "STATE.md";

    /** La carte du projet — ses cases comptent dans la dette, comme le dit le paquet. */
    static final String PLAN_ACTION = "PLAN-ACTION.md";

    /** L'index de la carte : le seul fichier de carte soumis au seuil de faits. */
    static final String INDEX = "README.md";

    /** Le dossier où se clonent les dépôts clients (F-93, {@code clonage/depot-dans-repos}). */
    static final String REPOS = "repos/";

    private final GovernanceMapDestinations destinations;
    private final GovernanceHostFiles hostFiles;
    private final GovernanceHostScope hostScope;
    private final GovernanceProjectFiles projectFiles;
    private final RunnerToolGateway gateway;
    private final RunnerAuditService auditService;

    public IntegriteInspection(GovernanceMapDestinations destinations, GovernanceHostFiles hostFiles,
            GovernanceHostScope hostScope, GovernanceProjectFiles projectFiles,
            RunnerToolGateway gateway, RunnerAuditService auditService) {
        this.destinations = destinations;
        this.hostFiles = hostFiles;
        this.hostScope = hostScope;
        this.projectFiles = projectFiles;
        this.gateway = gateway;
        this.auditService = auditService;
    }

    /**
     * Le rapport d'intégrité du poste d'un <b>projet</b> — la forme qu'emploie le contrôle de fin de
     * tour, qui ne connaît que le projet en cours.
     *
     * @return le rapport ; {@link IntegriteRapport#silencieux()} si rien n'a pu être inspecté
     */
    public IntegriteRapport deProjet(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return IntegriteRapport.silencieux();
        }
        try {
            return dePoste(userId, hostScope.hostOf(userId, workspaceId));
        } catch (RuntimeException ex) {
            // Projet effacé, projet d'autrui : on ne prend pas le tour de quelqu'un en otage.
            log.debug("Poste d'un projet non résolu ({})", ex.getClass().getSimpleName());
            return IntegriteRapport.silencieux();
        }
    }

    /**
     * Le rapport d'intégrité d'un poste <b>déjà vérifié possédé</b>.
     *
     * @return le rapport ; <b>jamais</b> {@code null}, et aucune exception
     */
    public IntegriteRapport dePoste(UUID userId, GovernanceHostRef host) {
        if (userId == null || host == null || !hostFiles.supports(host)) {
            return IntegriteRapport.silencieux(); // Poste « Hébergé » : pas de racine, pas de carte.
        }
        try {
            return inspecte(userId, host);
        } catch (RuntimeException ex) {
            log.debug("Inspection d'intégrité abandonnée ({})", ex.getClass().getSimpleName());
            return IntegriteRapport.silencieux();
        }
    }

    // -------------------------------------------------------------- internes

    private IntegriteRapport inspecte(UUID userId, GovernanceHostRef host) {
        Map<String, GovernancePackageFile> attendus = destinations.filesOf(userId, host);
        if (attendus.isEmpty()) {
            return IntegriteRapport.silencieux(); // Rien d'activé : aucune carte n'est attendue.
        }
        Budget budget = new Budget();
        List<IntegriteConstat> constats = new ArrayList<>();

        Map<String, String> carte = passeCarte(userId, host, attendus.keySet(), budget, constats);
        if (carte == null) {
            // La machine s'est tue : on n'a rien lu, et on ne dit surtout pas que tout va bien.
            return IntegriteRapport.silencieux();
        }
        passeProjets(userId, host, budget, constats);
        passeLiensMorts(userId, host, carte, attendus.keySet(), budget, constats);
        return IntegriteRapport.de(constats);
    }

    /**
     * La carte : présence, structure, et légèreté de l'index.
     *
     * @return les contenus lus, ou {@code null} si la machine s'est tue
     */
    private Map<String, String> passeCarte(UUID userId, GovernanceHostRef host,
            Set<String> attendus, Budget budget, List<IntegriteConstat> constats) {
        Map<String, String> contenus = new java.util.LinkedHashMap<>();
        for (String chemin : attendus) {
            if (budget.epuise()) {
                break;
            }
            budget.consomme();
            HostFileRead lecture = hostFiles.read(userId, host, chemin);
            if (lecture.presence() == Presence.UNREACHABLE) {
                return null;
            }
            if (lecture.presence() == Presence.ABSENT) {
                constats.add(IntegriteConstat.carteAbsente(chemin));
                continue;
            }
            if (lecture.presence() != Presence.PRESENT) {
                continue; // Illisible : on ne conclut rien de ce qu'on n'a pas lu.
            }
            String contenu = lecture.contentOrEmpty();
            contenus.put(chemin, contenu);
            GovernanceMapDigest.Digest digest = GovernanceMapDigest.of(contenu, chemin);
            if (digest.sections().isEmpty()) {
                constats.add(IntegriteConstat.carteSansStructure(chemin));
            }
            if (estIndex(chemin) && digest.facts() > IntegriteConstat.SEUIL_INDEX_FAITS
                    && !lecture.truncated()) {
                // Sur un contenu coupé, le compte est faux par construction : on se tait.
                constats.add(IntegriteConstat.indexSurcharge(chemin, digest.facts()));
            }
        }
        return contenus;
    }

    /** Les projets : dépôt égaré, {@code STATE.md} absent, dette — et les notes chez le client. */
    private void passeProjets(UUID userId, GovernanceHostRef host, Budget budget,
            List<IntegriteConstat> constats) {
        int inspectes = 0;
        for (Workspace projet : projets(userId, host)) {
            if (inspectes >= MAX_PROJETS || budget.epuise()) {
                break;
            }
            inspectes++;
            budget.consomme();
            Optional<Set<String>> chemins = listePaths(userId, projet);
            if (chemins.isEmpty()) {
                continue; // Arborescence illisible : on ne conclut rien sur ce projet.
            }
            String nom = cite(projet);
            if (estUnDepotGit(chemins.get())) {
                if (sousRepos(projet)) {
                    notesNonVersionnees(userId, projet, nom, budget, constats);
                } else {
                    // Un dépôt parmi les sujets : on ne lui réclame pas de STATE.md, ce n'en est
                    // pas un. Le geste de F-93 dit déjà où il doit aller.
                    constats.add(IntegriteConstat.projetDepotGit(nom));
                }
                continue;
            }
            if (!chemins.get().contains(STATE)) {
                if (chemins.get().size() < SEUIL_LISTAGE_INCOMPLET) {
                    constats.add(IntegriteConstat.stateAbsent(nom));
                }
                continue; // Sans STATE.md, il n'y a pas de dette à lire.
            }
            dette(userId, host, projet, nom, chemins.get(), budget, constats);
        }
    }

    /** La dette d'un projet : ses cases ouvertes, et ce que son statut en fait. */
    private void dette(UUID userId, GovernanceHostRef host, Workspace projet, String nom,
            Set<String> chemins, Budget budget, List<IntegriteConstat> constats) {
        if (budget.epuise()) {
            return;
        }
        budget.consomme();
        StateMarkdown.Lecture etat = StateMarkdown.of(lis(userId, projet, STATE));
        int cases = etat.casesOuvertes();
        if (chemins.contains(PLAN_ACTION) && !budget.epuise()) {
            budget.consomme();
            cases += StateMarkdown.of(lis(userId, projet, PLAN_ACTION)).casesOuvertes();
        }
        if (cases == 0) {
            return;
        }
        String carte = destinations.citedForProject(userId, projet.getId());
        constats.add(etat.statut() == StateMarkdown.Statut.CLOS
                ? IntegriteConstat.detteALaCloture(nom, cases, carte)
                : IntegriteConstat.detteEnCours(nom, cases, carte));
    }

    /**
     * Les notes personnelles non versionnées à la racine d'un dépôt client — <b>la règle qui protège
     * le plus</b> (F-93).
     *
     * <p>C'est le seul contrôle qui ne peut pas se faire avec les outils fichiers : savoir qu'un
     * {@code .md} n'est <b>pas suivi</b> demande à git. La commande est constante, le dossier voyage
     * dans le {@code cwd}, et toute autre issue — pas un dépôt, {@code bash} non autorisé, délai
     * dépassé — est <b>silencieuse</b> : un contrôle qui crierait parce que le runner refuse
     * {@code bash} ne serait plus lu.</p>
     */
    private void notesNonVersionnees(UUID userId, Workspace depot, String nom, Budget budget,
            List<IntegriteConstat> constats) {
        if (budget.epuise()) {
            return;
        }
        budget.consomme();
        String callId = UUID.randomUUID().toString();
        RunnerTarget target = RunnerTargets.of(depot);
        RunnerCallResult resultat;
        try {
            resultat = gateway.bash(target, callId, COMMANDE_STATUT, null, DELAI_STATUT_MS, null);
        } catch (RuntimeException ex) {
            log.debug("Statut du dépôt non lu ({})", ex.getClass().getSimpleName());
            return;
        }
        auditService.recordCall(userId, target, callId, OUTIL_STATUT, COMMANDE_STATUT, resultat);
        if (!resultat.ok()) {
            return;
        }
        List<String> notes = notesDe(resultat.content());
        if (!notes.isEmpty()) {
            constats.add(IntegriteConstat.noteHorsDepot(nom, notes));
        }
    }

    /**
     * Les {@code .md} non suivis <b>à la racine</b> d'un dépôt, tirés de {@code git status
     * --porcelain}.
     *
     * <p>Un fichier non suivi au fond de l'arborescence n'est pas visé : la règle porte sur la
     * <b>racine</b> du dépôt, là où une note personnelle atterrit et d'où elle part dans une
     * archive.</p>
     */
    static List<String> notesDe(String sortie) {
        if (sortie == null || sortie.isBlank()) {
            return List.of();
        }
        List<String> notes = new ArrayList<>();
        for (String brute : sortie.split("\n")) {
            String ligne = brute.strip();
            if (!ligne.startsWith("??") || notes.size() >= MAX_NOTES_CITEES) {
                continue;
            }
            String chemin = ligne.substring(2).strip();
            if (chemin.startsWith("\"") && chemin.endsWith("\"") && chemin.length() > 1) {
                // git cite les chemins « inhabituels » — espaces, accents selon core.quotepath.
                chemin = chemin.substring(1, chemin.length() - 1);
            }
            if (chemin.contains("/") || chemin.contains("\\")
                    || !chemin.toLowerCase(Locale.ROOT).endsWith(".md")) {
                continue;
            }
            notes.add(chemin);
        }
        return List.copyOf(notes);
    }

    /**
     * Les liens morts de la carte — <b>la façon dont une carte pourrit sans qu'on s'en aperçoive</b>.
     *
     * <p>Chaque référence est vérifiée à la racine du poste. Seul {@code not_found} conclut :
     * {@code is_directory} veut dire que la cible <b>existe</b> et qu'elle est un dossier, et un
     * refus de droits ne dit rien du tout.</p>
     */
    private void passeLiensMorts(UUID userId, GovernanceHostRef host, Map<String, String> carte,
            Set<String> fichiersDeLaCarte, Budget budget, List<IntegriteConstat> constats) {
        Set<String> vues = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, String> fichier : carte.entrySet()) {
            for (String reference : MapReferences.of(fichier.getValue(), fichiersDeLaCarte)) {
                if (budget.epuise()) {
                    return;
                }
                if (!vues.add(reference)) {
                    continue; // Deux fichiers citent le même chemin : une seule vérification.
                }
                budget.consomme();
                if (hostFiles.presence(userId, host, reference) == Presence.ABSENT) {
                    constats.add(IntegriteConstat.lienMort(fichier.getKey(), reference));
                }
            }
        }
    }

    /** Les projets du poste, ou aucun : lister ne fait jamais échouer un tour. */
    private List<Workspace> projets(UUID userId, GovernanceHostRef host) {
        try {
            return hostScope.projectsOf(userId, host);
        } catch (RuntimeException ex) {
            log.debug("Projets du poste non listés ({})", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private Optional<Set<String>> listePaths(UUID userId, Workspace projet) {
        try {
            return projectFiles.listPaths(userId, projet);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private String lis(UUID userId, Workspace projet, String chemin) {
        try {
            return projectFiles.read(userId, projet, chemin).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Vrai si l'arborescence porte un {@code .git/} : c'est un dépôt, pas un dossier de travail. */
    static boolean estUnDepotGit(Set<String> chemins) {
        return chemins.stream().anyMatch(chemin -> {
            String rel = chemin == null ? "" : chemin.strip().replace('\\', '/');
            return rel.equals(".git") || rel.startsWith(".git/");
        });
    }

    /** Vrai si ce projet est rangé sous {@code repos/} — la place d'un dépôt client (F-93). */
    static boolean sousRepos(Workspace projet) {
        String chemin = projet.getProjectPath() == null ? "" : projet.getProjectPath().strip();
        return chemin.toLowerCase(Locale.ROOT).replace('\\', '/').startsWith(REPOS);
    }

    /** Vrai si ce fichier de carte est <b>l'index</b> — le seul soumis au seuil de faits. */
    static boolean estIndex(String chemin) {
        return chemin != null && chemin.strip().equalsIgnoreCase(INDEX);
    }

    /**
     * Le nom cité dans un constat : le dossier sous la racine, à défaut le nom du projet.
     *
     * <p>C'est ce qui rend un constat <b>vérifiable</b> : « migration-dns » se retrouve à la racine,
     * « le projet » ne dit pas lequel quand le poste en porte trois.</p>
     */
    static String cite(Workspace projet) {
        String chemin = projet.getProjectPath() == null ? "" : projet.getProjectPath().strip();
        if (!chemin.isEmpty() && !"/".equals(chemin)) {
            return chemin;
        }
        return projet.getName() == null ? "" : projet.getName().strip();
    }

    /** Le budget d'appels d'une inspection : elle tourne dans le tour de quelqu'un. */
    private static final class Budget {

        private int restant = MAX_APPELS;

        boolean epuise() {
            return restant <= 0;
        }

        void consomme() {
            restant--;
        }
    }
}
