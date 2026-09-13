package fr.claudegateway.radar;

import java.util.Objects;
import java.util.UUID;

/**
 * Le périmètre du Radar (F-99, cadrage §3) : <b>un utilisateur et un de ses postes</b>.
 *
 * <p>Le Radar d'EDENRED ne voit jamais celui de CAGIP, y compris chez le même utilisateur. Tout le
 * registre prend ce couple, et seulement lui : il n'existe aucune méthode qui accepte un utilisateur
 * sans poste, ni un poste sans utilisateur. Un {@code RadarScope} ne se fabrique à partir d'une requête
 * que par {@link RadarScopeResolver}, qui vérifie la possession du poste.</p>
 */
public record RadarScope(UUID userId, UUID hostId) {

    public RadarScope {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(hostId, "hostId");
    }
}
