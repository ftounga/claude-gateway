package fr.claudegateway.jarvis.clientmail;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import fr.claudegateway.jarvis.notices.JarvisNotice;
import fr.claudegateway.jarvis.notices.JarvisNoticeService;
import fr.claudegateway.jarvis.notices.NoticeTexts;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * <b>Le récapitulatif du matin</b> (F-249 / SF-249-03, D11) — à 7 h 30 (Paris), pour chaque compte qui a déposé des
 * mails dans les 24 dernières heures : une annonce d'interface « Hier : 14 mails déposés (EDENRED 9, CAGIP 5), dont 3
 * sur vos sujets : « Alarme Fortec » (2), « SSO » (1). », dite par Jarvis à l'ouverture de {@code /jarvis}. Une seule
 * par jour et par compte (clé {@code recap:<date>}), même avec plusieurs pods. Pas de notification poussée.
 */
@Component
public class ClientMailRecap {

    private static final Logger log = LoggerFactory.getLogger(ClientMailRecap.class);
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    static final int MAX_NAMED = 3;

    private final ClientMailDepositRepository deposits;
    private final ClientMailSubjectLinkRepository links;
    private final ClientMailSubjectLinker linker;
    private final RunnerHostRepository hosts;
    private final JarvisNoticeService notices;
    private final ClientMailProperties properties;
    private final Clock clock;

    public ClientMailRecap(ClientMailDepositRepository deposits, ClientMailSubjectLinkRepository links,
            ClientMailSubjectLinker linker, RunnerHostRepository hosts, JarvisNoticeService notices,
            ClientMailProperties properties, Clock clock) {
        this.deposits = deposits;
        this.links = links;
        this.linker = linker;
        this.hosts = hosts;
        this.notices = notices;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.jarvis.client-mail.recap-cron:0 30 7 * * *}", zone = "Europe/Paris")
    public void morning() {
        if (!properties.isEnabled() || !notices.enabled()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (UUID userId : deposits.usersWithAcceptedSince(now.minusHours(24))) {
            try {
                recap(userId, now);
            } catch (RuntimeException e) {
                log.warn("Dépôts : récapitulatif non posé ({})", e.getClass().getSimpleName());
            }
        }
    }

    /** Le récap d'un compte (rien sans dépôt accepté, rien s'il est déjà posé aujourd'hui). */
    public JarvisNotice recap(UUID userId, OffsetDateTime now) {
        LocalDate today = now.atZoneSameInstant(ZONE).toLocalDate();
        String key = "recap:" + today;
        if (notices.exists(userId, key)) {
            return null;
        }
        OffsetDateTime since = now.minusHours(24);
        List<ClientMailDeposit> accepted = deposits.findByUserIdAndDepositedAtGreaterThanEqualOrderByDepositedAtAsc(
                userId, since).stream().filter(d -> ClientMailDeposit.ACCEPTED.equals(d.getOutcome())).toList();
        if (accepted.isEmpty()) {
            return null;
        }
        Map<UUID, String> hostNames = hosts.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .collect(Collectors.toMap(RunnerHost::getId, RunnerHost::getName, (a, b) -> a));
        Map<String, Integer> perClient = new LinkedHashMap<>();
        accepted.forEach(d -> perClient.merge(
                hostNames.getOrDefault(d.getHostId(), "?"), 1, Integer::sum));
        Set<UUID> acceptedItems = new HashSet<>();
        accepted.forEach(d -> acceptedItems.add(d.getItemId()));
        Map<UUID, Integer> perSubject = new LinkedHashMap<>();
        Set<UUID> linkedItems = new HashSet<>();
        for (ClientMailSubjectLink l : links.findByUserIdAndCreatedAtGreaterThanEqual(userId, since)) {
            if (acceptedItems.contains(l.getItemId())) {
                perSubject.merge(l.getWorkspaceId(), 1, Integer::sum);
                linkedItems.add(l.getItemId());
            }
        }
        Map<UUID, String> names = new HashMap<>();
        List<Map.Entry<UUID, Integer>> top = new ArrayList<>(perSubject.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> namedSubjects = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : top) {
            String n = names.computeIfAbsent(e.getKey(), id -> linker.nameOf(userId, id));
            if (n != null && namedSubjects.size() < MAX_NAMED) {
                namedSubjects.add("« " + NoticeTexts.name(n) + " » (" + e.getValue() + ")");
            }
        }
        int total = accepted.size();
        List<Map.Entry<String, Integer>> clients = new ArrayList<>(perClient.entrySet());
        clients.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        String byClient = clients.stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "));
        String display = "Hier : " + total + (total == 1 ? " mail déposé" : " mails déposés") + " (" + byClient + ")"
                + (linkedItems.isEmpty() ? "." : ", dont " + linkedItems.size() + " sur vos sujets : "
                        + String.join(", ", namedSubjects) + ".");
        String firstSubject = top.isEmpty() ? null : names.get(top.get(0).getKey());
        String speech = "Depuis hier, " + NoticeTexts.word(total).toLowerCase(java.util.Locale.ROOT)
                + (total == 1 ? " mail déposé" : " mails déposés")
                + (linkedItems.isEmpty() ? ", aucun sur vos sujets en cours."
                        : ", dont " + NoticeTexts.word(linkedItems.size()).toLowerCase(java.util.Locale.ROOT)
                                + " sur vos sujets" + (firstSubject == null ? "" : ", surtout « "
                                        + NoticeTexts.name(firstSubject) + " »") + ".");
        OffsetDateTime midnight = today.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        return notices.upsert(userId, JarvisNotice.MORNING_RECAP, key, null, null, total, false,
                count -> new JarvisNoticeService.Texts(display, speech), "/jarvis", midnight);
    }
}
