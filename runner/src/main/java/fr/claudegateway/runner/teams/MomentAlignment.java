package fr.claudegateway.runner.teams;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * <b>L'alignement — l'image EN VIGUEUR à {@code t}</b> (F-90 / SF-90-02).
 *
 * <h2>La règle, en une phrase</h2>
 *
 * <blockquote><b>Une phrase prononcée à {@code t} va avec l'image en vigueur à {@code t}</b> — la
 * dernière affichée <b>avant</b> {@code t} — <b>et surtout pas la suivante.</b></blockquote>
 *
 * <p>Prendre la suivante, ou « la plus proche dans le temps », serait plus indulgent et <b>faux une
 * fois sur deux</b> : on collerait à « je vous montre le planning » l'image de ce qui a été affiché
 * <b>après</b>. Le coût de l'erreur n'est pas symétrique — une image postérieure fait dire au compte
 * rendu le contraire de ce qui s'est passé. <b>Une image mal alignée est pire qu'une image
 * absente.</b></p>
 *
 * <h2>Un moment par image, pas un moment par réplique</h2>
 *
 * <p>Une réunion d'une heure porte des centaines de répliques et 20 à 60 images. <i>« Montre-moi ce
 * qu'il y avait à l'écran »</i> est une question sur les <b>écrans</b>, pas sur les phrases. Chaque
 * image retenue donne donc <b>au plus un</b> moment : la première parole de son règne. Les autres
 * sont <b>comptées et dites</b>, jamais concaténées — la borne de longueur du bloc les ferait
 * tronquer, et <i>une phrase tronquée peut dire le contraire de la phrase</i>.</p>
 *
 * <h2>Ce qui n'est rattaché à rien est nommé</h2>
 *
 * <p>Une parole prononcée <b>avant la première image</b> n'a aucune image en vigueur. La rattacher à
 * la première serait la même faute, dans l'autre sens. Elle est <b>comptée et nommée</b>. De même,
 * une image devant laquelle personne n'a parlé <b>ne devient pas un moment</b> — un moment sans
 * citation n'est pas un moment — et elle est comptée.</p>
 */
public final class MomentAlignment {

    /**
     * Le nombre maximal de moments produits. C'est <b>exactement</b> la borne que le bloc moment de
     * F-89 accepte ({@code TeamsBlockCards.MAX_MOMENTS}) : produire au-delà ferait refuser le bloc
     * entier à l'émission, donc perdre le compte rendu.
     */
    public static final int MAX_MOMENTS = FrameSelection.MAX_FRAMES;

    private MomentAlignment() {
    }

