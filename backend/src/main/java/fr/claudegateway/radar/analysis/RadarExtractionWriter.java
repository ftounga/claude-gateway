package fr.claudegateway.radar.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
import fr.claudegateway.radar.RadarRegistry.CommitmentInput;
import fr.claudegateway.radar.RadarRegistry.EvidenceInput;
import fr.claudegateway.radar.RadarText;
import fr.claudegateway.radar.analysis.RadarExtraction.CommitmentItem;
import fr.claudegateway.radar.analysis.RadarExtraction.FollowItem;
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
    /** F-106 / SF-106-06 : un échange qui nomme un projet du poste propose le lien. */
    private final RadarProjectProposer projectProposer;

    public RadarExtractionWriter(RadarRegistry registry, RadarSubjectFactRepository facts,
            RadarEvidenceLinkRepository links, RadarProjectProposer projectProposer) {
        this.registry = registry;
        this.facts = facts;
        this.links = links;
        this.projectProposer = projectProposer;
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
            UUID subjectId = writeSubject(session, item);
            subjectIds.add(subjectId);
            // Une QUESTION posée à l'utilisateur, jamais un lien : la page du sujet la montre (SF-106-06).
            projectProposer.propose(scope, subjectId, proposalTexts(item));
        }
        return new Written(session, subjectIds);
    }

    /** Ce qu'on lit pour proposer un projet : les messages qui prouvent le sujet et le titre de leurs échanges. */
    private static List<String> proposalTexts(SubjectItem item) {
        List<String> texts = new ArrayList<>();
        for (MessageEntry entry : item.evidence()) {
            texts.add(entry.exchange().title());
            texts.add(entry.message().text());
        }
        return texts;
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
        for (CommitmentItem commitment : item.commitments()) {
            registry.recordCommitment(scope, new CommitmentInput(id, commitment.direction(), commitment.description(),
                    commitment.debtor() == null ? null : session.person(commitment.debtor()),
                    commitment.beneficiary() == null ? null : session.person(commitment.beneficiary()),
                    commitment.other() == null ? null : session.person(commitment.other()),
                    commitment.dueDate(), commitment.dueDeduced(), commitment.certainty(),
                    extractionKey(commitment), session.evidence(commitment.evidence())));
        }
        for (FollowItem follow : item.follows()) {
            registry.markCommitment(scope, follow.commitment().id(), follow.status(), session.evidence(follow.evidence()));
        }
        if (item.closure() != null) {
            // Une proposition, jamais une clôture : l'utilisateur confirme ou refuse (SF-99-04).
            registry.proposeClosure(scope, id, session.evidence(item.closure()));
        }
        return id;
    }

    /**
     * La clé d'idempotence d'un engagement lu : la première preuve, le sens et la description normalisée.
     * Un lot redéposé et relu ne duplique pas l'engagement.
     */
    static String extractionKey(CommitmentItem commitment) {
        String material = commitment.evidence().get(0).message().sourceRef() + "|" + commitment.direction() + "|"
                + RadarText.key(commitment.description());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return "sync:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponible", ex);
        }
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
