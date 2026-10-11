package fr.claudegateway.atelier.deposit;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Porte les fichiers déposés dans la <b>consigne du tour</b> (F-115 / SF-115-03) : au début d'un tour,
 * la liste des fichiers déposés (chemins seulement) est ajoutée à la consigne, et l'agent les lit
 * ensuite avec {@code read_file}. Comme Claude Code, on ne réinjecte <b>jamais</b> le binaire —
 * seulement le chemin.
 *
 * <p>Deux voies de consommation :</p>
 * <ul>
 *   <li>{@link #consumeForTurn} (F-115) — <b>fenêtre temporelle</b> : tous les dépôts non lus du
 *       terminal. C'est le comportement historique, conservé pour les envois <b>sans</b> pièces
 *       jointes désignées (rétrocompat stricte).</li>
 *   <li>{@link #consumeForMessage} (F-169 / SF-169-02) — <b>désignation explicite</b> : exactement les
 *       dépôts dont l'envoi a donné l'identifiant, filtrés {@code (user_id, workspace_id)} et non
 *       consommés. Puis {@link #linkToMessage} pose {@code message_id} une fois le message persisté :
 *       le lien fichier ↔ message devient durable et se retrouve au rechargement.</li>
 * </ul>
 *
 * <p>Les dépôts sont marqués <b>consommés</b> à la lecture : un tour suivant ne les reverra pas. Un
 * dépôt dont l'écriture a échoué n'a jamais créé de ligne (SF-115-01) : il ne pollue pas la consigne.</p>
 */
@Service
public class DepositConsumptionService {

    private final AtelierDepositedFileRepository repository;

    public DepositConsumptionService(AtelierDepositedFileRepository repository) {
        this.repository = repository;
    }

    /**
     * Consomme les dépôts non lus d'un terminal (F-115, <b>fenêtre temporelle</b>) et rend la
     * <b>note de consigne</b> à ajouter au tour, ou {@code null} s'il n'y en a aucun. Isolation
     * stricte : filtre {@code (user_id, workspace_id)}.
     *
     * @return la note (chemins seulement), ou {@code null}
     */
    @Transactional
    public String consumeForTurn(UUID userId, UUID workspaceId) {
        List<AtelierDepositedFile> pending = repository
                .findByUserIdAndWorkspaceIdAndConsumedAtIsNullOrderByCreatedAtAsc(userId, workspaceId);
        if (pending.isEmpty()) {
            return null;
        }
        markConsumed(pending);
        return note(pending);
    }

    /**
     * Consomme <b>exactement</b> les dépôts désignés (F-169 / SF-169-02), filtrés
     * {@code (user_id, workspace_id)} et non consommés — pièces jointes attachées à ce message. Rend
     * la note de consigne (chemins), la liste des pièces jointes (chemin + taille) pour le rendu, et
     * les identifiants réellement retenus (pour {@link #linkToMessage} après persistance du message).
     *
     * <p>Un id d'autrui, d'un autre workspace, inconnu ou déjà consommé est <b>ignoré</b> (jamais
     * remonté par la requête) : il n'est ni attaché, ni consommé.</p>
     *
     * @return l'issue ; {@link MessageDeposits#isEmpty()} vrai si aucun dépôt désigné n'était valide
     */
    @Transactional
    public MessageDeposits consumeForMessage(UUID userId, UUID workspaceId, Collection<UUID> depositIds) {
        if (depositIds == null || depositIds.isEmpty()) {
            return MessageDeposits.EMPTY;
        }
        List<AtelierDepositedFile> designated = repository
                .findByUserIdAndWorkspaceIdAndIdInAndConsumedAtIsNullOrderByCreatedAtAsc(
                        userId, workspaceId, depositIds);
        if (designated.isEmpty()) {
            return MessageDeposits.EMPTY;
        }
        markConsumed(designated);
        List<UUID> ids = designated.stream().map(AtelierDepositedFile::getId).toList();
        List<AtelierAttachedFile> files = designated.stream().map(AtelierAttachedFile::of).toList();
        return new MessageDeposits(note(designated), files, ids);
    }

    /**
     * Pose {@code message_id} sur exactement les dépôts désignés (F-169 / SF-169-02), une fois le
     * message utilisateur persisté. Isolation stricte : filtre {@code (user_id, workspace_id)} — seuls
     * les dépôts déjà résolus par {@link #consumeForMessage} sont concernés. Sans effet si la liste
     * est vide.
     */
    @Transactional
    public void linkToMessage(UUID userId, UUID workspaceId, Collection<UUID> depositIds, UUID messageId) {
        if (depositIds == null || depositIds.isEmpty() || messageId == null) {
            return;
        }
        List<AtelierDepositedFile> designated = repository.findAllById(depositIds).stream()
                .filter(file -> userId.equals(file.getUserId()) && workspaceId.equals(file.getWorkspaceId()))
                .toList();
        if (designated.isEmpty()) {
            return;
        }
        for (AtelierDepositedFile file : designated) {
            file.setMessageId(messageId);
        }
        repository.saveAll(designated);
    }

    /**
     * Les pièces jointes de chaque message donné (F-169 / SF-169-02), pour le rendu à l'historique.
     * Isolation stricte {@code (user_id, workspace_id)}. Un message sans pièce jointe est absent de la
     * carte (l'appelant rend une liste vide).
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<AtelierAttachedFile>> attachedFilesByMessage(
            UUID userId, UUID workspaceId, Collection<UUID> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        return repository
                .findByUserIdAndWorkspaceIdAndMessageIdInOrderByCreatedAtAsc(userId, workspaceId, messageIds)
                .stream()
                .collect(Collectors.groupingBy(AtelierDepositedFile::getMessageId,
                        Collectors.mapping(AtelierAttachedFile::of, Collectors.toList())));
    }

    private void markConsumed(List<AtelierDepositedFile> files) {
        OffsetDateTime now = OffsetDateTime.now();
        for (AtelierDepositedFile file : files) {
            file.setConsumedAt(now);
        }
        repository.saveAll(files);
    }

    private static String note(List<AtelierDepositedFile> files) {
        String list = files.stream()
                .map(file -> "- " + file.getPath() + (isVideo(file.getPath()) ? VIDEO_HINT : ""))
                .collect(Collectors.joining("\n"));
        String note = "Fichiers déposés dans ce terminal depuis le dernier tour "
                + "(lis-les avec read_file si tu en as besoin ; le contenu n'est pas inclus ici) :\n"
                + list;
        // F-190 / SF-190-04 : une transcription déposée avec la vidéo se donne à video_images.
        boolean video = files.stream().anyMatch(file -> isVideo(file.getPath()));
        boolean transcript = files.stream().anyMatch(file -> isTranscript(file.getPath()));
        return video && transcript ? note + TRANSCRIPT_HINT : note;
    }

    /**
     * F-190 / SF-190-04 — <b>l'agent sait qu'il peut regarder</b>. Sans cette ligne, il cherchait
     * {@code ffmpeg}, ne le trouvait pas et concluait qu'il ne pouvait pas voir la vidéo (poste
     * EDENRED, 2026-10-11). La ligne vit dans la notice du MESSAGE, jamais dans la consigne système :
     * elle dépend du dépôt (règle du préfixe stable).
     */
    static final String VIDEO_HINT = " (vidéo : read_file ne la lit pas — regarde-la avec "
            + "video_images puis video_images_status ; ffmpeg est rapatrié tout seul)";
    static final String TRANSCRIPT_HINT = "\nUne transcription a été déposée avec la vidéo : passe-la "
            + "en « transcript » à video_images pour avoir, à côté de chaque image, ce qui se disait.";

    private static final java.util.Set<String> VIDEO_EXTENSIONS =
            java.util.Set.of(".mp4", ".mov", ".m4v", ".webm", ".mkv", ".avi");
    private static final java.util.Set<String> TRANSCRIPT_EXTENSIONS =
            java.util.Set.of(".vtt", ".srt", ".docx", ".txt");

    static boolean isVideo(String path) {
        return VIDEO_EXTENSIONS.contains(extension(path));
    }

    static boolean isTranscript(String path) {
        return TRANSCRIPT_EXTENSIONS.contains(extension(path));
    }

    private static String extension(String path) {
        if (path == null) {
            return "";
        }
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Issue de {@link #consumeForMessage} : la note de consigne (ou {@code null}), les pièces jointes
     * pour le rendu, et les identifiants retenus à lier au message.
     */
    public record MessageDeposits(String note, List<AtelierAttachedFile> files, List<UUID> depositIds) {

        static final MessageDeposits EMPTY = new MessageDeposits(null, List.of(), List.of());

        public boolean isEmpty() {
            return depositIds.isEmpty();
        }
    }
}
