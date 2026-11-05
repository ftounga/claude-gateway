package fr.claudegateway.jarvis.state.releve;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.AtelierMcpTurnLauncher;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.veille.JarvisWatchService;
import fr.claudegateway.jarvis.state.JarvisStateProperties;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateSettings;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>La mise en place du relevé maximal</b> (F-247 / SF-247-08, cadrage §4.3) : un tour d'agent <b>unique</b> par
 * client, lancé à la demande du PO, poste allumé et inactif, dans le sujet le plus récent du client. L'agent fait
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
    private final JarvisReleveService releve;
    private final WorkspaceRepository workspaces;
    private final AtelierMcpTurnLauncher launcher;
    private final Clock clock;

    public JarvisReleveSetupService(JarvisStateProperties properties, JarvisStateRegistry registry,
            RunnerHostService hosts, RunnerLiveness liveness, JarvisReleveService releve,
            WorkspaceRepository workspaces, AtelierMcpTurnLauncher launcher, Clock clock) {
        this.properties = properties;
        this.registry = registry;
        this.hosts = hosts;
        this.liveness = liveness;
        this.releve = releve;
        this.workspaces = workspaces;
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
        if (!releve.quiet(userId, hostId, now)) {
            return Setup.refused("host_busy", "Le poste travaille : relancez la mise en place quand il est inactif.");
        }
        Optional<Workspace> subject = subjectOf(userId, hostId);
        if (subject.isEmpty()) {
            return Setup.refused("no_subject", "Ce client n'a encore aucun sujet sur son poste.");
        }
        AtelierMcpTurnLauncher.LaunchResult launched = launcher.launchWithBanner(userId, subject.get().getId(),
                PROMPT, BANNER);
        if (launched.steered() || launched.steerRejected()) {
            return Setup.refused("host_busy", "Un tour est déjà en cours dans ce sujet.");
        }
        s.setReleveSetupAt(now);
        registry.save(s);
        return new Setup(true, null, null, subject.get().getId(), launched.turnId());
    }

    /** Le sujet le plus récent du client qui peut porter une sonde (sujet sur poste, pas un terminal de poste). */
    Optional<Workspace> subjectOf(UUID userId, UUID hostId) {
        List<Workspace> all = workspaces.findByUserIdAndHostId(userId, hostId);
        return all.stream().filter(w -> userId.equals(w.getUserId()) && JarvisWatchService.isWatchCapable(w))
                .max(Comparator.comparing(w -> w.getUpdatedAt() != null ? w.getUpdatedAt() : w.getCreatedAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder())));
    }
}
