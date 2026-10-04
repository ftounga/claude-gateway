package fr.claudegateway.governance.map.index;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Ce que F-174 range sur la carte d'un client, et qui part avec lui</b> (D10).
 *
 * <p>Un seul point de purge pour toutes les tables de F-174 : le journal des consultations
 * (SF-174-01) et l'index de la carte (SF-174-02). Aucune clé étrangère vers {@code users} ni vers
 * les postes : rien ne tombe en cascade, la purge doit être nommée. Les enfants d'abord, puis les
 * sections.</p>
 */
@Service
public class HostMapIndexPurge {

    private final HostMapLookupRepository lookups;
    private final HostMapSectionRepository sections;
    private final HostMapFactRepository facts;
    private final HostMapEntityRepository entities;
    private final HostMapRelationRepository relations;

    public HostMapIndexPurge(HostMapLookupRepository lookups, HostMapSectionRepository sections,
            HostMapFactRepository facts, HostMapEntityRepository entities,
            HostMapRelationRepository relations) {
        this.lookups = lookups;
        this.sections = sections;
        this.facts = facts;
        this.entities = entities;
        this.relations = relations;
    }

    /** À la suppression du compte. */
    @Transactional
    public void purgeUser(UUID userId) {
        if (userId == null) {
            return;
        }
        lookups.purgeUser(userId);
        facts.purgeUser(userId);
        entities.purgeUser(userId);
        relations.purgeUser(userId);
        sections.purgeUser(userId);
    }

    /** À la suppression d'un poste. */
    @Transactional
    public void purgeHost(UUID userId, UUID hostId) {
        if (userId == null || hostId == null) {
            return;
        }
        lookups.purgeHost(userId, hostId);
        facts.purgeHost(userId, hostId);
        entities.purgeHost(userId, hostId);
        relations.purgeHost(userId, hostId);
        sections.purgeHost(userId, hostId);
    }
}
