package fr.claudegateway.contract;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * L'inventaire des <b>DTO d'échange</b> (F-81 / SF-81-02).
 *
 * <p><b>La définition.</b> Un DTO d'échange est une classe qu'un côté <b>désérialise depuis une
 * charge utile écrite par un processus qu'il ne redéploie pas en même temps que lui</b> — le runner
 * installé sur la machine d'un client, ou un autre pod de la gateway pendant une bascule
 * progressive. C'est la <b>lecture</b> qui est visée, jamais l'écriture : {@code PairResponse} n'a
 * pas besoin d'être tolérant, la gateway ne le relit jamais ; c'est {@code StoredToken}, du côté qui
 * lit, qui devait l'être.</p>
 *
 * <p><b>Pourquoi l'inventaire n'est pas une liste.</b> Une liste écrite à la main aurait été juste le
 * jour où elle a été écrite. Celle-ci est reconstruite à chaque exécution, par deux sondes sur les
 * classes compilées :</p>
 * <ol>
 *   <li><b>les corps de requête</b> — le type de tout paramètre {@link RequestBody} d'un contrôleur
 *       du paquet {@code fr.claudegateway.runner..} : ce que la gateway lit du runner et des autres
 *       pods ;</li>
 *   <li><b>les cibles de lecture</b> — lecture du <b>bytecode</b> : toute classe passée à
 *       {@code ObjectMapper.readValue} / {@code treeToValue} / {@code convertValue}. C'est cette
 *       sonde qui attrapera le <b>prochain {@code StoredToken}</b> : un type nouvellement lu par
 *       Jackson entre dans l'inventaire le jour où la ligne de lecture est écrite, qu'il porte ou
 *       non la moindre annotation.</li>
 * </ol>
 *
 * <p>S'y ajoute la <b>fermeture transitive</b> : un type maison déclaré comme composant d'un DTO
 * d'échange en est un aussi — un champ inconnu au troisième niveau fait échouer la lecture tout
 * autant.</p>
 *
 * <p><b>Le bytecode plutôt que le source.</b> Un balayage du texte trouverait les {@code readValue}
 * par expression régulière et se tromperait au premier saut de ligne. Le bytecode dit exactement
 * quelle classe est passée à quel appel. {@code org.springframework.asm} est déjà là, apporté par
 * spring-core : aucune dépendance nouvelle.</p>
 */
final class ExchangeDtoInventory {

    /** Paquet racine du produit : hors de lui, un type n'est pas à nous et la règle ne s'applique pas. */
    private static final String MAISON = "fr.claudegateway";

    /** Les paquets du canal runner et du relais inter-pods, côté gateway. */
    private static final String CANAL_RUNNER = "fr.claudegateway.runner";

    private static final String OBJECT_MAPPER = "com/fasterxml/jackson/databind/ObjectMapper";

    private static final Set<String> LECTURES = Set.of("readValue", "treeToValue", "convertValue");

    private ExchangeDtoInventory() {
    }

    /**
     * L'inventaire complet, les deux côtés confondus, trié par nom pour que l'échec d'un test soit
     * lisible et reproductible.
     */
    static List<Class<?>> tout() {
        TreeMap<String, Class<?>> trouves = new TreeMap<>();

        // Côté gateway : ce que ses contrôleurs du canal runner acceptent en corps de requête.
        for (Class<?> type : classesDuJarDe(fr.claudegateway.runner.dto.PairRequest.class)) {
            if (!type.getName().startsWith(CANAL_RUNNER)) {
                continue;
            }
            for (Method methode : declarees(type)) {
                for (Parameter parametre : methode.getParameters()) {
                    if (parametre.isAnnotationPresent(RequestBody.class)) {
                        retenir(parametre.getType(), trouves);
                    }
                }
            }
        }

        // Les deux côtés : ce que le bytecode passe réellement à Jackson.
        for (Class<?> ancre : List.of(fr.claudegateway.runner.StoredToken.class,
                fr.claudegateway.runner.dto.PairRequest.class)) {
            boolean gateway = ancre.getName().startsWith(CANAL_RUNNER + ".dto");
            for (byte[] classe : bytecodeDuJarDe(ancre, gateway ? CANAL_RUNNER : MAISON)) {
                for (String cible : ciblesDeLecture(classe)) {
                    retenir(charger(cible), trouves);
                }
            }
        }

        return List.copyOf(trouves.values());
    }

    // ------------------------------------------------------- fermeture transitive

