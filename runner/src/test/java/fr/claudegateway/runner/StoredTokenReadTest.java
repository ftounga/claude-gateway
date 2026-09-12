package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Lecture de la réponse d'appairage (SF-48-04) — le test qui manquait.
 *
 * <p>Entre le 2026-09-10 et le 2026-09-12, <b>aucun appairage de machine neuve n'a fonctionné</b> :
 * SF-48-01 avait renommé {@code workspaceId} en {@code hostId} côté gateway, le runner attendait
 * toujours l'ancien nom, et son mapper strict refusait le champ inconnu. La gateway créait le jeton,
 * le runner ne savait pas le lire, et l'utilisateur lisait « Réponse d'appairage illisible » devant
 * un poste que l'écran montrait pourtant comme connu.</p>
 *
 * <p>Rien ne l'avait vu parce que <b>rien n'exerçait ce chemin</b>. Ces cas-là existent pour que la
 * prochaine dérive du contrat se voie à la compilation des tests, et non chez un client.</p>
 */
class StoredTokenReadTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** La forme exacte que rend {@code PairResponse} aujourd'hui. */
    private static final String REPONSE_GATEWAY = """
            {"token":"opaque","hostId":"82cc700e-f632-431d-b025-296cd7a9b390",\
            "expiresAt":"2026-09-12T10:05:32Z"}""";

    @Test
    @DisplayName("la réponse d'appairage de la gateway est lue, jeton et poste compris")
    void litLaReponseCourante() throws IOException {
        StoredToken token = mapper.readValue(REPONSE_GATEWAY, StoredToken.class);

        assertEquals("opaque", token.token());
        assertEquals("82cc700e-f632-431d-b025-296cd7a9b390", token.hostId().toString());
        assertNotNull(token.expiresAt());
    }

    @Test
    @DisplayName("un champ inconnu n'empêche plus de lire le jeton")
    void ignoreLesChampsInconnus() throws IOException {
        String plusRecente = """
                {"token":"opaque","hostId":"82cc700e-f632-431d-b025-296cd7a9b390",\
                "expiresAt":"2026-09-12T10:05:32Z","quelqueChoseDeNeuf":"ajouté demain"}""";

        // Un runner installé chez un client vit plus longtemps que la gateway qu'il a connue :
        // un champ ajouté plus tard doit être ignoré, jamais faire échouer l'appairage.
        assertEquals("opaque", mapper.readValue(plusRecente, StoredToken.class).token());
    }

    @Test
    @DisplayName("un jeton persisté sous l'ancienne forme reste lisible")
    void litLAncienneForme() throws IOException {
        String avantF48 = """
                {"token":"opaque","workspaceId":"82cc700e-f632-431d-b025-296cd7a9b390",\
                "expiresAt":"2026-09-12T10:05:32Z"}""";

        // Les postes appairés avant le 2026-09-10 portent ce fichier sur leur disque : leur jeton
        // reste valide, et seul l'identifiant de poste — que le runner ne lit nulle part — manque.
        StoredToken token = mapper.readValue(avantF48, StoredToken.class);
        assertEquals("opaque", token.token());
        assertNull(token.hostId());
    }

    @Test
    @DisplayName("un corps qui n'est pas du JSON est refusé — page d'un portail captif, par exemple")
    void refuseUnCorpsNonJson() {
        assertThrows(IOException.class,
                () -> mapper.readValue("<html>Accès bloqué</html>", StoredToken.class));
    }
}
