package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * <b>Ce dont la liaison a besoin, dit au premier usage</b> (F-87 / SF-87-02, décision D3).
 *
 * <p>D3 tranche que le pilotage du navigateur, {@code ffmpeg} et le modèle de transcription sont
 * <b>téléchargés au premier usage, jamais embarqués</b>, et que <b>le téléchargement se voit et se
 * dit</b>. Le paquet du runner fait déjà 40 Mo, et la plupart des utilisateurs n'ouvriront jamais
 * Teams.</p>
 *
 * <p>Cette classe est l'endroit où cela se dit. Pour la liaison (F-87), la réponse est heureuse :
 * <b>le pilotage du navigateur est natif</b> — le protocole de débogage est du JSON sur une socket,
 * que la machine virtuelle parle déjà. L'intention de D3 est donc tenue à <b>zéro octet</b> : rien
 * n'est embarqué, et rien n'est à télécharger non plus. C'est ici que {@code ffmpeg} (F-90) et le
 * modèle de transcription (F-91) viendront se déclarer, et que leur téléchargement se verra.</p>
 *
 * <p>Annoncé <b>une seule fois</b> par exécution du runner : une annonce répétée cesse d'être lue —
 * c'est la même raison qui fait de D1 une annonce par réunion, et non par tour.</p>
 */
public final class TeamsFirstUseNotice {

    private final AtomicBoolean said = new AtomicBoolean(false);
    private final List<Requirement> requirements;

    public TeamsFirstUseNotice() {
        this(List.of(new Requirement("pilotage du navigateur", true,
                "natif : le protocole de débogage est du JSON sur une socket — rien à télécharger")));
    }

    TeamsFirstUseNotice(List<Requirement> requirements) {
        this.requirements = List.copyOf(requirements);
    }

    /**
     * Dit ce dont la liaison a besoin, une fois. Les fois suivantes, ne dit rien.
     *
     * @return vrai si l'annonce a été faite à cet appel
     */
    public boolean announceOnce(Consumer<String> say) {
        if (!said.compareAndSet(false, true)) {
            return false;
        }
        if (say != null) {
            say.accept(text());
        }
        return true;
    }

    /** Le texte de l'annonce, également rendu par la sonde de liaison (SF-87-03). */
    public String text() {
        List<String> lines = new ArrayList<>();
        lines.add("Première utilisation de Teams sur ce poste. Ce dont la liaison a besoin :");
        for (Requirement requirement : requirements) {
            lines.add("  - " + requirement.name() + " : "
                    + (requirement.available() ? "déjà disponible" : "À TÉLÉCHARGER")
                    + " — " + requirement.detail());
        }
        if (requirements.stream().allMatch(Requirement::available)) {
            lines.add("Rien à télécharger : la liaison peut commencer tout de suite.");
        } else {
            lines.add("Le téléchargement a lieu maintenant, une seule fois. C'est une minute "
                    + "d'attente, pas une panne.");
        }
        lines.add("Ce qui reste sur cette machine : votre session, vos cookies, vos jetons "
                + "Microsoft. Ils ne remontent jamais.");
        return String.join(System.lineSeparator(), lines);
    }

    /** Vrai si tout est déjà là — c'est le cas de la liaison seule. */
    public boolean everythingAvailable() {
        return requirements.stream().allMatch(Requirement::available);
    }

    /**
     * Un besoin de la liaison, et son état.
     *
     * @param name      nom en français
     * @param available vrai s'il est déjà là
     * @param detail    pourquoi, ou ce qui sera téléchargé
     */
    public record Requirement(String name, boolean available, String detail) {
    }
}
