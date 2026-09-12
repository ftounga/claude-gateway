package fr.claudegateway.governance.juge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.governance.GovernanceHostFiles;
import fr.claudegateway.governance.GovernanceHostFiles.HostFileRead;
import fr.claudegateway.governance.GovernanceHostFiles.Presence;
import fr.claudegateway.governance.GovernanceHostRef;
import fr.claudegateway.governance.GovernanceHostScope;
import fr.claudegateway.governance.GovernanceMapDestinations;
import fr.claudegateway.governance.GovernancePackageFile;
import fr.claudegateway.governance.GovernanceProjectFiles;

/**
 * Rassemble <b>la matière</b> du juge indépendant (F-94 / SF-94-01) : la carte du poste d'un côté,
 * les notes de ses projets de l'autre.
 *
 * <p>Tout est lu <b>là où ça vit réellement</b> — la carte à la racine du poste par
 * {@link GovernanceHostFiles}, les notes dans chaque projet par {@link GovernanceProjectFiles}. La
 * gateway ne garde aucune copie : c'est la machine de l'utilisateur qui fait foi, et c'est ce qui
 * rend l'audit vrai plutôt que plausible.</p>
 *
 * <p><b>Trois façons de n'avoir rien à juger, et toutes se taisent</b> : un poste sans machine n'a
 * pas de racine ; un poste sans gouvernance active n'attend aucun fichier de carte ; une carte qu'on
 * n'a pas su lire ne se compare à rien. Dans ces trois cas la matière est {@link JugeMatiere#VIDE},
 * et aucune question n'est posée. <b>Ce n'est pas le repli qui alerte</b> : celui-là porte sur un
 * juge qu'on n'a pas compris (SF-94-03), pas sur une machine éteinte. Un filet qui crierait parce que
 * le runner dort ne serait plus lu.</p>
 *
 * <p><b>Un gabarit jamais touché n'est pas une note.</b> Les gabarits du paquet portent des exemples
 * — {@code cluster « atlas » (10.0.4.0/24)} figure tel quel dans {@code STATE.md}. Un fichier de
 * projet identique à ce qui a été déposé est donc écarté : il ne contient aucun fait, et l'envoyer
 * ferait alerter le juge sur un exemple dès le premier projet.</p>
 *
 * <p><b>Isolation.</b> Le poste arrive <b>déjà vérifié possédé</b> ; les projets viennent de
 * {@link GovernanceHostScope#projectsOf(UUID, GovernanceHostRef)}, qui lit par {@code user_id} +
 * {@code host_id}. Aucune autre source de projets n'est employée ici.</p>
 *
 * <p><b>Rien ne lève, jamais.</b> Ce lecteur est appelé en fin de tour : un projet effacé, un runner
 * muet, un paquet dépublié rendent une matière vide, pas une exception. Le contenu n'est
 * <b>jamais</b> journalisé — ce sont les fichiers de l'utilisateur, sur la machine d'un client.</p>
 */
@Service
public class JugeMatiereReader {

    private static final Logger log = LoggerFactory.getLogger(JugeMatiereReader.class);

    /** L'extension des notes. La carte et les notes sont du Markdown, et rien d'autre ne se lit. */
    private static final String NOTE_SUFFIX = ".md";

    private final GovernanceMapDestinations destinations;
    private final GovernanceHostFiles hostFiles;
    private final GovernanceHostScope hostScope;
    private final GovernanceProjectFiles projectFiles;

    public JugeMatiereReader(GovernanceMapDestinations destinations, GovernanceHostFiles hostFiles,
            GovernanceHostScope hostScope, GovernanceProjectFiles projectFiles) {
        this.destinations = destinations;
        this.hostFiles = hostFiles;
        this.hostScope = hostScope;
        this.projectFiles = projectFiles;
    }

    /**
     * La matière du poste, prête à être soumise.
     *
     * @return la matière, ou {@link JugeMatiere#VIDE} quand il n'y a rien à comparer
     */
    @Transactional(readOnly = true)
    public JugeMatiere lire(UUID userId, GovernanceHostRef host) {
        if (userId == null || host == null || !hostFiles.supports(host)) {
            return JugeMatiere.VIDE; // Poste « Hébergé » : pas de racine, donc pas de carte.
        }
        try {
            return assemble(userId, host);
        } catch (RuntimeException ex) {
            // Un filet qui casse le tour d'un utilisateur est pire que pas de filet.
            log.debug("Matière du juge non rassemblée ({})", ex.getClass().getSimpleName());
            return JugeMatiere.VIDE;
        }
    }

    // -------------------------------------------------------------- internes

    private JugeMatiere assemble(UUID userId, GovernanceHostRef host) {
        Map<String, GovernancePackageFile> attendus = destinations.filesOf(userId, host);
        if (attendus.isEmpty()) {
            return JugeMatiere.VIDE; // Rien d'activé : aucun fichier de carte n'est attendu.
        }
        Budget budget = new Budget();
        List<JugeMatiere.Piece> carte = lireCarte(userId, host, attendus.keySet(), budget);
        if (carte.isEmpty()) {
            // Carte absente, illisible ou machine muette : on ne compare pas des notes à une carte
            // qu'on n'a pas — TOUT y serait « absent », et l'alerte serait du bruit intégral.
            return JugeMatiere.VIDE;
        }
        List<JugeMatiere.Piece> notes =
                lireNotes(userId, host, destinations.projectFilesOf(userId, host), budget);
        if (notes.isEmpty()) {
            return JugeMatiere.VIDE;
        }
        return new JugeMatiere(carte, notes, budget.tronquee());
    }