    /**
     * Rapproche les images et les répliques.
     *
     * @param frames   images retenues (SF-90-01), dans n'importe quel ordre — elles seront triées
     * @param cues     répliques de la transcription (F-88), dans n'importe quel ordre
     * @param timeline l'origine du temps de la vidéo
     * @param carried  les manques déjà constatés en amont, repris tels quels
     * @return les moments <b>et</b> ce qui n'a pas pu être aligné
     * @throws OriginUnknownException quand l'origine du temps n'est pas connue — <b>on ne devine
     *                                pas</b>, parce qu'un alignement faux est silencieusement faux
     */
    public static MomentsAlignment align(List<SceneFrame> frames, List<TeamsTranscriptCue> cues,
            MomentTimeline timeline, List<TeamsGap> carried) {
        MomentTimeline effective = timeline == null ? MomentTimeline.unknown() : timeline;
        if (!effective.isKnown()) {
            throw new OriginUnknownException();
        }
        List<TeamsGap> gaps = new ArrayList<>(carried == null ? List.of() : carried);

        List<SceneFrame> ordered = new ArrayList<>(frames == null ? List.of() : frames);
        ordered.sort(Comparator.comparingDouble(SceneFrame::offsetSeconds));

        List<TeamsTranscriptCue> readable = new ArrayList<>();
        int unusableCues = 0;
        for (TeamsTranscriptCue cue : cues == null ? List.<TeamsTranscriptCue>of() : cues) {
            if (cue == null || !cue.isReadable()) {
                unusableCues++;
                continue;
            }
            readable.add(cue);
        }
        readable.sort(Comparator.comparing(TeamsTranscriptCue::at));
        if (unusableCues > 0) {
            gaps.add(new TeamsGap(TeamsGapKind.MISSING_FIELD, "alignement",
                    "répliques sans horodatage ou sans texte — elles ne sont rapprochées d'aucune "
                            + "image", unusableCues));
        }

        if (ordered.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.NO_SCENE_CHANGE, "alignement",
                    "aucune image à rapprocher de la transcription"));
            return new MomentsAlignment(List.of(), gaps, effective, readable.size(), 0, 0);
        }
        if (readable.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, "alignement",
                    "aucune réplique de transcription à rapprocher des images — ouvrez la "
                            + "transcription dans Teams, puis redemandez"));
            return new MomentsAlignment(List.of(), gaps, effective, 0, ordered.size(), 0);
        }

        // Les instants absolus des images, une fois pour toutes.
        List<Instant> frameInstants = new ArrayList<>(ordered.size());
        for (SceneFrame frame : ordered) {
            frameInstants.add(effective.at(frame.offsetSeconds()));
        }

        // Une parole prononcée AVANT la première image n'a aucune image en vigueur.
        int orphans = 0;
        Instant firstFrame = frameInstants.get(0);
        for (TeamsTranscriptCue cue : readable) {
            if (cue.at().isBefore(firstFrame)) {
                orphans++;
            }
        }
        if (orphans > 0) {
            gaps.add(new TeamsGap(TeamsGapKind.SPOKEN_BEFORE_FIRST_FRAME, "alignement",
                    "paroles prononcées avant la première image retenue : aucune image n'était en "
                            + "vigueur, elles ne sont rapprochées d'aucune capture", orphans));
        }

        List<TeamsMoment> moments = new ArrayList<>();
        int silentFrames = 0;
        for (int index = 0; index < ordered.size(); index++) {
            Instant reignStart = frameInstants.get(index);
            // Le règne d'une image va de son instant à celui de la suivante ; la dernière règne
            // jusqu'à la fin — c'est ce qui était encore affiché.
            Instant reignEnd = index + 1 < frameInstants.size() ? frameInstants.get(index + 1) : null;
            List<TeamsTranscriptCue> spoken = duringReign(readable, reignStart, reignEnd);
            if (spoken.isEmpty()) {
                // Pas de citation, donc pas de moment : le bloc de F-89 le refuserait, et il aurait
                // raison — une image sans parole n'apprend pas ce qui se disait.
                silentFrames++;
                continue;
            }
            TeamsTranscriptCue first = spoken.get(0);
            moments.add(new TeamsMoment(first.at(), ordered.get(index).offsetSeconds(),
                    first.text(), first.speakerDisplayName(), ordered.get(index).file(),
                    spoken.size() - 1));
        }
        if (silentFrames > 0) {
            gaps.add(new TeamsGap(TeamsGapKind.FRAME_WITHOUT_SPEECH, "alignement",
                    "images retenues devant lesquelles personne n'a parlé : elles ne deviennent pas "
                            + "des moments", silentFrames));
        }
        if (moments.size() > MAX_MOMENTS) {
            // Ne devrait pas arriver : SF-90-01 plafonne déjà les images à la même borne. La garde
            // reste, parce qu'un bloc refusé à l'émission ferait perdre TOUT le compte rendu.
            gaps.add(new TeamsGap(TeamsGapKind.CAP_REACHED, "alignement",
                    "plafond de " + MAX_MOMENTS + " moments", moments.size() - MAX_MOMENTS));
            moments = new ArrayList<>(moments.subList(0, MAX_MOMENTS));
        }
        return new MomentsAlignment(moments, gaps, effective, orphans, silentFrames,
                moments.size());
    }

    /**
     * Les répliques dont le début tombe dans le règne de cette image.
     *
     * <p><b>La borne de gauche est inclusive</b> : une parole prononcée exactement à l'instant d'une
     * image appartient à <b>cette</b> image — l'image est déjà affichée quand la phrase commence.
     * <b>La borne de droite est exclusive</b> : dès que l'image suivante paraît, c'est elle qui est
     * en vigueur.</p>
     */
    private static List<TeamsTranscriptCue> duringReign(List<TeamsTranscriptCue> cues,
            Instant start, Instant end) {
        List<TeamsTranscriptCue> spoken = new ArrayList<>();
        for (TeamsTranscriptCue cue : cues) {
            if (cue.at().isBefore(start)) {
                continue;
            }
            if (end != null && !cue.at().isBefore(end)) {
                continue;
            }
            spoken.add(cue);
        }
        return spoken;
    }

    /**
     * <b>L'origine du temps n'est pas connue, et on refuse de deviner</b> (F-90 / SF-90-02).
     *
     * <p>Sans elle, chaque horodatage produit aurait l'air juste et serait faux. Le refus nomme les
     * <b>deux</b> façons de la lever.</p>
     */
    public static class OriginUnknownException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public OriginUnknownException() {
            super("Je ne sais pas à quel instant commence cette vidéo, donc je ne peux rapprocher "
                    + "aucune image d'aucune phrase : tout ce que je rendrais aurait l'air juste et "
                    + "serait faux.");
        }

        /** Les deux façons de lever le manque. */
        public String remedy() {
            return "Donnez-moi le début de l'enregistrement (« video_started_at »), ou désignez la "
                    + "réunion (« meeting_id ») pour que je prenne son début tel que Teams l'a "
                    + "servi.";
        }

        public String sentence() {
            return getMessage() + " " + remedy();
        }
    }
}
