package fr.claudegateway.jarvis.state;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * <b>Le registre d'état</b> (F-247 / SF-247-02, cadrage §4.1) : la mémoire datée de ce qui ne vit <b>que</b> sur le
 * poste. <b>Une ligne par client + objet + attribut</b>, mise à jour en place (porte Z11) :
 * <ul>
 * <li>même valeur : seule {@code observed_at} avance ;</li>
 * <li>valeur différente : l'actuelle passe en {@code previous} ;</li>
 * <li>observation plus ancienne que la ligne : ignorée ;</li>
 * <li>une source {@code HAUTE} n'est jamais écrasée par une {@code MOYENNE} de moins de 5 min plus récente.</li>
 * </ul>
 *
 * <p><b>Isolation</b> : chaque écriture vérifie que le client appartient au compte ; chaque lecture porte
 * {@code user_id} + {@code host_id}, passe par un index et a une limite (porte Z10). <b>Activé par défaut</b> (D3) ;
 * couper un client purge ses faits.</p>
 */
@Service
public class JarvisStateRegistry {

    private static final Logger log = LoggerFactory.getLogger(JarvisStateRegistry.class);
    static final Duration HIGH_GUARD = Duration.ofMinutes(5);
    public static final int MAX_READ = 20;
    /** SF-247-14 — les observations écartées par le filtre « aucun secret » à l'enregistrement (mesure). */
    static final java.util.concurrent.atomic.AtomicLong SECRETS_REJECTED = new java.util.concurrent.atomic.AtomicLong();

    /** Les observations écartées à l'enregistrement parce qu'elles ressemblaient à un secret. */
    public static long secretsRejected() {
        return SECRETS_REJECTED.get();
    }

    private final JarvisStateFactRepository facts;
    private final JarvisStateSettingsRepository settings;
    private final RunnerHostRepository hosts;
    private final JarvisStateSeal seal;
    private final JarvisStateProperties properties;
    private final Clock clock;
    /** Ce qui veut savoir que le registre d'un client a changé (le bloc préparé à l'avance, SF-247-10). */
    private final List<BiConsumer<UUID, UUID>> listeners = new CopyOnWriteArrayList<>();
    /** Ce qui doit être effacé avec les faits d'un client (les transcriptions de captures, SF-247-12). */
    private final List<BiConsumer<UUID, UUID>> eraseListeners = new CopyOnWriteArrayList<>();

    public JarvisStateRegistry(JarvisStateFactRepository facts, JarvisStateSettingsRepository settings,
            RunnerHostRepository hosts, JarvisStateSeal seal, JarvisStateProperties properties, Clock clock) {
        this.facts = facts;
        this.settings = settings;
        this.hosts = hosts;
        this.seal = seal;
        this.properties = properties;
        this.clock = clock;
    }

    /** Une observation à enregistrer. {@code observedAt} = l'heure de la sortie d'outil. */
    public record Observation(UUID hostId, UUID workspaceId, String objectKind, String objectKey, String attribute,
            String value, OffsetDateTime observedAt, String source, String sourceRef, String confidence) {
    }

    /** Un fait lu, descellé. */
    public record Fact(UUID id, UUID hostId, UUID workspaceId, String objectKind, String objectKey, String attribute,
            String value, String previousValue, OffsetDateTime previousObservedAt, OffsetDateTime observedAt,
            OffsetDateTime changedAt, String source, String confidence) {
    }

    /** S'abonne aux changements du registre (userId, hostId). */
    public void onChange(BiConsumer<UUID, UUID> listener) {
        listeners.add(listener);
    }

