package fr.claudegateway.jarvis.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.jarvis.replay.JarvisReplay.Bilan;
import fr.claudegateway.jarvis.replay.JarvisReplay.Correlation;
import fr.claudegateway.jarvis.replay.JarvisReplay.Delegation;
import fr.claudegateway.jarvis.replay.JarvisReplay.Heard;
import fr.claudegateway.jarvis.replay.JarvisReplay.Show;
import fr.claudegateway.jarvis.replay.JarvisReplay.Steps;
import fr.claudegateway.jarvis.replay.JarvisReplay.Subject;
import fr.claudegateway.jarvis.replay.JarvisReplay.Turn;

/** F-255 / SF-255-02 — les verdicts sans IA et le bilan de séance. */
class JarvisReplayVerdictsTest {

    private static final UUID THREAD = UUID.randomUUID();
    private static final UUID WAF = UUID.randomUUID();
    private static final UUID AGENOR = UUID.randomUUID();
    private static final UUID DATA = UUID.randomUUID();
    private static final Map<UUID, String> NAMES = Map.of(THREAD, "Jarvis", WAF, "waf", AGENOR, "agenor", DATA,
            "data-platform");
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-09T16:12:00Z");

    /** Un tour entendu, mesuré, sans rien d'autre. */
    static final class T {
        Integer chars = 20;
        String rejected;
        String userText = "Bonjour Jarvis.";
        Integer firstSound = 1_500;
        Integer said = 30;
        String reply = "Bonjour, Monsieur.";
        UUID screen;
        List<Delegation> delegations = new ArrayList<>();
        List<Show> shows = new ArrayList<>();
        List<String> fallbacks = new ArrayList<>();
        boolean measured = true;

        Turn build(int i) {
            return new Turn(i, null, "p", Correlation.ESTIMEE, T0.plusSeconds(20L * i), "CONV_STREAM",
                    new Heard(chars, rejected, null, null, userText, null, null),
                    new Steps(null, null, null, null, null, firstSound, null), "FAST", null, null, null, said, reply,
                    screen == null ? null : new Subject(screen, NAMES.get(screen)), delegations, shows, fallbacks,
                    false, measured, List.of(), List.of());
        }
    }

    private static List<Turn> judge(T... turns) {
        List<Turn> built = new ArrayList<>();
        for (int i = 0; i < turns.length; i++) {
            built.add(turns[i].build(i + 1));
        }
        return JarvisReplayVerdicts.judge(built, NAMES, THREAD);
    }

    private static List<String> codes(Turn t) {
        return t.verdicts().stream().map(JarvisReplay.Verdict::code).toList();
    }

    private static Delegation to(UUID subject, String label) {
        return new Delegation(T0, new Subject(subject, NAMES.get(subject)), null, "EN_COURS", label, null);
    }

    @Test
    @DisplayName("entendu ; perdu (refus, ou transcription vide) ; jamais les deux")
    void heardOrLost() {
        T ok = new T();
        T refused = new T();
        refused.rejected = "empty";
        refused.chars = 0;
        refused.userText = null;
        refused.reply = null;
        T empty = new T();
        empty.chars = 0;
        empty.userText = null;
        empty.reply = null;
        List<Turn> j = judge(ok, refused, empty);
        assertThat(codes(j.get(0))).contains("ENTENDU").doesNotContain("PERDU");
        assertThat(codes(j.get(1))).contains("PERDU").doesNotContain("ENTENDU");
        assertThat(j.get(1).verdicts().get(0).detail()).isEqualTo("parole refusée : empty");
        assertThat(codes(j.get(2))).contains("PERDU");
    }

