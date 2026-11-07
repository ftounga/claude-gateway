package fr.claudegateway.jarvis.clientmail;

import java.text.Normalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.jarvis.mail.JarvisMailItem;
import fr.claudegateway.jarvis.mail.JarvisMailItemRepository;
import fr.claudegateway.jarvis.mail.JarvisMailItemService;
import fr.claudegateway.jarvis.mail.JarvisMailPurge;
import fr.claudegateway.jarvis.mail.JarvisMailTriageService;
import fr.claudegateway.jarvis.mail.MailHeaders;
import fr.claudegateway.jarvis.notices.JarvisNotice;
import fr.claudegateway.jarvis.notices.JarvisNoticeService;
import fr.claudegateway.jarvis.notices.NoticeTexts;
import fr.claudegateway.runner.host.RunnerHostLifecycleEvent;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * <b>Les mails répétitifs</b> (F-249 / SF-249-06, D13) — <b>sans IA</b> : repérer les expéditeurs automatiques et les
 * objets qui reviennent chez un client, proposer « Ignorer ces mails ? », et appliquer les règles acceptées <b>avant</b>
 * le tri par le cerveau rapide (gratuit). Une règle se retire d'un clic : ses mails récents repassent au tri.
 */
@Service
public class ClientMailIgnoreService implements JarvisMailPurge {

    public static final String RULE_REASON = "Ignoré par votre règle";
    static final int MIN_ITEMS = 10;
    static final int EVERY = 10;
    static final int SCAN = 100;
    static final int MIN_REPEAT = 3;
    static final int MAX_PENDING = 10;
    static final int REVERT_DAYS = 7;

    private static final Pattern AUTOMATIC = Pattern.compile(
            "(?i).*(no-?reply|do-?not-?reply|ne-?pas-?repondre|alert|alerte|notification|notif|monitor|jira|gitlab"
                    + "|github|jenkins|confluence|servicenow|sharepoint|teams|automat|robot|daemon|system).*");
    private static final Pattern PREFIX = Pattern.compile(
            "^(?:\\s*(?:re|tr|fw|fwd|réf|ref|aw|wg)\\s*(?:\\[\\d+\\])?\\s*:\\s*)+", Pattern.CASE_INSENSITIVE);

    /** Une proposition vue par l'écran. */
    public record OfferView(UUID id, UUID hostId, String kind, String sample, int occurrences) {
    }

    /** Une règle vue par l'écran. */
    public record RuleView(UUID id, UUID hostId, String kind, String sample) {
    }

    /** La proposition est déjà traitée (409). */
    public static class OfferClosedException extends RuntimeException {
        public OfferClosedException() {
            super("Proposition déjà traitée.");
        }
    }

    /** Introuvable sous ce compte (404). */
    public static class IgnoreNotFoundException extends RuntimeException {
        public IgnoreNotFoundException() {
            super("Introuvable.");
        }
    }

    private final ClientMailIgnoreRuleRepository rules;
    private final ClientMailIgnoreOfferRepository offers;
    private final JarvisMailItemRepository items;
    private final JarvisMailItemService itemService;
    private final RunnerHostRepository hosts;
    private final JarvisNoticeService notices;
    private final Clock clock;

    private JarvisMailTriageService triage;

