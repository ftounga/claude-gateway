/**
 * Formats acceptés par le serveur, publiés par `GET /api/file-formats` (F-85 / SF-85-01).
 *
 * <p>Ces listes ne sont **jamais** recopiées dans le frontend : elles vivent en configuration
 * serveur (`app.ocr.allowed-types`, `app.upload.allowed-types`) et l'écran les lit. C'est ce qui
 * garantit qu'un format ajouté au serveur apparaît dans le sélecteur sans toucher à l'écran.
 */
export interface FileFormatProfile {
  /** Types MIME acceptés, en minuscules, dans l'ordre de la configuration serveur. */
  mediaTypes: string[];
  /** Taille maximale d'un fichier, en octets. */
  maxBytes: number;
}

/** Les chemins de dépôt dont la liste blanche vit côté serveur. */
export interface FileFormats {
  /** Dépôt d'un document dans la bibliothèque (pipeline OCR). */
  documents: FileFormatProfile;
  /** Pièce jointe d'une conversation. */
  attachments: FileFormatProfile;
}

/** Nom d'un chemin de dépôt servi par le serveur. */
export type FileFormatChannel = keyof FileFormats;