    @Test
    @DisplayName("répété : même réponse normalisée qu'un des 5 tours précédents ; pas au-delà")
    void repeated() {
        T[] turns = new T[10];
        for (int i = 0; i < turns.length; i++) {
            turns[i] = new T();
            turns[i].reply = "Réponse " + i;
        }
        turns[0].reply = "Pour quel client ou quel sujet, Monsieur ?";
        turns[1].reply = "Pour quel client ou quel sujet, Monsieur ?";
        turns[3].reply = "pour quel client ou quel sujet monsieur";
        turns[9].reply = "Pour quel client ou quel sujet, Monsieur ?"; // 6 tours après la dernière : plus une répétition
        List<Turn> j = judge(turns);
        assertThat(codes(j.get(0))).doesNotContain("REPETE");
        assertThat(codes(j.get(1))).contains("REPETE");
        assertThat(j.get(3).verdicts().stream().filter(v -> v.code().equals("REPETE")).findFirst().orElseThrow()
                .detail()).isEqualTo("même réponse, 3ᵉ fois");
        assertThat(codes(j.get(9))).doesNotContain("REPETE");
    }

    @Test
    @DisplayName("lent : premier son au-delà de 3 s ; 3 s pile n'est pas lent")
    void slow() {
        T fast = new T();
        fast.firstSound = 3_000;
        T slow = new T();
        slow.firstSound = 4_632;
        List<Turn> j = judge(fast, slow);
        assertThat(codes(j.get(0))).doesNotContain("LENT");
        assertThat(j.get(1).verdicts()).anySatisfy(v -> {
            assertThat(v.code()).isEqualTo("LENT");
            assertThat(v.detail()).isEqualTo("premier son à 4,6 s");
        });
    }

    @Test
    @DisplayName("mauvais sujet : délégation vers waf quand data-platform est le dernier sujet nommé")
    void wrongSubject() {
        T named = new T();
        named.userText = "Repartons sur data-platform.";
        named.reply = "Nous passons sur data-platform, Monsieur.";
        T delegated = new T();
        delegated.userText = "Euh, fais une recherche approfondie, tu dois la trouver.";
        delegated.screen = WAF;
        delegated.delegations.add(to(WAF, "recherche approfondie de la vidéo"));
        List<Turn> j = judge(named, delegated);
        assertThat(j.get(1).verdicts()).anySatisfy(v -> {
            assertThat(v.code()).isEqualTo("MAUVAIS_SUJET");
            assertThat(v.detail()).isEqualTo("parti vers « waf », sujet courant « data-platform », écran « waf »");
        });
    }

    @Test
    @DisplayName("mauvais sujet : jamais si la demande nomme la cible, ni vers le sujet courant, ni vers le fil")
    void rightSubject() {
        T agenor = new T();
        agenor.userText = "Allons sur agenor.";
        T sameSubject = new T();
        sameSubject.userText = "Combien d'étapes manquent ?";
        sameSubject.delegations.add(to(AGENOR, "étapes restantes"));
        T namesTarget = new T();
        namesTarget.userText = "Ok, allons sur le dernier chantier côté waf";
        namesTarget.delegations.add(to(WAF, "point chantier 1"));
        T thread = new T();
        thread.delegations.add(to(THREAD, "tour d'horizon"));
        List<Turn> j = judge(agenor, sameSubject, namesTarget, thread);
        j.forEach(t -> assertThat(codes(t)).doesNotContain("MAUVAIS_SUJET"));
    }

    @Test
    @DisplayName("sujet courant : sans sujet nommé, celui que Jarvis annonce, sinon celui de l'écran")
    void currentSubjectFallbacks() {
        T announced = new T();
        announced.userText = "Le client CAGIP.";
        announced.reply = "Nous voici sur agenor, Monsieur.";
        T delegated = new T();
        delegated.userText = "Fais le point.";
        delegated.delegations.add(to(WAF, "point"));
        assertThat(codes(judge(announced, delegated).get(1))).contains("MAUVAIS_SUJET");

        T screenOnly = new T();
        screenOnly.userText = "Fais le point.";
        screenOnly.screen = WAF;
        screenOnly.delegations.add(to(WAF, "point"));
        assertThat(codes(judge(screenOnly).get(0))).doesNotContain("MAUVAIS_SUJET");
    }