    public ClientMailIgnoreService(ClientMailIgnoreRuleRepository rules, ClientMailIgnoreOfferRepository offers,
            JarvisMailItemRepository items, JarvisMailItemService itemService, RunnerHostRepository hosts,
            JarvisNoticeService notices, Clock clock) {
        this.rules = rules;
        this.offers = offers;
        this.items = items;
        this.itemService = itemService;
        this.hosts = hosts;
        this.notices = notices;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setTriage(JarvisMailTriageService triage) {
        this.triage = triage;
    }

    /** Objet normalisé pour une règle : préfixes retirés, chiffres en « # », casse et accents ignorés. */
    static String subjectKey(String subject) {
        String s = subject == null ? "" : PREFIX.matcher(subject.strip()).replaceFirst("");
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        s = s.replaceAll("\\d+", "#").replaceAll("\\s+", " ").strip();
        return s.length() <= 200 ? s : s.substring(0, 200);
    }

    static boolean automatic(String address) {
        return address != null && AUTOMATIC.matcher(address).matches();
    }

    static boolean matches(ClientMailIgnoreRule r, MailHeaders h) {
        return ClientMailIgnoreRule.SENDER.equals(r.getKind()) ? r.getRuleValue().equals(h.from())
                : r.getRuleValue().equals(subjectKey(h.subject()));
    }

    /**
     * Avant toute IA : un mail déposé qui répond à une règle de son client est classé « à ignorer ». Rend vrai s'il l'a
     * été (le travail ne le trie ni ne l'annonce alors).
     */
    @Transactional
    public boolean ignoredByRule(JarvisMailItem item) {
        if (item == null || !item.isDeposited() || item.getHostId() == null) {
            return false;
        }
        List<ClientMailIgnoreRule> mine = rules.findByUserIdAndHostId(item.getUserId(), item.getHostId());
        if (mine.isEmpty()) {
            return false;
        }
        MailHeaders h = itemService.headers(item);
        if (mine.stream().noneMatch(r -> matches(r, h))) {
            return false;
        }
        item.setTriage(JarvisMailItem.Triage.A_IGNORER);
        item.setTriageReason(RULE_REASON);
        item.setTriagedAt(OffsetDateTime.now(clock));
        items.save(item);
        return true;
    }

    /**
     * Repère, sans IA, les mails répétitifs d'un client (à 10, 20, 30… courriers déposés) et propose de les ignorer.
     * Rend le nombre de propositions nouvelles.
     */
    @Transactional
    public int detect(UUID userId, UUID hostId) {
        long count = items.countByUserIdAndHostId(userId, hostId);
        if (count < MIN_ITEMS || count % EVERY != 0) {
            return 0;
        }
        return detectNow(userId, hostId);
    }

    /** Le repérage, sans condition de seuil (tests, rattrapage). */
    @Transactional
    public int detectNow(UUID userId, UUID hostId) {
        List<JarvisMailItem> recent = items.findByUserIdAndHostIdOrderByReceivedAtDesc(userId, hostId,
                PageRequest.of(0, SCAN));
        if (recent.size() < MIN_ITEMS) {
            return 0;
        }
        Set<String> known = new HashSet<>();
        offers.findByUserIdAndHostId(userId, hostId).forEach(o -> known.add(o.getKind() + "|" + o.getRuleValue()));
        rules.findByUserIdAndHostId(userId, hostId).forEach(r -> known.add(r.getKind() + "|" + r.getRuleValue()));
        Map<String, Integer> senders = new LinkedHashMap<>();
        Map<String, Integer> subjects = new LinkedHashMap<>();
        Map<String, String> samples = new HashMap<>();
        for (JarvisMailItem i : recent) {
            if (!userId.equals(i.getUserId()) || RULE_REASON.equals(i.getTriageReason())) {
                continue;
            }
            MailHeaders h = itemService.headers(i);
            if (!h.from().isEmpty() && (i.isBulk() || automatic(h.from()))) {
                senders.merge(h.from(), 1, Integer::sum);
            }
            String key = subjectKey(h.subject());
            if (key.length() >= 6) {
                subjects.merge(key, 1, Integer::sum);
                samples.putIfAbsent(key, h.subject().length() <= 200 ? h.subject() : h.subject().substring(0, 200));
            }
        }
        long pending = offers.countByUserIdAndHostIdAndStatus(userId, hostId, ClientMailIgnoreOffer.PENDING);
        int created = 0;
        List<Map.Entry<String, Integer>> candidates = new ArrayList<>();
        senders.entrySet().stream().filter(e -> e.getValue() >= MIN_REPEAT)
                .forEach(e -> candidates.add(Map.entry(ClientMailIgnoreRule.SENDER + "|" + e.getKey(), e.getValue())));
        subjects.entrySet().stream().filter(e -> e.getValue() >= MIN_REPEAT)
                .forEach(e -> candidates.add(Map.entry(ClientMailIgnoreRule.SUBJECT + "|" + e.getKey(), e.getValue())));
        candidates.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (Map.Entry<String, Integer> c : candidates) {
            if (pending + created >= MAX_PENDING || known.contains(c.getKey())) {
                continue;
            }
            String kind = c.getKey().substring(0, c.getKey().indexOf('|'));
            String value = c.getKey().substring(c.getKey().indexOf('|') + 1);
            ClientMailIgnoreOffer o = new ClientMailIgnoreOffer();
            o.setUserId(userId);
            o.setHostId(hostId);
            o.setKind(kind);
            o.setRuleValue(value);
            o.setSample(ClientMailIgnoreRule.SENDER.equals(kind) ? value : samples.getOrDefault(value, value));
            o.setOccurrences(c.getValue());
            o.setCreatedAt(now);
            try {
                offers.saveAndFlush(o);
                created++;
            } catch (DataIntegrityViolationException e) {
                // proposée au même instant ailleurs
            }
        }
        if (created > 0) {
            long open = offers.countByUserIdAndHostIdAndStatus(userId, hostId, ClientMailIgnoreOffer.PENDING);
            String client = NoticeTexts.name(hosts.findByIdAndUserId(hostId, userId).map(h -> h.getName()).orElse(""));
            notices.upsert(userId, JarvisNotice.IGNORE_OFFER, "ignore:" + hostId, hostId, null, (int) open, false,
                    n -> new JarvisNoticeService.Texts(n + (n == 1 ? " mail répétitif" : " mails répétitifs")
                            + " à trier chez " + client + " : les ignorer ?",
                            "J'ai repéré des mails répétitifs chez " + client
                                    + ". Voulez-vous les ignorer ? C'est dans les réglages des mails."),
                    "/jarvis", now.plusDays(7));
        }
        return created;
    }

    /** Accepte une proposition : la règle est créée et appliquée aux mails du client pas encore utiles. */
    @Transactional
    public RuleView accept(UUID userId, UUID offerId) {
        ClientMailIgnoreOffer o = offers.findByIdAndUserId(offerId, userId).orElseThrow(IgnoreNotFoundException::new);
        if (!ClientMailIgnoreOffer.PENDING.equals(o.getStatus())) {
            throw new OfferClosedException();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        o.setStatus(ClientMailIgnoreOffer.ACCEPTED);
        o.setDecidedAt(now);
        offers.save(o);
        ClientMailIgnoreRule r = new ClientMailIgnoreRule();
        r.setUserId(userId);
        r.setHostId(o.getHostId());
        r.setKind(o.getKind());
        r.setRuleValue(o.getRuleValue());
        r.setSample(o.getSample());
        r.setCreatedAt(now);
        rules.save(r);
        for (JarvisMailItem i : items.findByUserIdAndHostIdOrderByReceivedAtDesc(userId, o.getHostId(),
                PageRequest.of(0, 500))) {
            if ((i.getTriage() == JarvisMailItem.Triage.EN_ATTENTE || i.getTriage() == JarvisMailItem.Triage.A_LIRE)
                    && matches(r, itemService.headers(i))) {
                i.setTriage(JarvisMailItem.Triage.A_IGNORER);
                i.setTriageReason(RULE_REASON);
                i.setTriagedAt(now);
                items.save(i);
            }
        }
        closeNoticeIfDone(userId, o.getHostId());
        return new RuleView(r.getId(), r.getHostId(), r.getKind(), r.getSample());
    }

    /** Refuse une proposition (jamais reproposée). */
    @Transactional
    public void decline(UUID userId, UUID offerId) {
        ClientMailIgnoreOffer o = offers.findByIdAndUserId(offerId, userId).orElseThrow(IgnoreNotFoundException::new);
        if (!ClientMailIgnoreOffer.PENDING.equals(o.getStatus())) {
            throw new OfferClosedException();
        }
        o.setStatus(ClientMailIgnoreOffer.DECLINED);
        o.setDecidedAt(OffsetDateTime.now(clock));
        offers.save(o);
        closeNoticeIfDone(userId, o.getHostId());
    }

    /** Retire une règle : ses mails écartés depuis 7 jours repassent au tri. */
    @Transactional
    public void removeRule(UUID userId, UUID ruleId) {
        ClientMailIgnoreRule r = rules.findByIdAndUserId(ruleId, userId).orElseThrow(IgnoreNotFoundException::new);
        rules.delete(r);
        List<ClientMailIgnoreRule> others = rules.findByUserIdAndHostId(userId, r.getHostId()).stream()
                .filter(x -> !x.getId().equals(r.getId())).toList();
        OffsetDateTime since = OffsetDateTime.now(clock).minusDays(REVERT_DAYS);
        int reverted = 0;
        for (JarvisMailItem i : items.findByUserIdAndHostIdOrderByReceivedAtDesc(userId, r.getHostId(),
                PageRequest.of(0, 500))) {
            if (!RULE_REASON.equals(i.getTriageReason()) || i.getReceivedAt().isBefore(since)) {
                continue;
            }
            MailHeaders h = itemService.headers(i);
            if (matches(r, h) && others.stream().noneMatch(x -> matches(x, h))) {
                i.setTriage(JarvisMailItem.Triage.EN_ATTENTE);
                i.setTriageReason(null);
                i.setTriagedAt(null);
                items.save(i);
                reverted++;
            }
        }
        if (reverted > 0 && triage != null) {
            triage.triageSoon(userId);
        }
    }

    public List<OfferView> pendingOffers(UUID userId) {
        return offers.findByUserIdAndStatusOrderByOccurrencesDesc(userId, ClientMailIgnoreOffer.PENDING).stream()
                .map(o -> new OfferView(o.getId(), o.getHostId(), o.getKind(), o.getSample(), o.getOccurrences()))
                .toList();
    }

    public List<RuleView> activeRules(UUID userId) {
        return rules.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(r -> new RuleView(r.getId(), r.getHostId(), r.getKind(), r.getSample())).toList();
    }

    private void closeNoticeIfDone(UUID userId, UUID hostId) {
        if (offers.countByUserIdAndHostIdAndStatus(userId, hostId, ClientMailIgnoreOffer.PENDING) == 0) {
            notices.close(userId, "ignore:" + hostId);
        }
    }

    @EventListener
    public void onHostDeleted(RunnerHostLifecycleEvent event) {
        if (event != null && event.kind() == RunnerHostLifecycleEvent.Kind.DELETED) {
            rules.purgeHost(event.userId(), event.hostId());
            offers.purgeHost(event.userId(), event.hostId());
        }
    }

    @Override
    public void purgeAccount(UUID userId, UUID accountId) {
        // sans objet
    }

    @Override
    public void purgeUser(UUID userId) {
        rules.purgeUser(userId);
        offers.purgeUser(userId);
    }
}
