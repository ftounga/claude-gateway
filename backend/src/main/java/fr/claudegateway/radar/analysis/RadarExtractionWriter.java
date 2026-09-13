package fr.claudegateway.radar.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.radar.InvalidRadarInputException;
import fr.claudegateway.radar.RadarEvidence;
import fr.claudegateway.radar.RadarEvidenceLink;
import fr.claudegateway.radar.RadarEvidenceLinkRepository;
import fr.claudegateway.radar.RadarLinkKind;
import fr.claudegateway.radar.RadarPerson;
import fr.claudegateway.radar.RadarRegistry;
import fr.claudegateway.radar.RadarRegistry.EvidenceInput;
import fr.claudegateway.radar.RadarRegistry.SummarySentence;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSubject;
import fr.claudegateway.radar.RadarSubjectFact;
import fr.claudegateway.radar.RadarSubjectFactRepository;
import fr.claudegateway.radar.analysis.RadarExtraction.RoleItem;
import fr.claudegateway.radar.analysis.RadarExtraction.SubjectItem;
import fr.claudegateway.radar.analysis.RadarExtraction.SummaryItem;
import fr.claudegateway.radar.analysis.RadarExtractionContext.MessageEntry;
import fr.claudegateway.radar.analysis.RadarExtractionContext.PersonEntry;

/**
 * <b>Les écritures d'une extraction</b> (F-101 / SF-101-03) — toutes par {@link RadarRegistry}.
 *
 * <p>Le registre garantit ce que rien d'autre ne peut garantir : pas de fait sans preuve du même poste,
 * les corrections de l'utilisateur jamais écrasées, un sujet clos jamais rouvert en silence, un sujet
 * fusionné redirigé. Ce composant ne fait que traduire des libellés vérifiés en appels ; il est joué par
 * la file, <b>dans la transaction qui efface le texte brut</b>.</p>
 */
@Component
public class RadarExtractionWriter {

    private final RadarRegistry registry;
    private final RadarSubjectFactRepository facts;
    private final RadarEvidenceLinkRepository links;

    public RadarExtractionWriter(RadarRegistry registry, RadarSubjectFactRepository facts,
            RadarEvidenceLinkRepository links) {
        this.registry = registry;
        this.facts = facts;
        this.links = links;
    }

    /** Ce qu'une écriture a produit, pour les étapes suivantes (SF-101-04). */
    public final class Session {

        private final RadarScope scope;
        private final RadarExtraction extraction;
        private final Map<String, UUID> evidenceIds = new HashMap<>();
        private final Map<String, UUID> personIds = new HashMap<>();

        Session(RadarScope scope, RadarExtraction extraction) {
            this.scope = scope;
            this.extraction = extraction;
        }

        public RadarScope scope() {
            return scope;
        }

        /** Les preuves de ces messages, enregistrées au besoin (idempotent par identifiant de source). */
        public List<UUID> evidence(List<MessageEntry> entries) {
            List<UUID> ids = new ArrayList<>(entries.size());
            for (MessageEntry entry : entries) {
                ids.add(evidenceIds.computeIfAbsent(entry.label(), label -> record(entry)));
            }
            return ids;
        }

        /** La personne du lot, créée ou mise à jour dans l'annuaire du poste. */
        public UUID person(PersonEntry entry) {
            return personIds.computeIfAbsent(entry.label(), label -> {
                RadarPerson person = registry.upsertPerson(scope, entry.authorKey(), entry.name(), entry.title());
                return person.getId();
            });
        }

        private UUID record(MessageEntry entry) {
            RadarExchangeBatch.Message message = entry.message();
            UUID author = entry.author() == null ? null : person(entry.author());
            String deepLink = message.deepLink() != null ? message.deepLink() : entry.exchange().deepLink();
            RadarEvidence evidence = registry.recordEvidence(scope, new EvidenceInput(entry.exchange().source(),
                    message.sourceRef(), message.occurredAt(),
                    RadarQuotes.pick(extraction.quotes().get(entry.label()), message.text()), deepLink, author));
            return evidence.getId();
        }
    }