    @Test
    @DisplayName("montré ; « Voici » sans affichage (refusé ou aucun ordre) ; rien sans annonce")
    void shown() {
        T shown = new T();
        shown.reply = "Voici la galerie des vidéos, Monsieur.";
        shown.shows.add(new Show("galerie", false, null));
        T refused = new T();
        refused.reply = "Voici la galerie des vidéos, Monsieur.";
        refused.shows.add(new Show("galerie", true, "NOT_FOUND"));
        T nothing = new T();
        nothing.reply = "Voilà les chantiers.";
        T plain = new T();
        plain.reply = "Nous voici chez CAGIP, Monsieur.";
        List<Turn> j = judge(shown, refused, nothing, plain);
        assertThat(codes(j.get(0))).contains("MONTRE").doesNotContain("VOICI_SANS_AFFICHAGE");
        assertThat(j.get(1).verdicts()).anySatisfy(v -> {
            assertThat(v.code()).isEqualTo("VOICI_SANS_AFFICHAGE");
            assertThat(v.detail()).isEqualTo("refusé : galerie NOT_FOUND");
        });
        assertThat(codes(j.get(2))).contains("VOICI_SANS_AFFICHAGE");
        assertThat(codes(j.get(3))).doesNotContain("VOICI_SANS_AFFICHAGE", "MONTRE");
    }

    @Test
    @DisplayName("délégation muette (said=0) ; repli de transcription avec sa raison ; mesure absente")
    void silentFallbackUnmeasured() {
        T silent = new T();
        silent.said = 0;
        silent.reply = null;
        silent.delegations.add(to(WAF, "x"));
        silent.userText = "waf";
        T fallback = new T();
        fallback.fallbacks.add("ouverture_fermee");
        T unmeasured = new T();
        unmeasured.measured = false;
        unmeasured.firstSound = null;
        List<Turn> j = judge(silent, fallback, unmeasured);
        assertThat(codes(j.get(0))).contains("DELEGATION_MUETTE");
        assertThat(j.get(1).verdicts()).anySatisfy(v -> {
            assertThat(v.code()).isEqualTo("REPLI_TRANSCRIPTION");
            assertThat(v.detail()).isEqualTo("ouverture_fermee");
        });
        assertThat(codes(j.get(2))).contains("MESURE_ABSENTE");
        assertThat(j).extracting(Turn::number).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("bilan : comptes, taux, premier son p50/p90 au rang le plus proche")
    void bilan() {
        T a = new T();
        a.firstSound = 1_000;
        T b = new T();
        b.firstSound = 4_000;
        b.fallbacks.add("coupe");
        T c = new T();
        c.rejected = "empty";
        c.chars = 0;
        c.userText = null;
        c.reply = null;
        c.firstSound = null;
        T d = new T();
        d.firstSound = 2_000;
        d.delegations.add(to(WAF, "waf"));
        d.userText = "waf";
        Bilan bilan = JarvisReplayVerdicts.bilan(judge(a, b, c, d));
        assertThat(bilan.turns()).isEqualTo(4);
        assertThat(bilan.heard()).isEqualTo(3);
        assertThat(bilan.lost()).isEqualTo(1);
        assertThat(bilan.lostRate()).isEqualTo(0.25);
        assertThat(bilan.slow()).isEqualTo(1);
        assertThat(bilan.firstSoundP50()).isEqualTo(2_000);
        assertThat(bilan.firstSoundP90()).isEqualTo(4_000);
        assertThat(bilan.delegations()).isEqualTo(1);
        assertThat(bilan.streamFallbacks()).isEqualTo(1);
        assertThat(bilan.streamFallbackRate()).isEqualTo(0.25);
        assertThat(JarvisReplayVerdicts.bilan(List.of()).firstSoundP50()).isNull();
    }

    @Test
    @DisplayName("sujets nommés : accents, casse, tirets ; le dernier nommé l'emporte ; le fil Jarvis jamais")
    void namedSubjects() {
        assertThat(JarvisReplayVerdicts.lastNamed("Repartons sur DATA platform", NAMES, THREAD)).isEqualTo(DATA);
        assertThat(JarvisReplayVerdicts.lastNamed("de waf vers agenor", NAMES, THREAD)).isEqualTo(AGENOR);
        assertThat(JarvisReplayVerdicts.lastNamed("Bonjour Jarvis", NAMES, THREAD)).isNull();
        assertThat(JarvisReplayVerdicts.lastNamed("le wafer", NAMES, THREAD)).isNull();
        assertThat(JarvisReplayVerdicts.lastNamed(null, NAMES, THREAD)).isNull();
    }
}
