package fr.claudegateway.runner.host;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.RunnerShell;
import fr.claudegateway.billing.seat.SeatLedgerService;
import fr.claudegateway.runner.RunnerPairingCodeRepository;
import fr.claudegateway.runner.RunnerTokenRepository;

/**
 * Cycle de vie des postes (F-48 / SF-48-01) : création, listing, suppression, et enregistrement de
 * ce que le runner <b>déclare</b> de sa machine.
 *
 * <p>Toute lecture et toute écriture filtrent {@code user_id} : un poste appartient à un seul
 * compte, et un identifiant de poste venu du client ne suffit jamais à y toucher.</p>
 */
@Service
public class RunnerHostService implements RunnerShellRecorder, RunnerVersionRecorder {

    private static final int MAX_OS_LENGTH = 64;

    private final RunnerHostRepository repository;
    private final SeatLedgerService seatLedgerService;
    private final RunnerTokenRepository tokenRepository;
    private final RunnerPairingCodeRepository pairingCodeRepository;
    private final ApplicationEventPublisher events;

    public RunnerHostService(RunnerHostRepository repository, SeatLedgerService seatLedgerService,
            RunnerTokenRepository tokenRepository,
            RunnerPairingCodeRepository pairingCodeRepository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.seatLedgerService = seatLedgerService;
        this.tokenRepository = tokenRepository;
        this.pairingCodeRepository = pairingCodeRepository;
        this.events = events;
    }

    /**
     * Crée un poste au nom libre. Le nom est requis : c'est ce qui le rend reconnaissable.
     *
     * <p>Le poste <b>annonce</b> sa naissance (F-75 / SF-75-01) : la gouvernance y embarque ce qui
     * est marqué « appliqué par défaut ». L'annonce est un événement et non un appel, pour que ce
     * service n'ait jamais à connaître le module gouvernance — qui, lui, le connaît déjà.</p>
     */
    @Transactional
    public RunnerHost create(UUID userId, String name) {
        RunnerHost host = repository.save(RunnerHost.builder()
                .userId(userId)
                .name(requireName(name))
                .build());
        events.publishEvent(RunnerHostLifecycleEvent.created(userId, host.getId()));
        return host;
    }

