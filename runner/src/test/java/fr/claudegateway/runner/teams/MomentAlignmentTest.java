package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * <b>L'alignement — l'image EN VIGUEUR à {@code t}</b> (F-90 / SF-90-02).
 *
 * <p>Ces tests portent <b>la</b> règle de la feature : <i>une phrase prononcée à {@code t} va avec
 * l'image en vigueur à {@code t}, et surtout pas la suivante</i>. C'est l'alignement qui fait la
 * valeur de F-90 — pas l'extraction.</p>
 *
 * <p><b>Limite écrite et non maquillée</b> : les répliques et les images sont <b>fabriquées</b>.
 * Nous n'avons aucun compte Teams de test. Ce qui est prouvé, c'est la règle et tous ses cas
 * limites ; ce qui ne l'est pas, c'est que les instants d'un vrai Teams tombent là où on les
 * suppose — raison pour laquelle l'origine du temps est un paramètre explicite et que l'hypothèse
 * retenue voyage avec le résultat.</p>
 */
class MomentAlignmentTest {

    private static final Instant DEBUT = Instant.parse("2026-09-12T14:00:00Z");
    private static final MomentTimeline FRISE = MomentTimeline.given(DEBUT);

    @Test
    void la_phrase_va_avec_limage_en_vigueur_et_surtout_pas_la_suivante() {
        // Le planning est affiché à 14:00:10 ; l'organigramme lui succède à 14:05:00.
        List<SceneFrame> images = List.of(image(10, "planning.jpg"), image(300, "organigramme.jpg"));
        // La phrase est prononcée à 14:02:00 : le PLANNING est à l'écran, pas l'organigramme.
        List<TeamsTranscriptCue> cues =
                List.of(cue(120, "Paul", "je vous montre le planning de migration"));

        MomentsAlignment aligned = MomentAlignment.align(images, cues, FRISE, List.of());

        assertEquals(1, aligned.moments().size());
        assertEquals("planning.jpg", aligned.moments().get(0).image().getFileName().toString(),
                "prendre l'image SUIVANTE ferait dire au compte rendu le contraire de ce qui s'est "
                        + "passé");
        assertEquals(10d, aligned.moments().get(0).offsetSeconds());
        assertEquals(DEBUT.plusSeconds(120), aligned.moments().get(0).at());
        assertEquals("Paul", aligned.moments().get(0).speaker());
    }

    @Test
    void une_phrase_a_linstant_exact_dune_image_va_avec_cette_image() {
        // L'image est déjà affichée quand la phrase commence : la borne de gauche est inclusive.
        List<SceneFrame> images = List.of(image(0, "premiere.jpg"), image(60, "seconde.jpg"));
        List<TeamsTranscriptCue> cues = List.of(cue(60, "Léa", "et voilà le budget"));

        MomentsAlignment aligned = MomentAlignment.align(images, cues, FRISE, List.of());

        assertEquals(1, aligned.moments().size());
        assertEquals("seconde.jpg", aligned.moments().get(0).image().getFileName().toString());
    }

    @Test
    void une_parole_avant_la_premiere_image_nest_rattachee_a_rien_et_cest_nomme() {
        List<SceneFrame> images = List.of(image(300, "planning.jpg"));
        List<TeamsTranscriptCue> cues = List.of(
                cue(10, "Paul", "bonjour à tous"),
                cue(20, "Léa", "on attend deux personnes"),
                cue(310, "Paul", "voici le planning"));

        MomentsAlignment aligned = MomentAlignment.align(images, cues, FRISE, List.of());

        assertEquals(1, aligned.moments().size());
        assertEquals(2, aligned.orphanCues());
        assertTrue(aligned.gaps().stream()
                .anyMatch(gap -> gap.kind() == TeamsGapKind.SPOKEN_BEFORE_FIRST_FRAME));
        assertTrue(aligned.describe().contains("avant la première image"));
    }

    @Test
    void une_image_sans_parole_ne_devient_pas_un_moment_et_elle_est_comptee() {
        // Un moment sans citation n'est pas un moment — et le bloc de F-89 le refuserait.
        List<SceneFrame> images = List.of(image(0, "titre.jpg"), image(60, "planning.jpg"));
        List<TeamsTranscriptCue> cues = List.of(cue(70, "Paul", "voici le planning"));

        MomentsAlignment aligned = MomentAlignment.align(images, cues, FRISE, List.of());

        assertEquals(1, aligned.moments().size());
        assertEquals("planning.jpg", aligned.moments().get(0).image().getFileName().toString());
        assertEquals(1, aligned.silentFrames());
        assertTrue(aligned.gaps().stream()
                .anyMatch(gap -> gap.kind() == TeamsGapKind.FRAME_WITHOUT_SPEECH));
        assertTrue(aligned.moments().stream().noneMatch(moment -> moment.quote().isEmpty()),
                "aucun moment émis sans citation : le bloc de F-89 le refuserait");
    }

    @Test
    void les_repliques_supplementaires_dun_regne_sont_comptees_jamais_concatenees() {
        List<SceneFrame> images = List.of(image(0, "planning.jpg"));
        List<TeamsTranscriptCue> cues = List.of(
                cue(10, "Paul", "voici le planning"),
                cue(20, "Léa", "on décale au T3"),
                cue(30, "Paul", "d'accord"));

        MomentsAlignment aligned = MomentAlignment.align(images, cues, FRISE, List.of());

        TeamsMoment moment = aligned.moments().get(0);
        assertEquals("voici le planning", moment.quote(), "la citation reste UNE phrase entière");
        assertEquals(2, moment.otherCues());
        assertTrue(moment.describe().contains("2 autres répliques"));
    }

