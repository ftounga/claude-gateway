package fr.claudegateway.vigie;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <b>Le dernier instantané de readiness rapporté par le runner</b>, par poste (F-122 / SF-122-02).
 *
 * <p>L'état de mise en service est <b>volatil</b> : le Chrome managé peut être joignable maintenant et
 * plus dans dix minutes, la session Teams peut expirer. Le persister en base introduirait une
 * migration pour une donnée jetable qu'on re-vérifie de toute façon. Il vit donc <b>en mémoire</b> et
 * <b>se périme</b> : au-delà de {@code app.vigie.readiness.stale-after}, on considère qu'on ne sait
 * plus, et l'agrégation retombe sur {@code PENDING} plutôt que d'afficher un état ancien pour vrai.</p>
 */
@Component
public class VigieReadinessStore {

    private final Duration staleAfter;
    private final Map<UUID, Stored> byHost = new ConcurrentHashMap<>();

    public VigieReadinessStore(
            @Value("${app.vigie.readiness.stale-after:PT2M}") Duration staleAfter) {
        this.staleAfter = staleAfter;
    }

    /** Range l'instantané pour ce poste, horodaté à maintenant. */
    public void put(UUID hostId, VigieReadinessSnapshot snapshot) {
        putAt(hostId, snapshot, Instant.now());
    }

    /** Même chose avec un horodatage explicite : réservé aux tests de péremption. */
    void putAt(UUID hostId, VigieReadinessSnapshot snapshot, Instant at) {
        byHost.put(hostId, new Stored(snapshot, at));
    }

    /** L'instantané de ce poste s'il est frais ; vide s'il n'y en a pas, ou s'il est périmé. */
    public Optional<VigieReadinessSnapshot> get(UUID hostId) {
        Stored stored = byHost.get(hostId);
        if (stored == null || Instant.now().isAfter(stored.at().plus(staleAfter))) {
            return Optional.empty();
        }
        return Optional.of(stored.snapshot());
    }

    private record Stored(VigieReadinessSnapshot snapshot, Instant at) {
    }
}
