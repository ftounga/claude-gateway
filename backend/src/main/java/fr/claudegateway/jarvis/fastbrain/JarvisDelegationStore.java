package fr.claudegateway.jarvis.fastbrain;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import fr.claudegateway.jarvis.mail.JarvisMailPurge;
import fr.claudegateway.jarvis.thread.JarvisLinkService;

/**
 * <b>Les délégations de Jarvis, en base</b> (F-272 / SF-272-01) — le registre partagé entre les pods : le cerveau
 * rapide offre sur un pod, le tour Opus du fil cible prend sur n'importe quel autre. La prise est <b>atomique</b>
 * ({@code UPDATE … WHERE taken_at IS NULL}) : un seul tour obtient une reformulation.
 *
 * <p>Le balayage (chaque minute, idempotent sur chaque pod) clôt ce qui ne finira plus : une offre jamais prise après
 * {@link JarvisDelegationRegistry#REFORMULATION_TTL} (la ligne-lien passe à « échec »), une délégation prise mais jamais
 * terminée après {@link JarvisDelegationRegistry#MAX_AGE} (pod arrêté en plein tour ; « interrompu »).</p>
 *
 * <p><b>Isolation</b> : chaque lecture d'un compte porte {@code user_id}. <b>Best-effort</b> : une panne de base ne
 * lève jamais vers le cerveau rapide ni vers le tour (le registre retombe sur « rien à prendre »).</p>
 */
@Component
public class JarvisDelegationStore implements JarvisMailPurge {

    private static final Logger log = LoggerFactory.getLogger(JarvisDelegationStore.class);
    static final int MAX_LABEL = 300;
    static final int MAX_DEMANDE = 4000;
    static final int MAX_NAME = 200;
    static final int SWEEP_BATCH = 100;
    static final Duration RETENTION = Duration.ofDays(7);

    private final JarvisDelegationRepository repository;
    private final Clock clock;
    private JarvisLinkService links;