    /** La carte, fichier par fichier. Une machine muette arrête tout ; un fichier illisible, non. */
    private List<JugeMatiere.Piece> lireCarte(UUID userId, GovernanceHostRef host,
            Iterable<String> chemins, Budget budget) {
        List<JugeMatiere.Piece> carte = new ArrayList<>();
        for (String chemin : chemins) {
            if (budget.epuise()) {
                break;
            }
            HostFileRead read = hostFiles.read(userId, host, chemin);
            if (read.presence() == Presence.UNREACHABLE) {
                // La machine s'est tue : on ne relance pas cinq délais pour l'apprendre cinq fois,
                // et on ne juge pas sur une carte à moitié lue.
                return List.of();
            }
            if (read.presence() != Presence.PRESENT) {
                continue; // Fichier absent ou illisible : le suivant peut très bien répondre.
            }
            carte.add(new JugeMatiere.Piece(chemin, budget.prendre(read.contentOrEmpty())));
        }
        return List.copyOf(carte);
    }

    /**
     * Les notes : les {@code .md} posés à la <b>racine</b> de chaque projet du poste.
     *
     * <p>Les sous-dossiers sont écartés — {@code sources/}, {@code context/}, {@code .claude/} —
     * parce que des sources brutes sont du bruit ; et les gabarits jamais modifiés aussi, parce
     * qu'ils ne portent que leurs propres exemples.</p>
     */
    private List<JugeMatiere.Piece> lireNotes(UUID userId, GovernanceHostRef host,
            Map<String, GovernancePackageFile> gabarits, Budget budget) {
        List<JugeMatiere.Piece> notes = new ArrayList<>();
        for (Workspace projet : projets(userId, host)) {
            if (notes.size() >= JugeMatiere.MAX_NOTES || budget.epuise()) {
                break;
            }
            for (String chemin : notesDe(userId, projet)) {
                if (notes.size() >= JugeMatiere.MAX_NOTES || budget.epuise()) {
                    break;
                }
                Optional<String> contenu = lireNote(userId, projet, chemin);
                if (contenu.isEmpty() || estGabaritIntact(gabarits, chemin, contenu.get())) {
                    continue;
                }
                notes.add(new JugeMatiere.Piece(cite(projet, chemin),
                        budget.prendre(contenu.get())));
            }
        }
        return List.copyOf(notes);
    }

    /** Les projets du poste, ou aucun : lister ne fait jamais échouer un tour. */
    private List<Workspace> projets(UUID userId, GovernanceHostRef host) {
        try {
            return hostScope.projectsOf(userId, host);
        } catch (RuntimeException ex) {
            log.debug("Projets du poste non listés ({})", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Les chemins de notes d'un projet : {@code .md}, à la racine, et rien d'autre. */
    private List<String> notesDe(UUID userId, Workspace projet) {
        List<String> chemins = new ArrayList<>();
        for (String chemin : projectFiles.listPathsOrEmpty(userId, projet)) {
            String rel = chemin == null ? "" : chemin.strip();
            if (rel.isEmpty() || rel.contains("/") || rel.startsWith(".")
                    || !rel.toLowerCase(Locale.ROOT).endsWith(NOTE_SUFFIX)) {
                continue;
            }
            if (!chemins.contains(rel)) {
                chemins.add(rel);
            }
        }
        return chemins;
    }

    private Optional<String> lireNote(UUID userId, Workspace projet, String chemin) {
        try {
            return projectFiles.read(userId, projet, chemin).filter(note -> !note.isBlank());
        } catch (RuntimeException ex) {
            log.debug("Note de projet illisible ({})", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Vrai si ce fichier est le gabarit déposé, <b>inchangé</b>.
     *
     * <p>La comparaison ignore les fins de ligne et les blancs de bord : un éditeur qui normalise un
     * {@code CRLF} ne transforme pas un gabarit en note.</p>
     */
    private static boolean estGabaritIntact(Map<String, GovernancePackageFile> gabarits,
            String chemin, String contenu) {
        GovernancePackageFile gabarit = gabarits.get(chemin);
        return gabarit != null && normalise(gabarit.getContent()).equals(normalise(contenu));
    }

    private static String normalise(String contenu) {
        return contenu == null ? "" : contenu.replace("\r\n", "\n").strip();
    }

    /**
     * Le chemin cité dans le verdict : celui du projet sous la racine, puis le fichier.
     *
     * <p>C'est ce qui rend une alerte <b>vérifiable</b> — « cité dans migration-dns/STATE.md » se
     * vérifie, « cité dans STATE.md » ne dit pas lequel quand le poste porte trois projets.</p>
     */
    private static String cite(Workspace projet, String chemin) {
        String prefixe = projet.getProjectPath() == null ? "" : projet.getProjectPath().strip();
        if (prefixe.isEmpty() || "/".equals(prefixe)) {
            prefixe = projet.getName() == null ? "" : projet.getName().strip();
        }
        return prefixe.isEmpty() ? chemin : prefixe + "/" + chemin;
    }

    /** Le budget de caractères de la matière — et la mémoire qu'il a fallu couper. */
    private static final class Budget {

        private int restant = JugeMatiere.MAX_CHARS_TOTAL;
        private boolean tronquee;

        boolean epuise() {
            return restant <= 0;
        }

        /** Prend ce qui tient, et retient qu'on a coupé. Une coupe se dit, elle ne se devine pas. */
        String prendre(String contenu) {
            String borne = contenu.length() <= JugeMatiere.MAX_CHARS_PAR_FICHIER
                    ? contenu
                    : contenu.substring(0, JugeMatiere.MAX_CHARS_PAR_FICHIER);
            if (borne.length() < contenu.length()) {
                tronquee = true;
            }
            if (borne.length() > restant) {
                borne = borne.substring(0, restant);
                tronquee = true;
            }
            restant -= borne.length();
            return borne;
        }

        boolean tronquee() {
            return tronquee;
        }
    }
}
