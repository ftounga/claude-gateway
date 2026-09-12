package fr.claudegateway.runner.teams;

/**
 * <b>Ce qui n'a pas pu être lu</b> (F-87 / SF-87-01) — le livrable le moins spectaculaire du volet,
 * et le plus important.
 *
 * <p>Tout outil de lecture rend, à côté de son résultat, la liste de ces manques. Un adaptateur
 * cassé qui rendrait la moitié des messages sans le dire produirait un compte rendu plausible et
 * faux, sur lequel on prendrait des décisions ; c'est pire qu'un adaptateur qui refuse.</p>
 *
 * @param kind   nature du manque
 * @param where  où il s'est produit, en clair : « conversation 19:abc… », « page 3 » — jamais un
 *               chemin technique ni un jeton
 * @param detail précision utile à qui devra réparer : nom du champ attendu, genre observé
 * @param count  nombre d'éléments concernés, au moins 1
 */
public record TeamsGap(TeamsGapKind kind, String where, String detail, int count) {

    public TeamsGap {
        where = where == null ? "" : where.strip();
        detail = detail == null ? "" : detail.strip();
        count = Math.max(1, count);
    }

    public static TeamsGap of(TeamsGapKind kind, String where, String detail) {
        return new TeamsGap(kind, where, detail, 1);
    }

    /** Même manque, un élément de plus : les manques se cumulent, ils ne se répètent pas. */
    public TeamsGap plusOne() {
        return new TeamsGap(kind, where, detail, count + 1);
    }

    /** Phrase française prête à être lue : « 3 champs obligatoires absents (originalarrivaltime) ». */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append(count).append(' ').append(kind.label());
        if (!detail.isEmpty()) {
            text.append(" (").append(detail).append(')');
        }
        if (!where.isEmpty()) {
            text.append(" — ").append(where);
        }
        return text.toString();
    }
}
