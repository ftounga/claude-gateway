package fr.claudegateway.runner.teams;

import java.util.List;

/**
 * <b>Les moments, et ce qui n'a pas pu être aligné</b> (F-90 / SF-90-02).
 *
 * <p>Les deux ensemble, toujours — <i>un trou se voit, un trou silencieux ne se voit jamais</i>. Et
 * l'<b>hypothèse de temps</b> avec eux : un compte rendu de moments ne vaut que si l'on sait sur
 * quelle origine il a été bâti. C'est ce qui fera voir l'écart, le jour du premier branchement sur un
 * vrai Teams, au lieu de le cacher.</p>
 *
 * @param moments        les moments, dans l'ordre du temps
 * @param gaps           ce qui n'a pas pu être lu ou aligné ; vide veut dire « rien à signaler »
 * @param timeline       l'origine du temps retenue, et d'où elle vient
 * @param orphanCues     paroles prononcées avant la première image : rattachées à rien
 * @param silentFrames   images devant lesquelles personne n'a parlé : pas de moment
 * @param framesAligned  images qui ont donné un moment
 */
public record MomentsAlignment(List<TeamsMoment> moments, List<TeamsGap> gaps,
        MomentTimeline timeline, int orphanCues, int silentFrames, int framesAligned) {

    public MomentsAlignment {
        moments = moments == null ? List.of() : List.copyOf(moments);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        timeline = timeline == null ? MomentTimeline.unknown() : timeline;
    }

    /**
     * La phrase à citer : le compte, l'hypothèse de temps, et ce qui n'a pas pu être rapproché.
     * C'est ce que l'agent doit répéter — un compte rendu qui tait un trou est un compte rendu faux.
     */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append(moments.size())
                .append(moments.size() > 1 ? " moments alignés" : " moment aligné")
                .append(" : chaque phrase est posée à côté de l'image qui était à l'écran pendant "
                        + "qu'elle se disait.");
        if (silentFrames > 0) {
            text.append(' ').append(silentFrames)
                    .append(silentFrames > 1
                            ? " images retenues ne deviennent pas des moments (personne n'y parlait)."
                            : " image retenue ne devient pas un moment (personne n'y parlait).");
        }
        if (orphanCues > 0) {
            text.append(' ').append(orphanCues)
                    .append(orphanCues > 1 ? " répliques ont été prononcées" : " réplique a été "
                            + "prononcée")
                    .append(" avant la première image : aucune capture ne leur correspond.");
        }
        text.append(' ').append(timeline.describe());
        return text.toString();
    }
}
