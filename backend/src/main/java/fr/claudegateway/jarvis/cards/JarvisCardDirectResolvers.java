package fr.claudegateway.jarvis.cards;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.actions.TerminalActionRepository;
import fr.claudegateway.jarvis.JarvisReturnService;
import fr.claudegateway.jarvis.mail.JarvisMailItem;
import fr.claudegateway.jarvis.mail.JarvisMailItemRepository;
import fr.claudegateway.jarvis.mail.JarvisMailItemService;
import fr.claudegateway.jarvis.mail.MailHeaders;
import fr.claudegateway.jarvis.universe.JarvisUniverse;
import fr.claudegateway.jarvis.universe.JarvisUniverseService;

/**
 * <b>Les familles lues en direct</b> (F-248 / SF-248-06) — attentes, « à envoyer », courriels, personnes : chacune relit
 * <b>sa</b> ligne sous {@code user_id} (une ligne par index ; « à envoyer » et l'univers par le service qui les compose
 * déjà sous le compte). Une ligne d'un autre compte est introuvable.
 */
@Configuration
public class JarvisCardDirectResolvers {

    @Bean
    JarvisCardResolver attenteCards(TerminalActionRepository actions, WorkspaceRepository workspaces) {
        return new JarvisCardResolver() {
            @Override
            public String family() {
                return "attente";
            }

            @Override
            public Optional<JarvisCardDetail> detail(UUID userId, String ref) {
                return uuid(ref).flatMap(id -> actions.findByIdAndUserId(id, userId)).map(a -> {
                    String subject = workspaces.findByIdAndUserId(a.getWorkspaceId(), userId)
                            .map(w -> w.getName()).orElse(null);
                    Map<String, String> f = new LinkedHashMap<>();
                    f.put("Attente", a.getDescription());
                    f.put("État", a.getStatus() == null ? null : a.getStatus().name().toLowerCase(Locale.ROOT)
                            .replace('_', ' '));
                    f.put("Qui", a.getRequestedTo() != null ? a.getRequestedTo() : a.getPerson());
                    f.put("Demandé le", JarvisCardSources.at(a.getRequestedAt()));
                    f.put("Par", a.getChannel());
                    f.put("Bloque", a.getBlocks());
                    f.put("Sujet", subject);
                    f.put("Chantier", a.getChantierNumber() == null ? null : String.valueOf(a.getChantierNumber()));
                    f.put("Depuis", JarvisCardSources.at(a.getCreatedAt()));
                    return new JarvisCardDetail(JarvisCardSources.attente(a, null),
                            JarvisCardTemplates.detailFields(f), null, a.getWorkspaceId(), subject,
                            a.getChantierNumber());
                });
            }
        };
    }

    @Bean
    JarvisCardResolver envoiCards(ObjectProvider<JarvisReturnService> returns) {
        return new JarvisCardResolver() {
            @Override
            public String family() {
                return "envoi";
            }

            @Override
            public Optional<JarvisCardDetail> detail(UUID userId, String ref) {
                JarvisReturnService service = returns.getIfAvailable();
                int sep = ref.lastIndexOf(':');
                if (service == null || sep <= 0) {
                    return Optional.empty();
                }
                Optional<UUID> message = uuid(ref.substring(0, sep));
                int block;
                try {
                    block = Integer.parseInt(ref.substring(sep + 1));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
                if (message.isEmpty()) {
                    return Optional.empty();
                }
                JarvisReturnService.ToSendList list = service.toSend(userId, null);
                if (list == null || list.items() == null) {
                    return Optional.empty();
                }
                return list.items().stream().filter(t -> message.get().equals(t.messageId())
                        && t.blockIndex() == block).findFirst().map(t -> {
                            Map<String, String> f = new LinkedHashMap<>();
                            f.put("Pour", t.recipient());
                            f.put("Objet", t.subject());
                            f.put("Type", t.kind());
                            f.put("Sujet", t.workspaceName());
                            f.put("Chantier", t.chantierNumber() == null ? null : String.valueOf(t.chantierNumber()));
                            f.put("Proposé le", JarvisCardSources.at(t.proposedAt()));
                            f.put("Extrait", t.excerpt());
                            return new JarvisCardDetail(JarvisCardSources.envoi(t), JarvisCardTemplates.detailFields(f),
                                    null, t.workspaceId(), t.workspaceName(), t.chantierNumber());
                        });
            }
        };
    }

    @Bean
    JarvisCardResolver courrielCards(JarvisMailItemRepository items, JarvisMailItemService itemService) {
        return new JarvisCardResolver() {
            @Override
            public String family() {
                return "courriel";
            }

            @Override
            public Optional<JarvisCardDetail> detail(UUID userId, String ref) {
                return uuid(ref).flatMap(id -> items.findByIdAndUserId(id, userId)).map(item -> {
                    MailHeaders h = itemService.headers(item);
                    boolean toHandle = item.getTriage() == JarvisMailItem.Triage.A_TRAITER;
                    Map<String, String> f = new LinkedHashMap<>();
                    f.put("De", h.fromName() == null || h.fromName().isBlank() ? h.from()
                            : h.fromName() + " <" + h.from() + ">");
                    f.put("Objet", h.subject());
                    f.put("Reçu", JarvisCardSources.at(item.getReceivedAt()));
                    f.put("Tri", toHandle ? "à traiter" : item.getTriage() == null ? null
                            : item.getTriage().name().toLowerCase(Locale.ROOT).replace('_', ' '));
                    // Jamais le corps : la visionneuse F-242 reste le seul endroit où il s'affiche (D9).
                    return new JarvisCardDetail(JarvisCardSources.courriel(item.getId(), toHandle,
                            item.getReceivedAt(), h.from(), h.fromName(), h.subject()),
                            JarvisCardTemplates.detailFields(f), null, null, null, null);
                });
            }
        };
    }

    @Bean
    JarvisCardResolver personneCards(ObjectProvider<JarvisUniverseService> universe, Clock clock) {
        return new JarvisCardResolver() {
            @Override
            public String family() {
                return "personne";
            }

            @Override
            public Optional<JarvisCardDetail> detail(UUID userId, String ref) {
                JarvisUniverseService service = universe.getIfAvailable();
                if (service == null) {
                    return Optional.empty();
                }
                JarvisUniverse u = service.universe(userId);
                String id = "fait:personne:" + ref;
                return JarvisCardSources.personnes(u, OffsetDateTime.now(clock)).stream()
                        .filter(c -> c.id().equals(id)).findFirst().map(c -> {
                            Map<String, String> f = new LinkedHashMap<>();
                            c.fields().forEach(x -> f.put(x.label(), x.value()));
                            f.put("Aussi appelé", String.join(", ", c.aliases().stream().skip(1).toList()));
                            return new JarvisCardDetail(c, JarvisCardTemplates.detailFields(f), null, null, null, null);
                        });
            }
        };
    }

    static Optional<UUID> uuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
