package fr.claudegateway.atelier.veille;

import java.util.List;

import fr.claudegateway.atelier.Workspace;

/**
 * <b>Quelque chose a bougé</b> (F-230 / SF-230-02 → SF-230-03) : appelé après un passage {@code CHANGED}, avec le
 * diff <b>seul</b>. Sans écouteur, le changement est seulement journalisé.
 */
public interface JarvisWatchChangeListener {

    /**
     * @param watch     la veille, relue sous son propriétaire
     * @param workspace son sujet, relu sous le même propriétaire
     * @param run       le passage qui a vu le changement
     * @param changes   les changements (jamais vide)
     */
    void onChange(JarvisWatch watch, Workspace workspace, JarvisWatchRun run, List<JarvisWatchDiff.Change> changes);
}
