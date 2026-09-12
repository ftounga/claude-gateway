package fr.claudegateway.governance;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Lit et écrit les fichiers de la <b>racine d'un poste</b> — <b>la carte</b> (F-92 / SF-92-01).
 *
 * <p>C'est le pendant de {@link GovernanceProjectFiles}, d'un cran au-dessus : celui-ci travaille
 * dans un <b>projet</b>, celui-là à la <b>racine du poste</b>, là où la carte vit et où le terminal
 * de poste (F-74) s'ouvre déjà. La cible est un appel de <b>machine</b>, sans projet — exactement la
 * forme qu'emploie {@code RunnerHostFolderBrowser} depuis F-71 : {@code workspaceId} nul, chemin de
 * projet vide.</p>
 *
 * <p><b>Aucune convention de chemin n'est imposée.</b> La racine est celle que le runner a
 * déclarée — {@code dev} chez un poste, {@code infra} chez un autre —, et la gateway n'en connaît
 * que le dernier segment, pour l'affichage. La carte se désigne donc par des <b>noms de fichiers</b>
 * relatifs à cette racine, jamais par un chemin absolu.</p>
 *
 * <p><b>La présence se lit, elle ne se déduit pas.</b> {@link #presence} interroge le fichier
 * lui-même plutôt que l'arborescence. À la racine d'un poste réel, {@code list_files} est récursif et
 * <b>tronqué</b> (SF-38-21) : conclure « absent » d'une liste incomplète ferait écraser la carte d'un
 * client. Trois issues, et une seule autorise à écrire : présent, <b>absent</b> ({@code not_found}
 * exact), ou <b>inconnu</b> — et l'inconnu n'écrit jamais.</p>
 *
 * <p><b>Un poste sans machine n'a pas de racine.</b> Le poste virtuel « Hébergé » (F-71) rend
 * {@link Presence#UNSUPPORTED} sans qu'aucun appel ne parte : il n'y a rien à joindre.</p>
 *
 * <p><b>Isolation.</b> Ce service reçoit un {@link GovernanceHostRef} <b>déjà vérifié comme
 * possédé</b> par {@link GovernanceHostScope}, jamais un identifiant brut venu du client. Le
 * {@code userId} ne sert qu'à signer la ligne d'audit.</p>
 *
 * <p><b>Le contenu n'est jamais journalisé</b> : c'est le fichier de l'utilisateur, sur la machine
 * d'un client. Une trace de lecture le recopierait dans les journaux de la gateway.</p>
 */
@Service
public class GovernanceHostFiles {

    private static final Logger log = LoggerFactory.getLogger(GovernanceHostFiles.class);

    /** Nom d'outil du journal pour une lecture de carte : trois raisons de lire, trois lignes. */
    static final String TOOL_MAP_READ = "governance_map_read";

    /** Nom d'outil du journal pour une écriture de carte. */
    static final String TOOL_MAP_WRITE = "governance_map_write";

    /** Code rendu par le runner quand le fichier n'existe pas — la <b>seule</b> issue qui autorise à écrire. */
    static final String NOT_FOUND = "not_found";

    /** Ce qu'on sait d'un fichier de la racine. */
    public enum Presence {

        /** Le fichier est là. On n'y touche pas. */
        PRESENT,

        /** Le runner a dit {@code not_found} : le fichier n'existe pas, on peut le créer. */
        ABSENT,

        /** Machine éteinte, droits refusés, chemin occupé par un dossier… : <b>on n'écrit pas</b>. */
        UNKNOWN,

        /** Ce poste n'est pas une machine : il n'a pas de racine, donc pas de carte. */
        UNSUPPORTED
    }

    private final RunnerToolGateway gateway;
    private final RunnerAuditService auditService;

    public GovernanceHostFiles(RunnerToolGateway gateway, RunnerAuditService auditService) {
        this.gateway = gateway;
        this.auditService = auditService;
    }

    /** Vrai si ce poste a une racine où poser une carte — faux pour le poste « Hébergé » (F-71). */
    public boolean supports(GovernanceHostRef host) {
        return host != null && !host.hosted();
    }

    /**
     * Ce qu'on sait du fichier {@code path} à la racine du poste, <b>sans rien écrire</b>.
     *
     * <p>N'appelle la machine qu'une fois, et ne conclut « absent » que sur le code exact
     * {@code not_found}. Tout autre refus rend {@link Presence#UNKNOWN} : le doute ne fait jamais
     * écrire, parce qu'écrire signifierait <b>écraser</b> la carte d'un client.</p>
     */
    public Presence presence(UUID userId, GovernanceHostRef host, String path) {
        return read(userId, host, path).presence();
    }

    /**
     * Le contenu du fichier {@code path} à la racine du poste, et ce qu'on sait de son existence.
     *
     * <p>Une seule lecture sert les deux besoins : décider d'un dépôt (SF-92-01) et rendre la carte à
     * l'écran (SF-92-02). Les séparer ferait payer deux allers-retours pour la même réponse.</p>
     */
    public HostFileRead read(UUID userId, GovernanceHostRef host, String path) {
        if (!supports(host)) {
            return HostFileRead.unsupported();
        }
        String rel = GovernancePath.normalizeOrNull(path);
        if (rel == null) {
            // Un chemin qu'aucun runner n'accepterait n'a pas à traverser le réseau ; et il ne doit
            // surtout pas passer pour « absent », ce qui déclencherait une écriture.
            return new HostFileRead(Presence.UNKNOWN, null, false);
        }
        String callId = UUID.randomUUID().toString();
        RunnerTarget target = rootTarget(host);
        RunnerCallResult result = gateway.readFile(target, callId, rel);
        auditService.recordCall(userId, target, callId, TOOL_MAP_READ, rel, result);
        if (result.ok()) {
            return new HostFileRead(Presence.PRESENT, result.content() == null ? "" : result.content(),
                    result.truncated());
        }
        if (NOT_FOUND.equals(result.errorCode())) {
            return new HostFileRead(Presence.ABSENT, null, false);
        }
        log.debug("Carte du poste illisible (code={})", result.errorCode());
        return new HostFileRead(Presence.UNKNOWN, null, false);
    }

    /**
     * Écrit un fichier à la racine du poste.
     *
     * <p><b>N'écrase jamais rien de son propre chef</b> : la décision « ce fichier manque » est prise
     * par l'appelant à partir de {@link #presence}, et {@link Presence#ABSENT} est la seule qui
     * l'autorise. Ce service ne fait qu'exécuter.</p>
     *
     * @return vrai si l'écriture a abouti
     */
    public boolean write(UUID userId, GovernanceHostRef host, String path, String content) {
        if (!supports(host)) {
            return false;
        }
        String rel = GovernancePath.normalizeOrNull(path);
        if (rel == null) {
            return false;
        }
        String callId = UUID.randomUUID().toString();
        RunnerTarget target = rootTarget(host);
        RunnerCallResult result = gateway.writeFile(target, callId, rel, content);
        auditService.recordCall(userId, target, callId, TOOL_MAP_WRITE, rel, result);
        return result.ok();
    }

    /**
     * Cible d'un appel de <b>poste</b> : la machine, aucun projet, la racine.
     *
     * <p>{@code workspaceId} est nul <b>à dessein</b> : aucun projet n'est concerné, et l'appel n'a
     * donc pas à être annulé par l'interruption d'un projet voisin (même forme que
     * {@code RunnerHostFolderBrowser} depuis F-71).</p>
     */
    private static RunnerTarget rootTarget(GovernanceHostRef host) {
        return new RunnerTarget(host.hostId(), null, "");
    }

    /**
     * Ce qu'une lecture de la racine a donné.
     *
     * @param presence  ce qu'on sait de l'existence du fichier
     * @param content   son contenu si {@link Presence#PRESENT}, {@code null} sinon
     * @param truncated vrai si le producteur a coupé le contenu — une coupe se <b>dit</b>
     */
    public record HostFileRead(Presence presence, String content, boolean truncated) {

        static HostFileRead unsupported() {
            return new HostFileRead(Presence.UNSUPPORTED, null, false);
        }

        /** Le contenu, ou une chaîne vide : confort de lecture pour l'appelant qui rend un écran. */
        public String contentOrEmpty() {
            return content == null ? "" : content;
        }
    }
}
