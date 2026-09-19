package fr.claudegateway.runner.teams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * <b>Le verrou de profil du Chrome managé</b> (F-122 / SF-122-08).
 *
 * <p>Chrome pose, dans son {@code --user-data-dir}, des fichiers « singleton » qui garantissent
 * qu'une seule instance utilise le profil : {@code SingletonLock}, {@code SingletonSocket},
 * {@code SingletonCookie}. À une fermeture <b>propre</b>, Chrome les retire. À une fermeture
 * <b>sale</b> (fenêtre tuée, process interrompu, poste mis en veille), ils <b>restent</b>. Un
 * nouveau Chrome lancé sur ce profil voit alors le verrou, tente de <b>passer la main</b> à une
 * instance qui n'existe plus, et <b>sort sans ouvrir le port de débogage</b> — d'où un
 * {@code browser_unreachable} qui persiste alors même qu'on a relancé.</p>
 *
 * <p>Cette classe <b>nettoie</b> ces fichiers résiduels avant une relance de récupération. Elle est
 * appelée <b>uniquement</b> quand le port ne répond pas (donc quand aucun Chrome ne tourne sur le
 * profil) : on ne retire jamais le verrou d'une instance vivante. <b>Best-effort strict</b> : un
 * fichier absent ou non supprimable n'interrompt jamais la récupération.</p>
 */
final class ChromeProfileLock {

    /** Les fichiers singleton posés par Chrome à la racine du profil. */
    static final List<String> SINGLETON_FILES =
            List.of("SingletonLock", "SingletonSocket", "SingletonCookie");

    private ChromeProfileLock() {
    }

    /**
     * Retire les fichiers singleton résiduels du profil. Ne lève jamais.
     *
     * @return {@code true} si au moins un fichier a été retiré (pour le diagnostic)
     */
    static boolean clearStale(Path profileDir, Consumer<String> say) {
        if (profileDir == null) {
            return false;
        }
        boolean cleared = false;
        for (String name : SINGLETON_FILES) {
            try {
                // deleteIfExists suit... non : on veut retirer le lien lui-même, pas sa cible. Files
                // .deleteIfExists sur un lien symbolique retire le LIEN (pas la cible) — c'est ce qu'on veut.
                if (Files.deleteIfExists(profileDir.resolve(name))) {
                    cleared = true;
                }
            } catch (Exception e) {
                // Best-effort strict : droits, verrou système, chemin illisible — on continue.
            }
        }
        if (cleared && say != null) {
            say.accept("Vigie : verrou de profil Chrome résiduel (fermeture précédente non propre) "
                    + "nettoyé avant relance.");
        }
        return cleared;
    }
}
