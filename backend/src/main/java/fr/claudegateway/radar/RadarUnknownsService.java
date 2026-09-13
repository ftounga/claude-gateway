package fr.claudegateway.radar;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.radar.dto.RadarSubjectPageViews.UnknownView;
import fr.claudegateway.radar.dto.RadarViews.SubjectDetail;
import fr.claudegateway.radar.sync.RadarHostSettingsRepository;
import fr.claudegateway.radar.sync.RadarSlots;

/**
 * Ce que le Radar ne sait pas sur un sujet (F-103 / SF-103-02) : la lecture du périmètre, puis
 * {@link RadarUnknowns}. Aucune écriture, aucun appel au modèle.
 *
 * <p><b>Isolation.</b> Tout part d'un {@link RadarScope} ; la page du sujet vient de
 * {@link RadarReadService#subject}, l'annuaire et la dernière synchro de dépôts qui portent
 * {@code user_id} et {@code host_id}.</p>
 */
@Service
@Transactional(readOnly = true)
public class RadarUnknownsService {

    /** Fuseau par défaut d'un poste sans réglage, comme la couverture (F-100). */
    static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Paris");

    private final RadarReadService readService;
    private final RadarPersonRepository people;
    private final RadarSyncRepository syncs;
    private final RadarHostSettingsRepository hostSettings;
    private final Clock clock;

    public RadarUnknownsService(RadarReadService readService, RadarPersonRepository people,
            RadarSyncRepository syncs, RadarHostSettingsRepository hostSettings, Clock clock) {
        this.readService = readService;
        this.people = people;
        this.syncs = syncs;
        this.hostSettings = hostSettings;
        this.clock = clock;
    }

    /** Les manques d'un sujet du périmètre ; un sujet d'ailleurs est introuvable (404). */
    public List<UnknownView> unknowns(RadarScope scope, UUID subjectId) {
        return unknowns(scope, readService.subject(scope, subjectId));
    }

    /** Les manques d'une page de sujet déjà lue dans le périmètre (sert aussi la réponse au manager). */
    public List<UnknownView> unknowns(RadarScope scope, SubjectDetail subject) {
        Map<UUID, RadarPerson> directory = people.findByUserIdAndHostIdOrderByDisplayNameAsc(scope.userId(), scope.hostId())
                .stream().collect(Collectors.toMap(RadarPerson::getId, Function.identity()));
        RadarSyncStatus lastSync = syncs.findByUserIdAndHostIdOrderByStartedAtDesc(scope.userId(), scope.hostId(),
                        PageRequest.of(0, 1)).stream()
                .findFirst().map(RadarSync::getStatus).orElse(null);
        ZoneId zone = zone(scope);
        return RadarUnknowns.of(subject, directory, lastSync, zone, LocalDate.now(clock.withZone(zone)));
    }

    /** Le fuseau du poste, pour dire les dates dans sa langue et son heure. */
    ZoneId zone(RadarScope scope) {
        return hostSettings.findByUserIdAndHostId(scope.userId(), scope.hostId())
                .flatMap(row -> RadarSlots.parseZone(row.getTimeZone()))
                .orElse(DEFAULT_ZONE);
    }
}