    /** S'abonne à « effacer tout » d'un client (userId, hostId) : couper ou effacer purge aussi ce qui s'y rattache. */
    public void onErase(BiConsumer<UUID, UUID> listener) {
        eraseListeners.add(listener);
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** Le réglage d'un client (ligne absente ⇒ tout activé). */
    @Transactional(readOnly = true)
    public JarvisStateSettings settingsOf(UUID userId, UUID hostId) {
        return settings.findByUserIdAndHostId(userId, hostId)
                .orElseGet(() -> JarvisStateSettings.defaults(userId, hostId));
    }

    /**
     * Enregistre des observations (toutes du même compte). Rien n'est écrit si le registre est coupé (global ou
     * client), si la famille est coupée, si l'observation sort du vocabulaire ou si le client n'est pas au compte.
     *
     * @return le nombre de lignes créées ou modifiées
     */
    @Transactional
    public int record(UUID userId, List<Observation> observations) {
        if (!properties.enabled() || userId == null || observations == null || observations.isEmpty()) {
            return 0;
        }
        int written = 0;
        java.util.Map<UUID, JarvisStateSettings> perHost = new java.util.HashMap<>();
        java.util.Set<UUID> touched = new java.util.LinkedHashSet<>();
        for (Observation o : observations) {
            Observation clean = normalize(o);
            if (clean == null) {
                continue;
            }
            JarvisStateSettings s = perHost.computeIfAbsent(clean.hostId(), h -> hosts.findByIdAndUserId(h, userId)
                    .map(x -> settingsOf(userId, h)).orElse(null));
            if (s == null || !s.isEnabled() || !s.familyAllowed(clean.objectKind())) {
                continue;
            }
            if (upsert(userId, clean)) {
                written++;
                touched.add(clean.hostId());
            }
        }
        for (UUID hostId : touched) {
            cap(userId, hostId);
            fire(userId, hostId);
        }
        return written;
    }

    /** L'upsert idempotent d'une observation ; vrai si la ligne a été créée ou modifiée. */
    boolean upsert(UUID userId, Observation o) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<JarvisStateFact> existing = facts.findByUserIdAndHostIdAndObjectKeyAndAttribute(userId, o.hostId(),
                o.objectKey(), o.attribute());
        if (existing.isEmpty()) {
            facts.save(JarvisStateFact.builder().userId(userId).hostId(o.hostId()).workspaceId(o.workspaceId())
                    .objectKind(o.objectKind()).objectKey(o.objectKey()).attribute(o.attribute())
                    .valueSealed(seal.seal(o.value())).observedAt(o.observedAt()).source(o.source())
                    .sourceRef(o.sourceRef()).confidence(o.confidence()).createdAt(now).updatedAt(now).build());
            return true;
        }
        JarvisStateFact f = existing.get();
        if (!o.observedAt().isAfter(f.getObservedAt())) {
            return false;
        }
        if (JarvisStateVocabulary.HAUTE.equals(f.getConfidence())
                && JarvisStateVocabulary.MOYENNE.equals(o.confidence())
                && Duration.between(f.getObservedAt(), o.observedAt()).compareTo(HIGH_GUARD) < 0) {
            return false;
        }
        String current = seal.open(f.getValueSealed());
        if (!Objects.equals(current, o.value())) {
            f.setPreviousValueSealed(f.getValueSealed());
            f.setPreviousObservedAt(f.getObservedAt());
            f.setValueSealed(seal.seal(o.value()));
            f.setChangedAt(o.observedAt());
        }
        f.setObservedAt(o.observedAt());
        f.setSource(o.source());
        f.setSourceRef(o.sourceRef());
        f.setConfidence(o.confidence());
        f.setObjectKind(o.objectKind());
        if (o.workspaceId() != null) {
            f.setWorkspaceId(o.workspaceId());
        }
        f.setUpdatedAt(now);
        facts.save(f);
        return true;
    }

