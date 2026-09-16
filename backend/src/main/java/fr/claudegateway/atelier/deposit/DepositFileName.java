package fr.claudegateway.atelier.deposit;

import org.springframework.http.HttpStatus;

/**
 * Assainit le nom d'un fichier déposé (F-115 / SF-115-01). Le nom vient du client : il ne doit
 * jamais permettre d'écrire ailleurs que sous {@code entrees/}. On garde le <b>basename</b> seul
 * (aucun séparateur, aucun {@code ..}), et le chemin final est composé par l'appelant en préfixant
 * {@code entrees/} — jamais l'inverse.
 */
public final class DepositFileName {

    /** Longueur maximale d'un nom de fichier (comme la colonne des noms de projet). */
    public static final int MAX_NAME_LENGTH = 255;

    private DepositFileName() {
    }

    /**
     * @param raw nom brut envoyé par le client
     * @return le basename assaini, garanti sans séparateur ni traversée
     * @throws WorkspaceDepositException (400 {@code invalid_name}) si le nom est vide, {@code .},
     *         {@code ..}, trop long, ou porte un octet nul / caractère de contrôle
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            throw invalid("Nom de fichier requis.");
        }
        // Windows envoie parfois des séparateurs antislash : on les ramène à '/' avant de couper.
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw invalid("Nom de fichier invalide.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw invalid("Nom de fichier trop long (255 caractères au plus).");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\0' || c < 0x20) {
                throw invalid("Nom de fichier invalide.");
            }
        }
        return name;
    }

    private static WorkspaceDepositException invalid(String message) {
        return new WorkspaceDepositException(HttpStatus.BAD_REQUEST, "invalid_name", message);
    }
}