    public JarvisDelegationStore(JarvisDelegationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Les lignes-liens à clore au balayage (facultatif : sans service, seules les délégations sont closes). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLinks(JarvisLinkService links) {
        this.links = links;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /** L'offre d'une délégation (demande reformulée, ligne-lien) : rend vrai si elle est rangée. */
    public boolean offer(UUID userId, UUID subjectId, Integer chantier, UUID delegationId, String demande,
            String label, UUID linkId) {
        try {
            JarvisDelegation d = row(userId, delegationId, subjectId, chantier);
            d.setSubjectId(subjectId);
            d.setChantier(chantier);
            d.setDemande(clip(demande, MAX_DEMANDE));
            if (label != null && !label.isBlank()) {
                d.setLabel(clip(label, MAX_LABEL));
            }
            d.setLinkId(linkId);
            repository.saveAndFlush(d);
            answered(userId, subjectId, chantier);
            return true;
        } catch (DataIntegrityViolationException e) {
            // La même délégation, rangée au même instant par l'autre appel (started) : on complète celle-là.
            return retryOffer(userId, subjectId, chantier, delegationId, demande, label, linkId);
        } catch (RuntimeException e) {
            log.warn("Délégation Jarvis non rangée ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean retryOffer(UUID userId, UUID subjectId, Integer chantier, UUID delegationId, String demande,
            String label, UUID linkId) {
        try {
            JarvisDelegation d = repository.findByUserIdAndDelegationId(userId, delegationId).orElse(null);
            if (d == null) {
                return false;
            }
            d.setDemande(clip(demande, MAX_DEMANDE));
            d.setLinkId(linkId);
            if (label != null && !label.isBlank()) {
                d.setLabel(clip(label, MAX_LABEL));
            }
            repository.saveAndFlush(d);
            return true;
        } catch (RuntimeException e) {
            log.warn("Délégation Jarvis non rangée ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    /** La délégation est lancée (nom du sujet, libellé dit) : crée la ligne si l'offre ne l'a pas fait. */
    public void started(UUID userId, UUID delegationId, UUID subjectId, String subjectName, Integer chantier,
            String label) {
        try {
            JarvisDelegation d = row(userId, delegationId, subjectId, chantier);
            d.setSubjectName(clip(subjectName, MAX_NAME));
            if (label != null && !label.isBlank()) {
                d.setLabel(clip(label, MAX_LABEL));
            }
            repository.saveAndFlush(d);
        } catch (RuntimeException e) {
            log.debug("Délégation Jarvis non marquée lancée ({})", e.getClass().getSimpleName());
        }
    }

    private JarvisDelegation row(UUID userId, UUID delegationId, UUID subjectId, Integer chantier) {
        return repository.findByUserIdAndDelegationId(userId, delegationId).orElseGet(() -> {
            JarvisDelegation d = new JarvisDelegation();
            d.setId(UUID.randomUUID());
            d.setUserId(userId);
            d.setDelegationId(delegationId);
            d.setSubjectId(subjectId);
            d.setChantier(chantier);
            d.setCreatedAt(now());
            return d;
        });
    }

    /** Prend (une seule fois, tous pods confondus) l'offre la plus récente du fil, ou {@code null}. */
    public JarvisDelegationRegistry.Reformulation take(UUID userId, UUID subjectId, Integer chantier,
            Duration ttl) {
        try {
            OffsetDateTime since = now().minus(ttl);
            PageRequest first = PageRequest.of(0, 3);
            List<JarvisDelegation> offers = chantier == null
                    ? repository.openOffersCommon(userId, subjectId, since, first)
                    : repository.openOffers(userId, subjectId, chantier, since, first);
            for (JarvisDelegation d : offers) {
                if (repository.claim(d.getId(), userId, now()) == 1) {
                    return new JarvisDelegationRegistry.Reformulation(d.getDelegationId(), d.getDemande(),
                            d.getLabel(), d.getCreatedAt().toInstant(), d.getLinkId());
                }
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("Délégation Jarvis non lue ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Les délégations en cours du compte : non terminées, prises par leur tour, ou offertes depuis moins de
     * {@code grace} (le tour n'a peut-être pas encore démarré) ; au plus {@code max}, de la plus ancienne à la plus
     * récente.
     */
    public List<JarvisDelegationRegistry.Running> running(UUID userId, Duration grace, Duration maxAge, int max) {
        try {
            OffsetDateTime now = now();
            OffsetDateTime graceStart = now.minus(grace);
            List<JarvisDelegation> rows = repository.unfinished(userId, now.minus(maxAge), PageRequest.of(0, 50));
            List<JarvisDelegationRegistry.Running> out = rows.stream()
                    .filter(d -> d.getTakenAt() != null || !d.getCreatedAt().isBefore(graceStart))
                    .map(d -> new JarvisDelegationRegistry.Running(d.getDelegationId(), d.getSubjectId(),
                            d.getSubjectName() == null ? "" : d.getSubjectName(), d.getChantier(),
                            d.getLabel() == null ? "" : d.getLabel(), d.getCreatedAt().toInstant()))
                    .toList();
            return out.size() <= max ? out : out.subList(out.size() - max, out.size());
        } catch (RuntimeException e) {
            log.debug("Délégations Jarvis illisibles ({})", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Le tour de la délégation est fini (réponse, échec ou interruption). */
    public void finished(UUID userId, UUID delegationId) {
        finished(userId, delegationId, JarvisWorkPhase.DONE);
    }

    /** F-270 / SF-270-01 — le tour du travail est fini, avec son issue ({@code DONE}, {@code WAITING_YES}…). */
    public void finished(UUID userId, UUID delegationId, JarvisWorkPhase outcome) {
        if (userId == null || delegationId == null) {
            return;
        }
        try {
            repository.finishWith(userId, delegationId, (outcome == null ? JarvisWorkPhase.DONE : outcome).name(),
                    now());
        } catch (RuntimeException e) {
            log.debug("Délégation Jarvis non close ({})", e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ F-270 / SF-270-01 : les travaux vivants

    /** L'issue d'une annonce de tour direct. */
    public enum Announced { CREATED, CONFLICT, FAILED }

    /**
     * Un tour direct né à la voix, annoncé par l'écran avant de l'ouvrir : ligne {@code DIRECT} sans demande, que le
     * tour du fil prendra ({@link #takeDirect}). Un identifiant déjà connu du compte n'est jamais réécrit.
     */
    public Announced direct(UUID userId, UUID delegationId, UUID subjectId, String subjectName, Integer chantier,
            String label) {
        if (userId == null || delegationId == null || subjectId == null) {
            return Announced.FAILED;
        }
        try {
            if (repository.findByUserIdAndDelegationId(userId, delegationId).isPresent()) {
                return Announced.CONFLICT;
            }
            JarvisDelegation d = row(userId, delegationId, subjectId, chantier);
            d.setOrigin(JarvisDelegation.ORIGIN_DIRECT);
            d.setSubjectName(clip(subjectName, MAX_NAME));
            if (label != null && !label.isBlank()) {
                d.setLabel(clip(label, MAX_LABEL));
            }
            repository.saveAndFlush(d);
            answered(userId, subjectId, chantier);
            return Announced.CREATED;
        } catch (DataIntegrityViolationException e) {
            return Announced.CONFLICT;
        } catch (RuntimeException e) {
            log.warn("Tour direct Jarvis non rangé ({})", e.getClass().getSimpleName());
            return Announced.FAILED;
        }
    }

    /** Prend (une seule fois, tous pods confondus) le tour direct annoncé le plus récent du fil, ou {@code null}. */
    public JarvisDelegationRegistry.Reformulation takeDirect(UUID userId, UUID subjectId, Integer chantier, Duration ttl) {
        try {
            OffsetDateTime since = now().minus(ttl);
            PageRequest first = PageRequest.of(0, 3);
            List<JarvisDelegation> open = chantier == null
                    ? repository.openDirectCommon(userId, subjectId, since, first)
                    : repository.openDirect(userId, subjectId, chantier, since, first);
            for (JarvisDelegation d : open) {
                if (repository.claim(d.getId(), userId, now()) == 1) {
                    return new JarvisDelegationRegistry.Reformulation(d.getDelegationId(), null, d.getLabel(),
                            d.getCreatedAt().toInstant(), null);
                }
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("Tour direct Jarvis non lu ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /** La phase d'un travail vivant ; rend vrai si elle a changé. */
    public boolean phase(UUID userId, UUID delegationId, JarvisWorkPhase phase) {
        if (userId == null || delegationId == null || phase == null || phase.ended()) {
            return false;
        }
        try {
            return repository.phase(userId, delegationId, phase.name()) == 1;
        } catch (RuntimeException e) {
            log.debug("Phase de travail Jarvis non posée ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    /** Les travaux du compte créés depuis {@code since} (vivants et finis), au plus {@code max}. */
    public List<JarvisDelegation> recent(UUID userId, Duration since, int max) {
        if (userId == null) {
            return List.of();
        }
        try {
            return repository.recent(userId, now().minus(since), PageRequest.of(0, Math.max(1, max)));
        } catch (RuntimeException e) {
            log.debug("Travaux Jarvis illisibles ({})", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Un nouveau travail est parti sur ce fil : le « oui » qu'il attendait n'est plus attendu. */
    private void answered(UUID userId, UUID subjectId, Integer chantier) {
        try {
            if (chantier == null) {
                repository.answeredCommon(userId, subjectId);
            } else {
                repository.answered(userId, subjectId, chantier);
            }
        } catch (RuntimeException e) {
            log.debug("Confirmation Jarvis non close ({})", e.getClass().getSimpleName());
        }
    }

    /**
     * Le balayage : les offres jamais prises après {@code ttl} et les délégations prises mais jamais terminées après
     * {@code maxAge} sont closes, leur ligne-lien aussi (« échec » ou « interrompu ») ; les lignes de plus de 7 jours
     * sont purgées. Rend le nombre de délégations closes par ce passage.
     */
    public int sweep(Duration ttl, Duration maxAge) {
        int closed = 0;
        OffsetDateTime now = now();
        try {
            for (JarvisDelegation d : repository.expiredOffers(now.minus(ttl), PageRequest.of(0, SWEEP_BATCH))) {
                if (repository.close(d.getId(), now) == 1) {
                    closed++;
                    closeLink(d, false);
                }
            }
            for (JarvisDelegation d : repository.staleTaken(now.minus(maxAge), PageRequest.of(0, SWEEP_BATCH))) {
                if (repository.close(d.getId(), now) == 1) {
                    closed++;
                    closeLink(d, true);
                }
            }
            repository.purgeBefore(now.minus(RETENTION));
        } catch (RuntimeException e) {
            log.warn("Balayage des délégations Jarvis reporté ({})", e.getClass().getSimpleName());
        }
        return closed;
    }

    private void closeLink(JarvisDelegation d, boolean interrupted) {
        if (links != null && d.getLinkId() != null) {
            links.finish(d.getUserId(), d.getLinkId(), null, interrupted);
        }
    }

    @Scheduled(fixedDelayString = "${app.jarvis.delegations.sweep-ms:60000}",
            initialDelayString = "${app.jarvis.delegations.sweep-initial-ms:60000}")
    public void scheduledSweep() {
        int closed = sweep(JarvisDelegationRegistry.REFORMULATION_TTL, JarvisDelegationRegistry.MAX_AGE);
        if (closed > 0) {
            log.info("Délégations Jarvis closes au balayage : {}", closed);
        }
    }

    @Override
    public void purgeAccount(UUID userId, UUID accountId) {
        // Les délégations ne dépendent pas d'une boîte IMAP.
    }

    @Override
    public void purgeUser(UUID userId) {
        if (userId != null) {
            repository.purgeUser(userId);
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String v = s.strip();
        return v.length() <= max ? v : v.substring(0, max - 1) + "…";
    }
}
