package fr.claudegateway.jarvis.clientmail;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import fr.claudegateway.jarvis.mail.DepositedMailLabels;
import fr.claudegateway.jarvis.mail.JarvisMailItem;
import fr.claudegateway.jarvis.mail.JarvisMailPurge;
import fr.claudegateway.jarvis.notices.JarvisNotice;
import fr.claudegateway.jarvis.notices.JarvisNoticeService;
import fr.claudegateway.jarvis.notices.NoticeTexts;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;
import fr.claudegateway.runner.host.RunnerHostLifecycleEvent;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * <b>Rattacher et annoncer</b> (F-249 / SF-249-03, D9, D10) — après le tri d'un mail déposé : les sujets trouvés par
 * {@link ClientMailSubjectLinker} sont écrits, puis chaque sujet reçoit (ou cumule) son annonce d'interface « 2 mails
 * sur « Alarme Fortec » (EDENRED) » et une notification F-185 neutre. <b>Jamais</b> de message ni de tour dans le fil
 * du sujet. Un courrier « à ignorer » n'est ni rattaché ni annoncé.
 */
@Component
public class ClientMailAnnouncer implements DepositedMailLabels, JarvisMailPurge {

    private static final Logger log = LoggerFactory.getLogger(ClientMailAnnouncer.class);
    static final int NOTICE_DAYS = 3;

    private final ClientMailSubjectLinker linker;
    private final ClientMailSubjectLinkRepository links;
    private final JarvisNoticeService notices;
    private final RunnerHostRepository hosts;
    private final Clock clock;

    private PushNotificationService push;

    public ClientMailAnnouncer(ClientMailSubjectLinker linker, ClientMailSubjectLinkRepository links,
            JarvisNoticeService notices, RunnerHostRepository hosts, Clock clock) {
        this.linker = linker;
        this.links = links;
        this.notices = notices;
        this.hosts = hosts;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setPush(PushNotificationService push) {
        this.push = push;
    }

    /** Rattache le courrier et annonce ; rend les sujets rattachés. */
    public List<ClientMailSubjectLinker.Found> linkAndAnnounce(JarvisMailItem item) {
        if (item == null || !item.isDeposited() || item.getTriage() == JarvisMailItem.Triage.A_IGNORER) {
            return List.of();
        }
        List<ClientMailSubjectLinker.Found> found = linker.find(item);
        if (found.isEmpty()) {
            return found;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        String client = hosts.findByIdAndUserId(item.getHostId(), item.getUserId()).map(h -> h.getName()).orElse("");
        List<ClientMailSubjectLinker.Found> written = new ArrayList<>();
        for (ClientMailSubjectLinker.Found f : found) {
            ClientMailSubjectLink link = new ClientMailSubjectLink();
            link.setUserId(item.getUserId());
            link.setItemId(item.getId());
            link.setWorkspaceId(f.workspaceId());
            link.setHostId(item.getHostId());
            link.setReason(f.reason());
            link.setCreatedAt(now);
            try {
                links.saveAndFlush(link);
                written.add(f);
            } catch (DataIntegrityViolationException e) {
                // déjà rattaché (travail rejoué) : ni second lien, ni seconde annonce
            }
        }
        for (ClientMailSubjectLinker.Found f : written) {
            try {
                JarvisNotice n = notices.upsert(item.getUserId(), JarvisNotice.MAILS_ON_SUBJECT,
                        "mails:" + f.workspaceId(), item.getHostId(), f.workspaceId(), 1, true,
                        count -> NoticeTexts.mailsOnSubject(count, f.name(), client), "/atelier/" + f.workspaceId(),
                        now.plusDays(NOTICE_DAYS));
                if (n != null && push != null) {
                    push.notify(item.getUserId(), f.workspaceId(), PushEvent.CLIENT_MAILS_ON_SUBJECT,
                            "/atelier/" + f.workspaceId());
                }
            } catch (RuntimeException e) {
                log.warn("Dépôts : annonce non posée ({})", e.getClass().getSimpleName());
            }
        }
        return written;
    }

    /** Les noms de sujets rattachés à ces courriers (pour le libellé « Outlook EDENRED · « Alarme Fortec » »). */
    @Override
    public Map<UUID, List<String>> subjectsOf(UUID userId, Collection<UUID> itemIds) {
        if (userId == null || itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        List<ClientMailSubjectLink> all = links.findByUserIdAndItemIdIn(userId, itemIds);
        if (all.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, List<String>> out = new LinkedHashMap<>();
        for (ClientMailSubjectLink l : all) {
            String name = names.computeIfAbsent(l.getWorkspaceId(), id -> linker.nameOf(userId, id));
            if (name != null) {
                out.computeIfAbsent(l.getItemId(), k -> new ArrayList<>()).add(name);
            }
        }
        return out;
    }

    @EventListener
    public void onHostDeleted(RunnerHostLifecycleEvent event) {
        if (event != null && event.kind() == RunnerHostLifecycleEvent.Kind.DELETED) {
            links.purgeHost(event.userId(), event.hostId());
        }
    }

    @Override
    public void purgeAccount(UUID userId, UUID accountId) {
        // sans objet
    }

    @Override
    public void purgeUser(UUID userId) {
        links.purgeUser(userId);
    }
}
