package fr.claudegateway.atelier;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.dto.ThreadRecallResponse;
import fr.claudegateway.atelier.recall.AtelierSemanticRecall;

/**
 * Le **rappel à la demande** (F-165 / SF-165-06), servi à la commande action {@code /rappel <terme>}.
 *
 * <p><b>Une recherche, pas un tour.</b> Ce service <b>réutilise</b> les briques de recherche du
 * {@code recall} F-162 — le <b>sémantique</b> ({@link AtelierSemanticRecall}, s'il est actif) puis le
 * repli <b>mot-clé</b> ({@link AtelierMessageRepository#searchByContent}) — pour surfacer des extraits de
 * l'historique du fil, <b>sans jamais passer par la boucle modèle</b> (Provider-First / Gateway-First :
 * aucun moteur IA réimplémenté).</p>
 *
 * <p><b>Isolation stricte.</b> {@code requireOwned} (404 indiscernable sur un fil d'autrui), puis toute
 * recherche est filtrée {@code workspace_id} <b>ET</b> {@code user_id} — un utilisateur ne rappelle jamais
 * l'historique d'un autre, ni d'un autre workspace. La relecture sémantique par ids est re-filtrée
 * (défense en profondeur), comme dans l'outil F-162.</p>
 */
@Service
public class AtelierRecallService {

    /** Extraits rendus au plus : quelques-uns suffisent à retrouver un détail (aligné sur F-162). */
    private static final int MAX_EXTRACTS = 5;
    /** Longueur maximale d'un extrait rendu à l'écran (borne : jamais le message entier). */
    private static final int EXCERPT_CHARS = 320;

    private final WorkspaceService workspaceService;
    private final AtelierMessageRepository messageRepository;
    private final AtelierSemanticRecall semanticRecall;

    public AtelierRecallService(WorkspaceService workspaceService,
            AtelierMessageRepository messageRepository, AtelierSemanticRecall semanticRecall) {
        this.workspaceService = workspaceService;
        this.messageRepository = messageRepository;
        this.semanticRecall = semanticRecall;
    }

    /**
     * Cherche {@code query} dans l'historique du fil et rend des extraits bornés.
     *
     * @param userId      utilisateur du contexte de sécurité (jamais un paramètre client)
     * @param workspaceId projet consulté
     * @param query       terme recherché
     * @throws WorkspaceNotFoundException 404 indiscernable si le projet n'existe pas ou n'appartient pas à
     *                                    l'utilisateur (via {@code requireOwned})
     */
    @Transactional(readOnly = true)
    public ThreadRecallResponse search(UUID userId, UUID workspaceId, String query) {
        // Ownership d'abord : un fil d'autrui rend 404, avant toute recherche.
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        String needle = query == null ? "" : query.trim();
        if (needle.isBlank()) {
            return new ThreadRecallResponse("", false, List.of());
        }

        // D'abord le SÉMANTIQUE (par le sens), s'il est actif — repli mot-clé sinon. Même ordre que
        // l'outil recall F-162, isolé user + workspace, relecture re-filtrée.
        List<AtelierMessage> semantic = semanticMatches(userId, workspace.getId(), needle);
        if (semantic != null && !semantic.isEmpty()) {
            return new ThreadRecallResponse(needle, true, extractsOf(semantic));
        }

        // Repli mot-clé : le filet qui ne casse jamais (LIKE insensible à la casse, isolé user+workspace).
        String term = "%" + needle.toLowerCase(Locale.ROOT) + "%";
        List<AtelierMessage> matches = messageRepository.searchByContent(workspace.getId(), userId, term,
                PageRequest.of(0, MAX_EXTRACTS));
        return new ThreadRecallResponse(needle, false, extractsOf(matches));
    }

    /**
     * Chemin sémantique : embed + plus proches voisins isolés, relecture par ids <b>re-filtrée</b>
     * {@code user_id} + {@code workspace_id}, réordonnée selon la similarité. Rend {@code null} (→ repli
     * mot-clé) si le sémantique est éteint, en échec ou sans résultat. Ne lève jamais.
     */
    private List<AtelierMessage> semanticMatches(UUID userId, UUID workspaceId, String needle) {
        if (!semanticRecall.isEnabled()) {
            return null;
        }
        try {
            List<UUID> ids = semanticRecall.search(userId, workspaceId, needle, MAX_EXTRACTS);
            if (ids == null || ids.isEmpty()) {
                return null;
            }
            List<AtelierMessage> found =
                    messageRepository.findByWorkspaceIdAndUserIdAndIdIn(workspaceId, userId, ids);
            if (found.isEmpty()) {
                return null;
            }
            Map<UUID, AtelierMessage> byId = new HashMap<>();
            for (AtelierMessage message : found) {
                byId.put(message.getId(), message);
            }
            return ids.stream().map(byId::get).filter(Objects::nonNull).limit(MAX_EXTRACTS).toList();
        } catch (RuntimeException ex) {
            // Le sémantique ne doit jamais casser le rappel : on retombe sur le mot-clé.
            return null;
        }
    }

    private static List<ThreadRecallResponse.Extract> extractsOf(List<AtelierMessage> messages) {
        return messages.stream()
                .limit(MAX_EXTRACTS)
                .map(message -> new ThreadRecallResponse.Extract(
                        message.getRole(), excerpt(message.getContent()), message.getCreatedAt()))
                .toList();
    }

    /** Extrait borné : espaces normalisés, tronqué à {@link #EXCERPT_CHARS} avec « … ». */
    private static String excerpt(String content) {
        if (content == null) {
            return "";
        }
        String collapsed = content.strip().replaceAll("\\s+", " ");
        if (collapsed.length() <= EXCERPT_CHARS) {
            return collapsed;
        }
        return collapsed.substring(0, EXCERPT_CHARS).stripTrailing() + "…";
    }
}