    /** Vocabulaire fermé, bornes, clé normalisée ; {@code null} si l'observation n'est pas recevable. */
    static Observation normalize(Observation o) {
        if (o == null || o.hostId() == null || o.observedAt() == null || o.value() == null) {
            return null;
        }
        String kind = o.objectKind() == null ? null : o.objectKind().strip().toUpperCase(Locale.ROOT);
        String attribute = o.attribute() == null ? null : o.attribute().strip().toLowerCase(Locale.ROOT);
        String source = o.source() == null ? null : o.source().strip().toUpperCase(Locale.ROOT);
        String confidence = o.confidence() == null ? null : o.confidence().strip().toUpperCase(Locale.ROOT);
        String key = o.objectKey() == null ? null : o.objectKey().strip().replaceAll("\\s+", " ");
        String value = o.value().strip().replaceAll("[\\r\\n\\t]+", " ");
        if (!JarvisStateVocabulary.KINDS.contains(kind) || !JarvisStateVocabulary.ATTRIBUTES.contains(attribute)
                || !JarvisStateVocabulary.SOURCES.contains(source)
                || !JarvisStateVocabulary.CONFIDENCES.contains(confidence) || key == null || key.isEmpty()
                || key.length() > JarvisStateVocabulary.MAX_KEY || value.isEmpty()) {
            return null;
        }
        // SF-247-14 — le filtre « aucun secret » à l'enregistrement : quelle que soit la source, une valeur ou une clé
        // qui ressemble à un secret n'entre jamais au registre (on préfère perdre un fait que garder un secret).
        if (fr.claudegateway.jarvis.state.extract.SecretFilter.looksSecret(value)
                || fr.claudegateway.jarvis.state.extract.SecretFilter.looksSecret(key)) {
            SECRETS_REJECTED.incrementAndGet();
            return null;
        }
        if (value.length() > JarvisStateVocabulary.MAX_VALUE) {
            value = value.substring(0, JarvisStateVocabulary.MAX_VALUE - 1) + "…";
        }
        String ref = o.sourceRef() == null ? null : o.sourceRef().length() > 64 ? o.sourceRef().substring(0, 64)
                : o.sourceRef();
        return new Observation(o.hostId(), o.workspaceId(), kind, key, attribute, value, o.observedAt(), source, ref,
                confidence);
    }

    /** Le plafond par client : au-delà, les plus anciens sont purgés. */
    void cap(UUID userId, UUID hostId) {
        int max = properties.maxFactsPerHost();
        if (facts.countByUserIdAndHostId(userId, hostId) <= max) {
            return;
        }
        List<OffsetDateTime> edge = facts.observedDesc(userId, hostId, PageRequest.of(max - 1, 1));
        if (!edge.isEmpty()) {
            facts.deleteOlder(userId, hostId, edge.get(0));
        }
    }

    // ---- Lectures : user_id + host_id, index, LIMIT (Z10). On ne descelle que les lignes retenues.

    /** Les plus récents du client. */
    @Transactional(readOnly = true)
    public List<Fact> recent(UUID userId, UUID hostId, int limit) {
        if (!properties.enabled() || userId == null || hostId == null) {
            return List.of();
        }
        return open(facts.findByUserIdAndHostIdOrderByObservedAtDesc(userId, hostId, page(limit)));
    }

    /** Les plus récents d'une famille. */
    @Transactional(readOnly = true)
    public List<Fact> byKind(UUID userId, UUID hostId, String kind, int limit) {
        if (!properties.enabled() || userId == null || hostId == null || kind == null) {
            return List.of();
        }
        return open(facts.findByUserIdAndHostIdAndObjectKindOrderByObservedAtDesc(userId, hostId,
                kind.strip().toUpperCase(Locale.ROOT), page(limit)));
    }

    /** Par clé exacte ou préfixe ({@code JIRA:PROJ-}) ; les jokers SQL de la saisie sont neutralisés. */
    @Transactional(readOnly = true)
    public List<Fact> byKey(UUID userId, UUID hostId, String keyOrPrefix, int limit) {
        if (!properties.enabled() || userId == null || hostId == null || keyOrPrefix == null
                || keyOrPrefix.isBlank()) {
            return List.of();
        }
        String safe = keyOrPrefix.strip().replace("\\", "").replace("%", "").replace("_", "\\_");
        if (safe.length() < 2) {
            return List.of();
        }
        return open(facts.findByKeyPrefix(userId, hostId, safe + "%", page(limit)));
    }

