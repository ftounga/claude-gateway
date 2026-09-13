package fr.claudegateway.radar.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.claudegateway.mail.ClientEmail;
import fr.claudegateway.mail.ClientMailOutbox;
import fr.claudegateway.mail.ClientMailRenderer;
import fr.claudegateway.mail.HostMailAddressService;
import fr.claudegateway.mail.ResolvedRecipient;
import fr.claudegateway.radar.RadarBriefService;
import fr.claudegateway.radar.RadarReadService;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSyncStatus;
import fr.claudegateway.radar.RadarSyncTrigger;
import fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus;
import fr.claudegateway.radar.dto.RadarBoardViews.BriefCounts;
import fr.claudegateway.radar.dto.RadarBoardViews.BriefSentence;
import fr.claudegateway.radar.dto.RadarBoardViews.BriefView;
import fr.claudegateway.radar.dto.RadarViews.SyncView;
import fr.claudegateway.runner.host.ClientSpace;
import fr.claudegateway.runner.host.HostSpaceService;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * <b>Le résumé du matin par courriel</b> (F-110 / SF-110-04) : après chaque synchro du soir analysée, un courriel
 * sobre vers l'adresse de réception du client — phrases du résumé, compteurs, relances dues, lien vers la Vigie.
 *
 * <h2>Quand</h2>
 *
 * <p>Pour chaque poste dont l'option est active, avec le droit Vigie et le client activé dans la Vigie (les mêmes
 * gardes que le planificateur) : la dernière synchro <b>du soir</b> ({@code SCHEDULED} ou {@code CATCH_UP}),
 * terminée {@code SUCCEEDED} ou {@code PARTIAL}, dont l'analyse est posée — ou terminée depuis plus de
 * {@link #ANALYSIS_WAIT}. Au-delà de {@link #STALE}, elle est notée traitée sans envoi.</p>
 *
 * <h2>Une seule fois</h2>
 *
 * <p>Le marqueur {@code morning_email_sync_id} est pris par une mise à jour conditionnelle, et le courriel mis en
 * file <b>dans la même transaction</b> : deux pods n'en envoient pas deux, et un échec de mise en file ne consomme
 * pas le marqueur.</p>
 */
@Service
public class RadarMorningMail {

    private static final Logger log = LoggerFactory.getLogger(RadarMorningMail.class);

    /** Au-delà, l'analyse n'est plus attendue : le résumé part avec ce qui est posé. */
    static final Duration ANALYSIS_WAIT = Duration.ofHours(2);
    /** Au-delà, la synchro est trop ancienne pour un résumé « du matin ». */
    static final Duration STALE = Duration.ofHours(18);
    /** Relances dues listées au plus. */
    static final int MAX_FOLLOW_UPS = 10;
    static final int PAGE = 200;

    private static final Set<RadarSyncStatus> FINISHED_OK = EnumSet.of(RadarSyncStatus.SUCCEEDED, RadarSyncStatus.PARTIAL);
    private static final Set<RadarAnalysisBatchStatus> ANALYSIS_OPEN =
            EnumSet.of(RadarAnalysisBatchStatus.PENDING, RadarAnalysisBatchStatus.PROCESSING);

    private final RadarHostSettingsRepository settings;
    private final RadarReadService readService;
    private final RadarBriefService briefService;
    private final TeamsAccessService teamsAccess;
    private final HostSpaceService hostSpaces;
    private final HostMailAddressService addresses;
    private final ClientMailOutbox outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String frontendUrl;

    public RadarMorningMail(RadarHostSettingsRepository settings, RadarReadService readService,
            RadarBriefService briefService, TeamsAccessService teamsAccess, HostSpaceService hostSpaces,
            HostMailAddressService addresses, ClientMailOutbox outbox, PlatformTransactionManager transactionManager,
            Clock clock, @Value("${app.frontend-url:http://localhost:4200}") String frontendUrl) {
        this.settings = settings;
        this.readService = readService;
        this.briefService = briefService;
        this.teamsAccess = teamsAccess;
        this.hostSpaces = hostSpaces;
        this.addresses = addresses;
        this.outbox = outbox;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.frontendUrl = frontendUrl == null ? "" : frontendUrl.replaceAll("/+$", "");
    }

    /**
     * Un passage.
     *
     * @return le nombre de résumés mis en file
     */
    public int runOnce() {
        int queued = 0;
        for (int page = 0;; page++) {
            List<RadarHostSettings> rows = settings.findByMorningEmailTrueOrderByIdAsc(PageRequest.of(page, PAGE));
            for (RadarHostSettings row : rows) {
                try {
                    if (handle(row)) {
                        queued++;
                    }
                } catch (RuntimeException e) {
                    log.warn("Résumé du matin par courriel en échec (poste={}) : {}", row.getHostId(),
                            e.getClass().getSimpleName());
                }
            }
            if (rows.size() < PAGE) {
                return queued;
            }
        }
    }

    private boolean handle(RadarHostSettings row) {
        RadarScope scope = new RadarScope(row.getUserId(), row.getHostId());
        if (!teamsAccess.hasAccess(row.getUserId())
                || !hostSpaces.isActiveForOwner(row.getUserId(), row.getHostId(), ClientSpace.VIGIE)) {
            return false; // plus de droit, ou client retiré de la Vigie : réexaminé au passage suivant
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<SyncView> evening = readService.syncs(scope).stream()
                .filter(sync -> sync.trigger() == RadarSyncTrigger.SCHEDULED || sync.trigger() == RadarSyncTrigger.CATCH_UP)
                .filter(sync -> sync.status() != RadarSyncStatus.RUNNING)
                .findFirst();
        if (evening.isEmpty() || evening.get().id().equals(row.getMorningEmailSyncId())) {
            return false;
        }
        SyncView sync = evening.get();
        if (!FINISHED_OK.contains(sync.status()) || sync.finishedAt() == null) {
            return false; // synchro du soir en échec ou annulée : pas de résumé
        }
        if (sync.finishedAt().plus(STALE).isBefore(now)) {
            // Trop ancienne pour un résumé « du matin » : notée traitée, sans envoi.
            transactions.executeWithoutResult(
                    status -> settings.markMorningEmail(scope.userId(), scope.hostId(), sync.id()));
            return false;
        }
        if (analysisOpen(sync) && sync.finishedAt().plus(ANALYSIS_WAIT).isAfter(now)) {
            return false; // l'analyse tourne encore : le résumé attend
        }
        Boolean queued = transactions.execute(status -> {
            if (settings.markMorningEmail(scope.userId(), scope.hostId(), sync.id()) == 0) {
                return false; // un autre passage l'a pris, ou l'option vient d'être retirée
            }
            ResolvedRecipient recipient = addresses.resolveRecipient(scope.userId(), scope.hostId());
            if (recipient.address() == null) {
                return false;
            }
            BriefView brief = briefService.brief(scope);
            List<String> followUps = briefService.dueFollowUps(scope, MAX_FOLLOW_UPS);
            String subject = "Résumé du matin — " + recipient.clientName();
            String body = compose(brief, followUps, recipient, frontendUrl + "/vigie/" + scope.hostId());
            outbox.enqueue(new ClientMailOutbox.Draft(scope.userId(), scope.hostId(), null,
                    ClientEmail.Kind.MORNING_SUMMARY, recipient, truncate(subject, 200),
                    ClientMailRenderer.render(body, recipient.clientName())));
            return true;
        });
        return Boolean.TRUE.equals(queued);
    }

    private static boolean analysisOpen(SyncView sync) {
        Map<RadarAnalysisBatchStatus, Long> batches = sync.analysis() == null ? null : sync.analysis().batches();
        if (batches == null) {
            return false;
        }
        return ANALYSIS_OPEN.stream().anyMatch(status -> batches.getOrDefault(status, 0L) > 0);
    }

    /** Le corps Markdown du résumé. Package-privé pour les tests. */
    static String compose(BriefView brief, List<String> followUps, ResolvedRecipient recipient, String vigieUrl) {
        StringBuilder md = new StringBuilder();
        md.append("# Résumé du matin — ").append(escape(recipient.clientName())).append("\n\n");
        if (brief.coverageWarning() != null && !brief.coverageWarning().isBlank()) {
            md.append("> ").append(escape(brief.coverageWarning())).append("\n\n");
        }
        md.append("## Ce qui a bougé\n\n");
        List<BriefSentence> sentences = brief.sentences() == null ? List.of() : brief.sentences();
        if (sentences.isEmpty()) {
            md.append("Rien de nouveau depuis hier.\n\n");
        } else {
            sentences.forEach(sentence -> md.append("- ").append(escape(sentence.text())).append('\n'));
            md.append('\n');
        }
        BriefCounts counts = brief.counts();
        if (counts != null) {
            md.append("## Les compteurs\n\n")
                    .append("| À traiter | À faire | Relances dues | Mises en relation | Sujets suivis | Bloqués |\n")
                    .append("|---|---|---|---|---|---|\n")
                    .append("| ").append(counts.toHandle()).append(" | ").append(counts.toDoByMe())
                    .append(" | ").append(counts.followUpsDue()).append(" | ").append(counts.introductions())
                    .append(" | ").append(counts.subjectsFollowed()).append(" | ").append(counts.blockedSubjects())
                    .append(" |\n\n");
        }
        md.append("## Les relances dues\n\n");
        if (followUps.isEmpty()) {
            md.append("Aucune relance due.\n\n");
        } else {
            followUps.forEach(line -> md.append("- ").append(escape(line)).append('\n'));
            md.append('\n');
        }
        if (!recipient.verifiedForClient()) {
            md.append("_Aucune adresse vérifiée pour ").append(escape(recipient.clientName()))
                    .append(" : ce résumé arrive à l'adresse de votre compte. Réglez l'adresse de réception dans "
                            + "l'en-tête du client._\n\n");
        }
        md.append("[Ouvrir la Vigie](").append(vigieUrl).append(")\n");
        return md.toString();
    }

    /** Les phrases viennent du registre (donc des échanges du client) : pas de Markdown actif dedans. */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("([\\\\`*_\\[\\]<>#|])", "\\\\$1").replaceAll("\\s+", " ").strip();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
