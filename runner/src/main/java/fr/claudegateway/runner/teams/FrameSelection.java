package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;

/**
 * <b>Le tri qui rend la fonctionnalité finançable</b> (F-90 / SF-90-01).
 *
 * <p>Une heure de réunion à intervalle fixe donne 3 600 images. Aux changements de plan puis
 * dédoublonnées, elle en donne <b>20 à 60</b>. <b>Chaque image analysée se paie</b> : ce n'est donc
 * pas une optimisation de confort, c'est ce qui permet d'offrir la chose.</p>
 *
 * <p>Deux gestes, dans cet ordre :</p>
 * <ol>
 *   <li><b>Le dédoublonnage.</b> Le filtre de scène franchit son seuil plusieurs fois de suite sur
 *       une transition animée ; l'empreinte perceptuelle rabat cette grappe sur une image.
 *       Comparaison à la <b>dernière image gardée</b>, jamais à toutes : une diapositive qui
 *       <b>revient</b> après un détour est un moment, pas un doublon — et elle doit ressortir.</li>
 *   <li><b>Le plafond.</b> Au-delà de {@value #MAX_FRAMES}, on garde les plus <b>espacées dans le
 *       temps</b>, jamais les premières : tronquer à la soixantième rendrait un compte rendu qui
 *       s'arrête au tiers de la réunion <i>en ayant l'air complet</i>. Et le nombre écarté est
 *       <b>dit</b>.</li>
 * </ol>
 */
public final class FrameSelection {

    /**
     * <b>Le plafond de remontée en images</b> (D4 : un plafond annoncé, jamais silencieux). C'est le
     * haut de la fourchette mesurée au cadrage — et exactement la borne que le bloc moment de F-89
     * accepte ({@code TeamsBlockCards.MAX_MOMENTS}).
     */
    public static final int MAX_FRAMES = 60;

    private FrameSelection() {
    }

    /**
     * Trie les images sorties par {@code ffmpeg}.
     *
     * @param extracted les images brutes, dans l'ordre du temps
     * @param carried   les manques déjà constatés en amont, repris tels quels
     * @return les images retenues <b>et</b> ce qui a été écarté
     */
    public static FramesHarvest select(List<SceneFrame> extracted, List<TeamsGap> carried) {
        List<SceneFrame> source = extracted == null ? List.of() : extracted;
        List<TeamsGap> gaps = new ArrayList<>(carried == null ? List.of() : carried);

        List<SceneFrame> readable = new ArrayList<>();
        int unreadable = 0;
        for (SceneFrame frame : source) {
            OptionalLong fingerprint = FrameFingerprint.of(frame.file());
            if (fingerprint.isEmpty()) {
                unreadable++;
                continue;
            }
            readable.add(frame.withFingerprint(fingerprint.getAsLong()));
        }
        if (unreadable > 0) {
            gaps.add(new TeamsGap(TeamsGapKind.FRAME_UNREADABLE, "extraction des images",
                    "images sorties par ffmpeg mais illisibles — elles ne sont pas dans le compte "
                            + "rendu", unreadable));
        }

        List<SceneFrame> distinct = deduplicate(readable);
        int duplicates = readable.size() - distinct.size();

        int overCap = Math.max(0, distinct.size() - MAX_FRAMES);
        List<SceneFrame> kept = overCap == 0 ? distinct : spread(distinct, MAX_FRAMES);
        if (overCap > 0) {
            gaps.add(new TeamsGap(TeamsGapKind.CAP_REACHED, "extraction des images",
                    "plafond de " + MAX_FRAMES + " images : les images gardées sont réparties sur "
                            + "toute la durée, pas prises au début", overCap));
        }
        if (source.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.NO_SCENE_CHANGE, "extraction des images",
                    "aucun changement de plan détecté au seuil demandé — la vidéo montre "
                            + "peut-être un plan fixe, ou le seuil est trop haut"));
        }
        return new FramesHarvest(kept, gaps, source.size(), duplicates, unreadable, overCap);
    }

    /**
     * Écarte ce qui n'apprend rien de nouveau. <b>Un curseur qui bouge n'est pas un nouveau plan</b>
     * : à 9×8 en niveaux de gris, il ne pèse aucun bit.
     */
    static List<SceneFrame> deduplicate(List<SceneFrame> frames) {
        List<SceneFrame> kept = new ArrayList<>();
        Long previous = null;
        for (SceneFrame frame : frames) {
            if (previous != null && FrameFingerprint.sameImage(previous, frame.fingerprint())) {
                continue;
            }
            kept.add(frame);
            previous = frame.fingerprint();
        }
        return kept;
    }

    /**
     * Garde {@code max} images <b>réparties sur toute la durée</b>, la première et la dernière
     * comprises. Un compte rendu tronqué au début aurait l'air complet — c'est précisément ce que ce
     * volet interdit.
     */
    static List<SceneFrame> spread(List<SceneFrame> frames, int max) {
        if (frames.size() <= max) {
            return List.copyOf(frames);
        }
        if (max <= 1) {
            return List.of(frames.get(0));
        }
        List<SceneFrame> kept = new ArrayList<>(max);
        // Répartition régulière par index : les bornes sont toujours prises, le reste est étalé.
        for (int index = 0; index < max; index++) {
            int source = (int) Math.round(index * (frames.size() - 1d) / (max - 1d));
            SceneFrame frame = frames.get(source);
            if (kept.isEmpty() || kept.get(kept.size() - 1) != frame) {
                kept.add(frame);
            }
        }
        kept.sort(Comparator.comparingDouble(SceneFrame::offsetSeconds));
        return List.copyOf(kept);
    }
}
