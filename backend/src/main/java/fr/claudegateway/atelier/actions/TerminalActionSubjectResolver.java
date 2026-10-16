package fr.claudegateway.atelier.actions;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.radar.RadarSubject;
import fr.claudegateway.radar.RadarSubjectProject;
import fr.claudegateway.radar.RadarSubjectProjectRepository;
import fr.claudegateway.radar.RadarSubjectProjectState;
import fr.claudegateway.radar.RadarSubjectRepository;

/**
 * <b>Le sujet du Radar d'une attente</b> (F-180 / SF-180-02, décision D3) : en prod, {@code subject_id}
 * n'était rempli sur aucune des 37 attentes. À l'inscription, le terminal qui a <b>exactement un</b>
 * sujet lié et confirmé (F-106) le donne à l'attente. Zéro ou plusieurs : nul — mieux vaut pas de
 * rattachement qu'un mauvais.
 *
 * <p><b>Isolation</b> : liens et sujets lus sous {@code user_id} + {@code host_id} du terminal
 * <b>possédé</b> (l'appelant l'a vérifié). Best-effort : une lecture en échec rend « aucun sujet ».</p>
 */
@Component
public class TerminalActionSubjectResolver {

    private static final Logger log = LoggerFactory.getLogger(TerminalActionSubjectResolver.class);

    private final RadarSubjectProjectRepository links;
    private final RadarSubjectRepository subjects;

    public TerminalActionSubjectResolver(RadarSubjectProjectRepository links, RadarSubjectRepository subjects) {
        this.links = links;
        this.subjects = subjects;
    }

    /** Le sujet unique lié et confirmé de ce terminal, s'il y en a un seul. */
    public Optional<UUID> subjectOf(UUID userId, Workspace workspace) {
        if (userId == null || workspace == null || workspace.getHostId() == null) {
            return Optional.empty();
        }
        try {
            List<UUID> linked = links.findByUserIdAndHostIdAndState(userId, workspace.getHostId(),
                            RadarSubjectProjectState.CONFIRMED).stream()
                    .filter(l -> workspace.getId().equals(l.getWorkspaceId()))
                    .map(RadarSubjectProject::getSubjectId)
                    .distinct()
                    .toList();
            return linked.size() == 1 ? Optional.of(linked.get(0)) : Optional.empty();
        } catch (RuntimeException e) {
            log.debug("Sujet du terminal illisible (best-effort) : {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Les noms des sujets donnés, lus sous {@code user_id} + {@code host_id}. */
    public Map<UUID, String> namesOf(UUID userId, UUID hostId, Collection<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        if (userId == null || hostId == null || ids == null || ids.isEmpty()) {
            return names;
        }
        try {
            for (RadarSubject subject : subjects.findByUserIdAndHostIdAndIdIn(userId, hostId, ids)) {
                names.put(subject.getId(), subject.getName());
            }
        } catch (RuntimeException e) {
            log.debug("Noms de sujets illisibles (best-effort) : {}", e.getClass().getSimpleName());
        }
        return names;
    }
}