    /** Retient un type et, de proche en proche, les types maison qu'il déclare. */
    private static void retenir(Class<?> depart, TreeMap<String, Class<?>> trouves) {
        Deque<Class<?>> aVoir = new ArrayDeque<>();
        aVoir.add(depart);
        while (!aVoir.isEmpty()) {
            Class<?> type = aVoir.poll();
            if (type == null || !estUnTypeMaisonLiable(type)
                    || trouves.putIfAbsent(type.getName(), type) != null) {
                continue;
            }
            if (type.isRecord()) {
                for (RecordComponent composant : type.getRecordComponents()) {
                    aVoir.add(composant.getType());
                }
            }
            for (Field champ : type.getDeclaredFields()) {
                if (!Modifier.isStatic(champ.getModifiers())) {
                    aVoir.add(champ.getType());
                }
            }
        }
    }

    /**
     * Un type sur lequel la règle a un sens : une classe de valeur à nous.
     *
     * <p>Les énumérations sont écartées — une valeur inconnue y est un autre sujet, avec une autre
     * réponse ({@code READ_UNKNOWN_ENUM_VALUES_AS_NULL}), et {@code @JsonIgnoreProperties} n'y peut
     * rien. Les interfaces et les types abstraits le sont aussi : ce n'est pas eux que Jackson
     * instancie.</p>
     */
    private static boolean estUnTypeMaisonLiable(Class<?> type) {
        return type != null
                && type.getName().startsWith(MAISON)
                && !type.isEnum()
                && !type.isInterface()
                && !type.isAnnotation()
                && !type.isArray()
                && !Modifier.isAbstract(type.getModifiers())
                && !Throwable.class.isAssignableFrom(type);
    }

    // ------------------------------------------------------------------ bytecode

    /** Les types passés à une lecture Jackson dans cette classe compilée. */
    private static List<String> ciblesDeLecture(byte[] classe) {
        List<String> cibles = new ArrayList<>();
        new ClassReader(classe).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    /** Dernier littéral de classe empilé : c'est lui que `readValue` consommera. */
                    private String dernierLitteral;

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type type && type.getSort() == Type.OBJECT) {
                            dernierLitteral = type.getClassName();
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methode,
                            String descriptor, boolean isInterface) {
                        if (OBJECT_MAPPER.equals(owner) && LECTURES.contains(methode)
                                && dernierLitteral != null) {
                            cibles.add(dernierLitteral);
                            dernierLitteral = null;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return cibles;
    }

    // -------------------------------------------------------------- exploration

    /** Toutes les classes du jar d'où vient cette classe d'ancrage, chargées. */
    private static List<Class<?>> classesDuJarDe(Class<?> ancre) {
        List<Class<?>> classes = new ArrayList<>();
        for (String nom : nomsDuJarDe(ancre, MAISON)) {
            Class<?> type = charger(nom);
            if (type != null) {
                classes.add(type);
            }
        }
        return classes;
    }

    /** Le bytecode brut de toutes les classes du jar d'où vient cette classe d'ancrage. */
    private static List<byte[]> bytecodeDuJarDe(Class<?> ancre, String prefixe) {
        List<byte[]> octets = new ArrayList<>();
        for (String nom : nomsDuJarDe(ancre, prefixe)) {
            try (InputStream flux = ancre.getClassLoader()
                    .getResourceAsStream(nom.replace('.', '/') + ".class")) {
                if (flux != null) {
                    octets.add(flux.readAllBytes());
                }
            } catch (IOException e) {
                throw new IllegalStateException("Classe illisible : " + nom, e);
            }
        }
        return octets;
    }

    private static List<String> nomsDuJarDe(Class<?> ancre, String prefixe) {
        Path source;
        try {
            source = Path.of(ancre.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalStateException("Impossible de localiser le jar de " + ancre, e);
        }
        List<String> noms = new ArrayList<>();
        try (JarFile jar = new JarFile(source.toFile())) {
            Enumeration<JarEntry> entrees = jar.entries();
            while (entrees.hasMoreElements()) {
                String nom = entrees.nextElement().getName();
                if (!nom.endsWith(".class")) {
                    continue;
                }
                String classe = nom.substring(0, nom.length() - ".class".length()).replace('/', '.');
                if (classe.startsWith(prefixe)) {
                    noms.add(classe);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Jar illisible : " + source, e);
        }
        return noms;
    }

    private static Class<?> charger(String nom) {
        try {
            return Class.forName(nom, false, ExchangeDtoInventory.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null; // une classe qu'on ne peut pas charger n'est lue par personne non plus
        }
    }

    /** Les méthodes déclarées, ou rien si la classe ne peut pas être introspectée. */
    private static Method[] declarees(Class<?> type) {
        try {
            return type.getDeclaredMethods();
        } catch (NoClassDefFoundError e) {
            return new Method[0];
        }
    }
}
