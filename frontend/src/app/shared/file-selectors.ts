/**
 * Sources uniques des attributs `accept` qui ne viennent pas du serveur (F-85 / SF-85-01).
 *
 * <p>Deux listes blanches vivent en configuration serveur et sont lues par `FileFormatsService`.
 * Les deux autres chemins n'ont pas de liste serveur à lire : le fichier de projet (liste texte
 * `WORKSPACE_TEXT_EXTENSIONS`, déjà à source unique dans `atelier.types.ts`) et l'import d'archive,
 * qui n'a pas de liste MIME côté serveur — l'archive est validée en la décompressant. Pour ce
 * dernier, la source unique est ici : le sélecteur **et** la mention des formats acceptés la
 * lisent, au lieu de répéter chacun `.zip`.
 */

/** Extensions d'archive importables comme projet. */
export const ARCHIVE_EXTENSIONS: readonly string[] = ['zip'];

/** Attribut `accept` du sélecteur d'archive, dérivé de {@link ARCHIVE_EXTENSIONS}. */
export const ARCHIVE_ACCEPT = [
  ...ARCHIVE_EXTENSIONS.map((extension) => `.${extension}`),
  'application/zip',
].join(',');
