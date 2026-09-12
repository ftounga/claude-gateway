package fr.claudegateway.runner.teams;

import java.util.List;

/**
 * <b>Les images retenues, et ce qui n'a pas pu être fait</b> (F-90 / SF-90-01).
 *
 * <p>Les deux, toujours ensemble, et c'est structurel : on ne <b>peut pas</b> rendre une liste
 * d'images sans dire à côté ce qui manque. C'est la règle qui prime sur toutes les autres dans ce
 * volet — <i>un adaptateur cassé qui rend la moitié des messages est pire qu'un adaptateur qui
 * refuse</i>. Une extraction qui perd la moitié d'une réunion sans le dire produirait un compte
 * rendu plausible et faux, sur lequel on déciderait.</p>
 *
 * @param frames    les images retenues, dans l'ordre du temps
 * @param gaps      ce qui n'a pas pu être lu ou fait ; vide veut dire « rien à signaler », et il est
 *                  alors écrit vide, jamais absent
 * @param examined  nombre d'images qu'{@code ffmpeg} a sorties, avant tri
 * @param duplicates nombre d'images écartées parce qu'elles n'apprenaient rien de nouveau
 * @param unreadable nombre d'images sorties mais illisibles
 * @param overCap   nombre d'images écartées par le plafond
 */
public record FramesHarvest(List<SceneFrame> frames, List<TeamsGap> gaps, int examined,
        int duplicates, int unreadable, int overCap) {

    public FramesHarvest {
        frames = frames == null ? List.of() : List.copyOf(frames);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }

    /** Rien du tout, avec la raison. */
    public static FramesHarvest nothing(List<TeamsGap> gaps) {
        return new FramesHarvest(List.of(), gaps, 0, 0, 0, 0);
    }

    /**
     * La phrase à citer : le compte, et ce qui a été écarté <b>et pourquoi</b>. Le tri est ce qui
     * rend la fonctionnalité finançable ; il n'a pas à être caché.
     */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append(frames.size()).append(frames.size() > 1 ? " images retenues" : " image retenue");
        text.append(" sur ").append(examined)
                .append(examined > 1 ? " changements de plan" : " changement de plan")
                .append(" détectés");
        if (duplicates > 0) {
            text.append(" — ").append(duplicates)
                    .append(duplicates > 1 ? " écartées parce qu'elles n'apprenaient rien de nouveau"
                            : " écartée parce qu'elle n'apprenait rien de nouveau");
        }
        if (unreadable > 0) {
            text.append(" — ").append(unreadable)
                    .append(unreadable > 1 ? " illisibles" : " illisible");
        }
        if (overCap > 0) {
            text.append(" — ").append(overCap).append(" au-delà du plafond de ")
                    .append(FrameSelection.MAX_FRAMES);
        }
        text.append('.');
        return text.toString();
    }
}