    @Test
    void ce_qui_est_dit_apres_la_derniere_image_va_avec_elle() {
        // La dernière image règne jusqu'à la fin : c'est ce qui était encore affiché.
        List<SceneFrame> images = List.of(image(0, "planning.jpg"));
        List<TeamsTranscriptCue> cues = List.of(cue(7200, "Paul", "on se reparle lundi"));

        MomentsAlignment aligned = MomentAlignment.align(images, cues, FRISE, List.of());

        assertEquals(1, aligned.moments().size());
        assertEquals("planning.jpg", aligned.moments().get(0).image().getFileName().toString());
    }

    @Test
    void une_transcription_vide_rend_zero_moment_et_un_manque_nomme() {
        MomentsAlignment aligned = MomentAlignment.align(
                List.of(image(0, "planning.jpg")), List.of(), FRISE, List.of());

        assertTrue(aligned.moments().isEmpty());
        assertEquals(1, aligned.gaps().size());
        assertTrue(aligned.gaps().get(0).describe().contains("Ouvrez la transcription")
                || aligned.gaps().get(0).describe().contains("ouvrez la transcription"));
    }

    @Test
    void aucune_image_rend_zero_moment_et_un_manque_nomme() {
        MomentsAlignment aligned = MomentAlignment.align(
                List.of(), List.of(cue(10, "Paul", "bonjour")), FRISE, List.of());

        assertTrue(aligned.moments().isEmpty());
        assertTrue(aligned.gaps().stream()
                .anyMatch(gap -> gap.kind() == TeamsGapKind.NO_SCENE_CHANGE));
    }

    @Test
    void sans_origine_du_temps_lalignement_refuse_en_nommant_les_deux_remedes() {
        // « Échouer bruyamment, jamais à moitié faux » : sans origine, chaque horodatage aurait
        // l'air juste et serait faux.
        MomentAlignment.OriginUnknownException refusal =
                assertThrows(MomentAlignment.OriginUnknownException.class,
                        () -> MomentAlignment.align(List.of(image(0, "a.jpg")),
                                List.of(cue(10, "Paul", "bonjour")), MomentTimeline.unknown(),
                                List.of()));

        assertTrue(refusal.sentence().contains("video_started_at"));
        assertTrue(refusal.sentence().contains("meeting_id"));
        assertTrue(refusal.getMessage().contains("aurait l'air juste et serait faux"));
    }

    @Test
    void une_replique_sans_horodatage_ou_sans_texte_est_ecartee_et_comptee() {
        List<TeamsTranscriptCue> cues = new ArrayList<>();
        cues.add(cue(10, "Paul", "voici le planning"));
        cues.add(new TeamsTranscriptCue(null, -1, "", "Léa", "sans heure"));
        cues.add(new TeamsTranscriptCue(DEBUT.plusSeconds(20), -1, "", "Léa", ""));

        MomentsAlignment aligned =
                MomentAlignment.align(List.of(image(0, "planning.jpg")), cues, FRISE, List.of());

        assertEquals(1, aligned.moments().size());
        assertTrue(aligned.gaps().stream()
                .anyMatch(gap -> gap.kind() == TeamsGapKind.MISSING_FIELD && gap.count() == 2));
    }

    @Test
    void le_nombre_de_moments_ne_depasse_jamais_la_borne_du_bloc_de_f89() {
        List<SceneFrame> images = new ArrayList<>();
        List<TeamsTranscriptCue> cues = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            images.add(image(index * 10, "image-" + index + ".jpg"));
            cues.add(cue(index * 10 + 1, "Paul", "phrase " + index));
        }

        MomentsAlignment aligned = MomentAlignment.align(images, cues, FRISE, List.of());

        assertEquals(MomentAlignment.MAX_MOMENTS, aligned.moments().size());
        assertTrue(aligned.gaps().stream()
                .anyMatch(gap -> gap.kind() == TeamsGapKind.CAP_REACHED));
    }

    @Test
    void lhypothese_de_temps_voyage_avec_le_resultat() {
        TeamsMeeting meeting = new TeamsMeeting("19:abc", "Comité de migration", DEBUT, null, "",
                List.of(), "", true, true, "");
        MomentTimeline fromMeeting = MomentTimeline.fromMeeting(meeting).shiftedBy(90);

        MomentsAlignment aligned = MomentAlignment.align(List.of(image(0, "a.jpg")),
                List.of(cue(90, "Paul", "bonjour")), fromMeeting, List.of());

        assertEquals(1, aligned.moments().size());
        assertTrue(aligned.describe().contains("Comité de migration"));
        assertTrue(aligned.describe().contains("+00:01:30"), "le décalage retenu est DIT");
    }

    @Test
    void les_manques_deja_constates_en_amont_sont_repris_tels_quels() {
        TeamsGap carried = TeamsGap.of(TeamsGapKind.FRAME_UNREADABLE, "extraction", "une image");

        MomentsAlignment aligned = MomentAlignment.align(List.of(image(0, "a.jpg")),
                List.of(cue(10, "Paul", "bonjour")), FRISE, List.of(carried));

        assertTrue(aligned.gaps().contains(carried));
    }

    // ------------------------------------------------------------------ matière de papier

    private static SceneFrame image(double offsetSeconds, String name) {
        return new SceneFrame(offsetSeconds, Path.of("/tmp/moments", name), 0L);
    }

    private static TeamsTranscriptCue cue(long offsetSeconds, String speaker, String text) {
        return new TeamsTranscriptCue(DEBUT.plusSeconds(offsetSeconds), 4_000, "id-" + speaker,
                speaker, text);
    }
}