    /** Un fait du compte (effacement d'un élément, SF-247-15). */
    @Transactional
    public boolean delete(UUID userId, UUID factId) {
        Optional<JarvisStateFact> f = facts.findByIdAndUserId(factId, userId);
        f.ifPresent(x -> {
            facts.delete(x);
            fire(userId, x.getHostId());
        });
        return f.isPresent();
    }

    private static PageRequest page(int limit) {
        return PageRequest.of(0, Math.max(1, Math.min(limit, MAX_READ)));
    }

    private List<Fact> open(List<JarvisStateFact> rows) {
        List<Fact> out = new ArrayList<>(rows.size());
        for (JarvisStateFact f : rows) {
            String value = seal.open(f.getValueSealed());
            if (value == null) {
                continue;
            }
            out.add(new Fact(f.getId(), f.getHostId(), f.getWorkspaceId(), f.getObjectKind(), f.getObjectKey(),
                    f.getAttribute(), value, seal.open(f.getPreviousValueSealed()), f.getPreviousObservedAt(),
                    f.getObservedAt(), f.getChangedAt(), f.getSource(), f.getConfidence()));
        }
        return out;
    }

    // ---- Réglage, coupure, purge.

    /** Couper (ou rallumer) un client ; couper <b>purge</b> ses faits. */
    @Transactional
    public JarvisStateSettings setEnabled(UUID userId, UUID hostId, boolean enabled) {
        JarvisStateSettings s = settings.findByUserIdAndHostId(userId, hostId)
                .orElseGet(() -> JarvisStateSettings.defaults(userId, hostId));
        s.setEnabled(enabled);
        s.setUpdatedAt(OffsetDateTime.now(clock));
        JarvisStateSettings saved = settings.save(s);
        if (!enabled) {
            eraseAll(userId, hostId);
        }
        return saved;
    }

    /** Enregistre un réglage complet (le client doit appartenir au compte, vérifié par l'appelant). */
    @Transactional
    public JarvisStateSettings save(JarvisStateSettings s) {
        s.setUpdatedAt(OffsetDateTime.now(clock));
        JarvisStateSettings saved = settings.save(s);
        if (!saved.isEnabled()) {
            eraseAll(saved.getUserId(), saved.getHostId());
        }
        return saved;
    }

    /** « Effacer tout » : les faits du client, et ce qui s'y rattache (transcriptions de captures, SF-247-12). */
    @Transactional
    public int eraseAll(UUID userId, UUID hostId) {
        int n = facts.deleteAllOf(userId, hostId);
        for (BiConsumer<UUID, UUID> l : eraseListeners) {
            l.accept(userId, hostId);
        }
        fire(userId, hostId);
        return n;
    }

    /** Purge nocturne : faits non revus depuis la rétention du client (30 j par défaut). */
    @Scheduled(cron = "${app.jarvis.state.purge-cron:0 40 3 * * *}", zone = "Europe/Paris")
    @Transactional
    public int purge() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int n = 0;
        for (JarvisStateSettings s : settings.findByFactRetentionDaysNot(
                JarvisStateSettings.DEFAULT_FACT_RETENTION_DAYS)) {
            n += facts.deleteOlder(s.getUserId(), s.getHostId(), now.minusDays(Math.max(1,
                    s.getFactRetentionDays())));
        }
        // Rétention par défaut pour tous les clients qui n'en ont pas de propre.
        n += facts.deleteDefaultOlder(now.minusDays(JarvisStateSettings.DEFAULT_FACT_RETENTION_DAYS),
                JarvisStateSettings.DEFAULT_FACT_RETENTION_DAYS);
        return n;
    }

    private void fire(UUID userId, UUID hostId) {
        for (BiConsumer<UUID, UUID> l : listeners) {
            try {
                l.accept(userId, hostId);
            } catch (RuntimeException e) {
                log.warn("Registre d'état : abonné en échec ({})", e.getClass().getSimpleName());
            }
        }
    }
}
