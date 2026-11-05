package fr.claudegateway.jarvis.state.releve;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.AtelierMcpTurnLauncher;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.live.HostTurnActivity;
import fr.claudegateway.jarvis.state.JarvisStateProperties;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateSettings;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>La mise en place du relevé maximal</b> (F-247 / SF-247-08, cadrage §4.3) : un tour d'agent <b>unique</b> par
 * client, lancé à la demande du PO, poste allumé et inactif, dans le <b>sujet dédié</b> « Jarvis — relevé » du poste
 * (SF-247-18 : jamais dans un sujet de travail du PO — incident du 2026-11-05). L'agent fait
 * l'inventaire des moyens de lecture du poste (CLIs installés et authentifiés, contextes), sans lire aucun secret, et
 * propose <b>une sonde par famille disponible</b> par {@code veille_poser(usage: etat)}, testée une fois chacune. Rien ne
 * tourne avant la validation du PO (« Tout valider », F-230 D5).
 */
@Service
public class JarvisReleveSetupService {

    /** La consigne fixe du tour d'inventaire (versionnée). */
    public static final String VERSION = "inventaire-v1";
    public static final String PROMPT = """
            Mise en place du relevé d'état de Jarvis pour ce client (une fois). But : que Jarvis sache, poste \
            éteint, l'état de ce qui compte ici (tickets, MR/PR, pipelines, déploiements, ressources cloud, \
            sorties d'infra, version de schéma, état git des dépôts).

            1. Fais l'inventaire des moyens de LECTURE présents sur ce poste : CLIs installés ET authentifiés \
            (jira/acli, glab, gh, kubectl/oc, helm, argocd, aws, az, terraform, psql…), contextes et dépôts. Ne lis \
            AUCUN secret : ni jeton, ni fichier de configuration d'identifiants ; un « auth status » suffit.
            2. Pour CHAQUE famille disponible (au plus une sonde par famille), écris une sonde en lecture seule dans \
            .atelier/releve/ qui imprime UNIQUEMENT une liste JSON [{"id","titre","statut","assigne","mis_a_jour",\
            "dernier_commentaire"}] (identifiant technique stable dans "id" : clé de ticket, groupe/projet!iid, \
            contexte/ns/deploiement…), 20 s au plus, puis appelle veille_poser avec usage "etat" (sans heures : \
            toutes les heures de 8 h à 20 h).
            3. Seuls les verbes de lecture sont admis (get, list, view, describe, status, show, log, output, \
            history ; aws describe-/get-/list-) : aucune écriture, aucun plan, aucun apply.
            4. Lance chaque sonde une fois et termine par une liste courte : famille, ce que voit la sonde, combien \
            d'éléments. Le PO validera tout d'un geste dans « Ce que je sais ».
            """;
    public static final String BANNER = "_Jarvis — mise en place du relevé d'état (inventaire, une fois)._\n";
    static final String BUSY = "Le poste travaille (un tour est en cours ou attend sur l'un de ses sujets) : relancez la "
            + "mise en place quand il est inactif.";

    /** L'issue d'une demande de mise en place. */
    public record Setup(boolean started, String code, String message, UUID workspaceId, UUID turnId) {

        static Setup refused(String code, String message) {
            return new Setup(false, code, message, null, null);
        }
    }

    private final JarvisStateProperties properties;
    private final JarvisStateRegistry registry;
    private final RunnerHostService hosts;
    private final RunnerLiveness liveness;
    private final WorkspaceService workspaces;
    private final HostTurnActivity activity;
    private final AtelierMcpTurnLauncher launcher;
    private final Clock clock;

    public JarvisReleveSetupService(JarvisStateProperties properties, JarvisStateRegistry registry,
            RunnerHostService hosts, RunnerLiveness liveness,
            WorkspaceService workspaces, HostTurnActivity activity, AtelierMcpTurnLauncher launcher, Clock clock) {
        this.properties = properties;
        this.registry = registry;
        this.hosts = hosts;
        this.liveness = liveness;
        this.workspaces = workspaces;
        this.activity = activity;
        this.launcher = launcher;
        this.clock = clock;
    }

    /** Lance le tour d'inventaire d'un client du compte (le client est vérifié par l'appelant). */
    public Setup start(UUID userId, UUID hostId) {
        if (!properties.releve()) {
            return Setup.refused("releve_disabled", "Le relevé est coupé sur cette installation.");
        }
        JarvisStateSettings s = registry.settingsOf(userId, hostId);
        if (!s.isEnabled() || !s.isReleveEnabled()) {
            return Setup.refused("releve_disabled", "Le relevé est coupé pour ce client.");
        }
        if (!liveness.isAlive(userId, hostId)) {
            return Setup.refused("host_offline", "Le poste est éteint : la mise en place se fait poste allumé.");
        }
        if (!hosts.declaredCapabilities(hostId).contains(JarvisReleveService.LOW_PRIORITY)) {
            return Setup.refused("runner_update", "Mettez d'abord le runner de ce poste à jour : il doit savoir "
                    + "céder la place à votre travail.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        // SF-247-18 : tout le poste (tous sujets), ce pod ET les pairs, demandes sans réponse en base comprises.
        if (activity.hostBusy(userId, hostId, Duration.ofMinutes(JarvisReleveService.QUIET_MINUTES), true, now)) {
            return Setup.refused("host_busy", BUSY);
        }
        Workspace subject;
        try {
            subject = workspaces.openJarvisReleve(userId, hostId);
        } catch (DataIntegrityViolationException e) {
            return Setup.refused("host_busy", BUSY); // deux mises en place simultanées : la seconde s'efface
        }
        // Jamais une précision : un tour vivant dans le sujet dédié ⇒ refus, rien n'est déposé.
        Optional<UUID> turnId = launcher.launchIfIdle(userId, subject.getId(), PROMPT, BANNER);
        if (turnId.isEmpty()) {
            return Setup.refused("host_busy", BUSY);
        }
        s.setReleveSetupAt(now);
        registry.save(s);
        return new Setup(true, null, null, subject.getId(), turnId.get());
    }
}