    /**
     * Écrit une extraction vérifiée.
     *
     * @return la session, qui garde les preuves et les personnes déjà enregistrées ; les sujets écrits
     *         sont rendus dans l'ordre de la sortie
     */
    public Written write(RadarScope scope, RadarExtraction extraction) {
        Session session = new Session(scope, extraction);
        List<UUID> subjectIds = new ArrayList<>();
        for (SubjectItem item : extraction.subjects()) {
            subjectIds.add(writeSubject(session, item));
        }
        return new Written(session, subjectIds);
    }

    /** Une extraction écrite : la session et l'identifiant de chaque sujet, dans l'ordre de la sortie. */
    public record Written(Session session, List<UUID> subjectIds) {
    }

    private UUID writeSubject(Session session, SubjectItem item) {
        RadarScope scope = session.scope();
        List<UUID> proofs = session.evidence(item.evidence());
        RadarSubject subject;
        boolean redirected = false;
        if (item.attached()) {
            UUID shown = item.existing().subject().id();
            subject = registry.attachEvidence(scope, shown, proofs);
            // Fusionné entre la lecture et l'écriture : les preuves suivent la cible, mais on ne réécrit
            // pas le résumé de la cible avec les phrases d'un autre sujet.
            redirected = !subject.getId().equals(shown);
        } else {
            subject = registry.createSubject(scope, item.newName(), null, proofs);
        }
        UUID id = subject.getId();

        for (String alias : item.aliases()) {
            registry.addAlias(scope, id, alias);
        }
        if (item.state() != null && item.attached()) {
            // Un sujet découvert naît « nouveau » : l'état proposé ne s'applique qu'à un sujet suivi.
            registry.setState(scope, id, item.state().value(), session.evidence(item.state().evidence()));
        }
        if (item.nextStep() != null) {
            registry.setNextStep(scope, id, item.nextStep().value(), session.evidence(item.nextStep().evidence()));
        }
        if (item.due() != null) {
            registry.setDueDate(scope, id, item.due().value(), session.evidence(item.due().evidence()));
        }
        if (item.summary() != null && !redirected) {
            registry.replaceSummary(scope, id, sentences(session, id, item.summary()));
        }
        for (RoleItem role : item.roles()) {
            registry.assignRole(scope, id, session.person(role.person()), role.role(), session.evidence(role.evidence()));
        }
        return id;
    }

    /** Les phrases du nouveau résumé ; une reprise garde le texte et les preuves de la phrase existante. */
    private List<SummarySentence> sentences(Session session, UUID subjectId, List<SummaryItem> items) {
        RadarScope scope = session.scope();
        Map<UUID, RadarSubjectFact> existing = new HashMap<>();
        facts.findByUserIdAndHostIdAndSubjectIdOrderByPositionAsc(scope.userId(), scope.hostId(), subjectId)
                .forEach(f -> existing.put(f.getId(), f));
        List<RadarEvidenceLink> summaryLinks = links.findByUserIdAndHostIdAndSubjectIdAndTargetKind(
                scope.userId(), scope.hostId(), subjectId, RadarLinkKind.SUMMARY);
        List<SummarySentence> sentences = new ArrayList<>();
        for (SummaryItem item : items) {
            if (item.reprise() != null) {
                RadarSubjectFact fact = existing.get(item.reprise().id());
                if (fact == null) {
                    // La phrase a disparu depuis la lecture (correction, fusion) : on retentera sur un registre relu.
                    throw new InvalidRadarInputException("Phrase de résumé reprise introuvable.");
                }
                List<UUID> proofs = summaryLinks.stream()
                        .filter(l -> Objects.equals(l.getTargetId(), fact.getId()))
                        .map(RadarEvidenceLink::getEvidenceId).distinct().toList();
                sentences.add(new SummarySentence(fact.getText(), proofs));
            } else {
                sentences.add(new SummarySentence(item.text(), session.evidence(item.evidence())));
            }
        }
        return sentences;
    }
}