    /** Postes de l'utilisateur, les plus récents d'abord. */
    @Transactional(readOnly = true)
    public List<RunnerHost> list(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Poste possédé par l'utilisateur.
     *
     * @throws RunnerHostNotFoundException s'il est inconnu ou appartient à quelqu'un d'autre — les
     *                                     deux cas sont indiscernables (pas d'oracle d'existence)
     */
    @Transactional(readOnly = true)
    public RunnerHost requireOwned(UUID userId, UUID hostId) {
        return repository.findByIdAndUserId(hostId, userId)
                .orElseThrow(() -> new RunnerHostNotFoundException("Poste introuvable : " + hostId));
    }

    /** Renomme un poste possédé. */
    @Transactional
    public RunnerHost rename(UUID userId, UUID hostId, String name) {
        RunnerHost host = requireOwned(userId, hostId);
        host.setName(requireName(name));
        return host;
    }

    /**
     * Déclare l'<b>état de mission</b> d'un poste possédé (F-60 / SF-60-01) : où en est le travail
     * chez ce client — en cours, en attente, clôturé.
     *
     * <p><b>Cette méthode n'écrit qu'une colonne.</b> Elle ne révoque aucun jeton, ne coupe aucune
     * liaison, ne détache aucun projet, ne ramène aucune cible d'exécution à {@code SANDBOX} et
     * n'efface aucune ligne de journal. Clôturer une mission n'est <b>pas</b> un coupe-circuit :
     * couper une machine reste {@code POST /runner-hosts/{id}/kill}, et lui seul. Un poste clôturé
     * se range, il ne s'éteint pas.</p>
     *
     * <p>Idempotent : réappliquer le même état ne lève pas et ne change rien.</p>
     *
     * <p><b>Ce que la clôture fait désormais, et qui n'est pas une coupure</b> (F-65 / SF-65-01) :
     * elle note que le poste a été facturable pendant le mois en cours. C'est ce qui rend la clôture
     * honnête dans les deux sens — le mois engagé reste dû jusqu'au bout, et rouvrir le poste avant
     * la fin du même mois ne le refacture pas.</p>
     *
     * @throws RunnerHostNotFoundException si le poste est inconnu ou appartient à quelqu'un d'autre
     */
    @Transactional
    public RunnerHost setMissionStatus(UUID userId, UUID hostId, HostMissionStatus status) {
        RunnerHost host = requireOwned(userId, hostId);
        HostMissionStatus next = status == null ? HostMissionStatus.defaultStatus() : status;
        HostMissionStatus previous = host.getMissionStatus();
        host.setMissionStatus(next);
        recordSeatTransition(userId, host, previous, next);
        return host;
    }

    /**
     * Tient le registre des mois-postes à jour au passage d'une frontière de facturabilité
     * (F-65 / SF-65-01) : la clôture d'un poste facturable, et la réouverture d'un poste clôturé.
     * Les autres transitions — {@code ACTIVE} ↔ {@code PENDING} — ne franchissent aucune frontière
     * et n'écrivent rien : une mission en attente reste une mission ouverte.
     */
    private void recordSeatTransition(
            UUID userId, RunnerHost host, HostMissionStatus previous, HostMissionStatus next) {
        boolean wasBillable = previous != HostMissionStatus.CLOSED;
        boolean isBillable = next != HostMissionStatus.CLOSED;
        if (wasBillable && !isBillable) {
            seatLedgerService.noteClosure(userId, host.getId(), host.getCreatedAt());
        } else if (!wasBillable && isBillable) {
            seatLedgerService.noteReopening(userId, host.getId());
        }
    }

    /**
     * Supprime un poste possédé <b>avec ses identifiants</b> — jetons runner et codes d'appairage —
     * en <b>une seule transaction</b>.
     *
     * <p>Les trois effacements tenaient auparavant dans le contrôleur, hors transaction : la purge
     * des jetons y levait {@code TransactionRequiredException}, et la suppression d'un poste
     * répondait 500. Le défaut n'avait jamais été vu parce qu'aucun écran n'appelait ce chemin —
     * c'est précisément ce que F-69 vient corriger en l'exposant. Les rassembler ici, c'est aussi
     * garantir qu'on ne peut plus supprimer un poste en laissant vivre un jeton qui l'authentifie.</p>
     *
     * <p>Ses <b>mois-postes</b> (F-65) partent avec lui : supprimer un poste n'est pas le clôturer.
     * La clôture range et laisse le mois engagé ; la suppression détruit la machine, son appairage
     * et ses jetons — la garder en facturation montrerait une ligne sans nom.</p>
     *
     * <p>Ce service ne connaît pas les <b>projets</b>, et n'a pas à les connaître : depuis F-69 un
     * poste ne se supprime qu'à zéro projet, et la garde vit là où l'orchestration vit — dans le
     * contrôleur.</p>
     */
    @Transactional
    public void deleteWithCredentials(UUID userId, UUID hostId) {
        RunnerHost host = requireOwned(userId, hostId);
        tokenRepository.deleteByHostId(hostId);
        pairingCodeRepository.deleteByHostId(hostId);
        seatLedgerService.forgetHost(host.getId());
        repository.delete(host);
        // La gouvernance activée sur ce poste ne lui survit pas (F-75 / SF-75-01). L'annonce est
        // faite DANS la transaction : une activation orpheline serait une gouvernance qui s'applique
        // à une machine qui n'existe plus.
        events.publishEvent(RunnerHostLifecycleEvent.deleted(userId, hostId));
    }

    /**
     * Enregistre ce que le runner déclare de sa machine à l'appairage (F-38 / SF-38-15 et 18,
     * déplacé sur le poste par F-48).
     *
     * <p>La <b>racine</b> est réduite à son dernier segment : l'arborescence de la machine de
     * l'utilisateur n'a aucune raison d'entrer dans la base.</p>
     */
    @Transactional
    public void recordDeclaration(UUID hostId, String rootName, String os, boolean elevated) {
        repository.findById(hostId).ifPresent(host -> {
            String segment = lastSegment(rootName);
            if (segment != null) {
                host.setRootName(segment);
            }
            String system = shorten(os, MAX_OS_LENGTH);
            if (system != null) {
                host.setOs(system);
            }
            host.setElevated(elevated);
            host.setLastSeenAt(OffsetDateTime.now());
        });
    }

    /**
     * Enregistre le genre d'interpréteur élu par le runner (F-38 / SF-38-27), déplacé du projet vers
     * le poste : c'est une propriété de la machine, et elle vaut pour tous ses projets.
     *
     * <p>Hors liste blanche, la valeur est <b>ignorée</b> plutôt que relayée : elle vient d'un
     * client, et la consigne système garde alors son texte POSIX.</p>
     */
    @Transactional
    @Override
    public void recordRunnerShell(UUID hostId, String declared) {
        RunnerShell.fromDeclared(declared).ifPresent(shell ->
                repository.findById(hostId).ifPresent(host -> host.setShell(shell.declared())));
    }

    /**
     * Retient la <b>version</b> du binaire que le runner déclare (F-81 / SF-81-03).
     *
     * <p>Elle n'autorise et n'interdit rien : elle est écrite pour que la vue d'ensemble du poste
     * puisse répondre à « son runner est-il à jour ? » au lieu de laisser deviner. Une valeur vide ou
     * trop longue est <b>ignorée</b> plutôt que tronquée — elle vient d'un client, et une version
     * coupée en deux serait pire qu'une version absente.</p>
     */
    @Transactional
    @Override
    public void recordRunnerVersion(UUID hostId, String declared) {
        if (hostId == null || declared == null) {
            return;
        }
        String version = declared.trim();
        if (version.isEmpty() || version.length() > RunnerHost.MAX_RUNNER_VERSION_LENGTH) {
            return;
        }
        repository.findById(hostId).ifPresent(host -> host.setRunnerVersion(version));
    }

    /**
     * Genre d'interpréteur déclaré par le runner de ce poste, ou {@code null} — poste inconnu, non
     * rattaché, ou runner qui n'a rien déclaré. La consigne système retombe alors sur son texte
     * POSIX, correct sur toute machine Unix.
     */
    @Transactional(readOnly = true)
    public String declaredShell(UUID hostId) {
        if (hostId == null) {
            return null;
        }
        return repository.findById(hostId).map(RunnerHost::getShell).orElse(null);
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidHostNameException("Le nom du poste est requis.");
        }
        return trimmed.length() > RunnerHost.MAX_NAME_LENGTH
                ? trimmed.substring(0, RunnerHost.MAX_NAME_LENGTH)
                : trimmed;
    }

    /** Dernier segment d'un chemin, quel que soit le séparateur ; {@code null} si rien d'exploitable. */
    static String lastSegment(String rawRoot) {
        if (rawRoot == null) {
            return null;
        }
        String cleaned = rawRoot.trim().replace('\\', '/');
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        int slash = cleaned.lastIndexOf('/');
        String segment = slash >= 0 ? cleaned.substring(slash + 1) : cleaned;
        return shorten(segment, 255);
    }

    private static String shorten(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
