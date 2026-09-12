package fr.claudegateway.runner.teams;

/**
 * Une pièce jointe, <b>par référence</b> (F-87 / SF-87-01).
 *
 * <p>Le nom du type le dit : on porte de quoi <b>nommer</b> le fichier, jamais son contenu. Rien
 * n'est téléchargé par la liaison ; ce qui descend sur la machine le fait plus tard, explicitement,
 * et <b>reste</b> sur la machine (cadrage §7).</p>
 *
 * @param id          identifiant opaque
 * @param name        nom du fichier tel qu'il apparaît dans la conversation
 * @param contentType type déclaré, ou {@code ""} s'il n'a pas été donné
 * @param sizeBytes   taille en octets, ou {@code -1} si Teams ne l'a pas dite
 * @param sourceUrl   adresse côté Microsoft, ou {@code ""} — jamais suivie par l'adaptateur
 */
public record TeamsAttachmentRef(String id, String name, String contentType, long sizeBytes,
        String sourceUrl) {

    public TeamsAttachmentRef {
        id = id == null ? "" : id.strip();
        name = name == null ? "" : name.strip();
        contentType = contentType == null ? "" : contentType.strip();
        sizeBytes = sizeBytes < 0 ? -1 : sizeBytes;
        sourceUrl = sourceUrl == null ? "" : sourceUrl.strip();
    }
}
