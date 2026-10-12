package fr.claudegateway.runner;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Choisit le jeton avec lequel le runner se connecte (F-194 / SF-194-03) : celui du disque, ou
 * celui qu'un {@code --code} permet d'obtenir.
 *
 * <p><b>Un code fourni l'emporte sur le jeton stocké.</b> Avant, le jeton du disque était réutilisé
 * dès qu'il existait et le code ignoré en silence : la machine EDENRED, appairée par erreur avec le
 * code du poste FREE, ne pouvait pas être réparée en relançant avec le bon code — il fallait
 * d'abord effacer {@code token.json} à la main.</p>
 *
 * <p><b>Sauf si ce code est refusé</b> alors qu'un jeton existe : le lanceur relance le runner avec
 * ses arguments d'origine (après une mise à jour, par exemple), et un code déjà consommé ne doit pas
 * arrêter un poste qui est appairé. Le refus est dit, et le jeton du disque sert.</p>
 *
 * <p>Le runner <b>nomme son poste</b> à chaque fois : c'est ce qui aurait montré, le 12/10, que la
 * machine EDENRED venait de s'appairer comme « FREE ».</p>
 */
final class TokenAcquisition {

    /** Échange le code contre un jeton et le stocke ; lève {@link PairingClient.PairingException}. */
    @FunctionalInterface
    interface Pairer {
        StoredToken pair();
    }

    /** Statut d'un code inconnu, expiré ou déjà utilisé ({@code pairing_invalid}). */
    static final int CODE_REFUSED = 401;

    private final Console console;

    TokenAcquisition(Console console) {
        this.console = console;
    }

    /**
     * Le jeton à utiliser, ou vide quand il n'y a ni jeton stocké ni code — l'appelant dit alors ce
     * qui manque.
     */
    Optional<StoredToken> obtain(Optional<StoredToken> stored, boolean codeGiven, Path tokenFile,
            Pairer pairer) {
        if (!codeGiven) {
            stored.ifPresent(token -> {
                console.info("Jeton runner réutilisé (" + tokenFile + ").");
                announceHost(token);
            });
            return stored;
        }
        if (stored.isPresent()) {
            console.info("Un code d'appairage est fourni : il remplace le jeton stocké.");
        }
        try {
            StoredToken paired = pairer.pair();
            announceHost(paired);
            announceReplacement(paired);
            return Optional.of(paired);
        } catch (PairingClient.PairingException e) {
            if (stored.isEmpty() || e.httpStatus() != CODE_REFUSED) {
                throw e;
            }
            console.warn(e.getMessage() + " — le jeton stocké est conservé (" + tokenFile + ").");
            announceHost(stored.get());
            return stored;
        }
    }

    private void announceHost(StoredToken token) {
        if (token.hostName() != null && !token.hostName().isBlank()) {
            console.info("Poste     : « " + token.hostName() + " »");
        }
    }

    private void announceReplacement(StoredToken token) {
        String previous = token.previousRootName();
        if (previous == null || previous.isBlank()) {
            return;
        }
        String host = token.hostName() == null ? "Ce poste" : "Le poste « " + token.hostName() + " »";
        console.warn(host + " était déclaré sur la racine « " + previous + " » : cette machine "
                + "prend sa place. Si ce n'est pas le bon poste, générez le code depuis la carte du "
                + "bon poste (Postes > … > Réappairer ce poste…), puis relancez avec --code.");
    }
}
