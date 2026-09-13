import { VigiePerson, VigiePersonSubject } from '../../core/models/vigie.models';

/**
 * **L'annuaire du Radar, en fonctions pures** (F-103 / SF-103-04) : l'ordre, le filtre, les sujets d'une
 * personne et leurs mots. Sans Angular ni HTTP.
 */

/** Longueur maximale du filtre. */
export const DIRECTORY_FILTER_MAX = 100;

/** Personnes affichées avant « Afficher les *k* autres ». */
export const DIRECTORY_PAGE = 50;

/** Minuscules, sans accents, espaces resserrés : la clé de comparaison du filtre et du tri. */
export function directoryKey(value: string | null | undefined): string {
  return (value ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim();
}

function time(iso: string | null | undefined): number | null {
  if (!iso) {
    return null;
  }
  const t = new Date(iso).getTime();
  return Number.isNaN(t) ? null : t;
}

/** L'ordre de l'annuaire : l'interaction la plus récente d'abord, les inconnues à la fin, puis par nom. */
export function sortPeople(people: readonly VigiePerson[] | null | undefined): VigiePerson[] {
  return [...(people ?? [])].sort((a, b) => {
    const ta = time(a.lastInteractionAt);
    const tb = time(b.lastInteractionAt);
    if (ta !== tb) {
      if (ta === null) {
        return 1;
      }
      if (tb === null) {
        return -1;
      }
      return tb - ta;
    }
    return a.displayName.localeCompare(b.displayName, 'fr', { sensitivity: 'base' });
  });
}

/** Le filtre : nom, fonction ou nom d'un sujet, sans casse ni accents. Un filtre vide retient tout. */
export function filterPeople(people: readonly VigiePerson[], query: string | null | undefined): VigiePerson[] {
  const q = directoryKey((query ?? '').slice(0, DIRECTORY_FILTER_MAX));
  if (!q) {
    return [...people];
  }
  return people.filter((person) =>
    directoryKey(person.displayName).includes(q)
    || directoryKey(person.jobTitle).includes(q)
    || (person.subjects ?? []).some((subject) => directoryKey(subject.subjectName).includes(q)));
}

/** Les sujets d'une personne : en cours d'abord, clos ensuite, puis par nom. */
export function personSubjects(person: VigiePerson): VigiePersonSubject[] {
  const closed = (s: VigiePersonSubject) => (s.state === 'CLOSED' ? 1 : 0);
  return [...(person.subjects ?? [])].sort((a, b) =>
    closed(a) - closed(b) || a.subjectName.localeCompare(b.subjectName, 'fr', { sensitivity: 'base' }));
}

/** « 1 sujet », « 3 sujets », « 0 sujet ». */
export function subjectsLabel(person: VigiePerson): string {
  const count = person.subjects?.length ?? 0;
  return count > 1 ? `${count} sujets` : `${count} sujet`;
}

/** « dernier échange le 12 septembre » — l'année seulement si elle diffère ; rien si la date manque. */
export function interactionLabel(person: VigiePerson, now: Date = new Date()): string | null {
  const t = time(person.lastInteractionAt);
  if (t === null) {
    return null;
  }
  const date = new Date(t);
  const options: Intl.DateTimeFormatOptions = date.getFullYear() === now.getFullYear()
    ? { day: 'numeric', month: 'long' }
    : { day: 'numeric', month: 'long', year: 'numeric' };
  return `dernier échange le ${date.toLocaleDateString('fr-FR', options)}`;
}
