/**
 * **Le seul endroit où un type MIME devient un mot** (F-85 / SF-85-02).
 *
 * <p>Le produit refusait un fichier en énumérant des types MIME : « Formats acceptés :
 * application/pdf, image/png, image/jpeg, image/tiff ». Exact, et inutilisable pour quelqu'un qui
 * pense en « Word », « PDF », « photo ». C'est le défaut du `-Djavax.net.ssl.trustStore=<fichier>`
 * de F-80 : nommer le remède sans donner le moyen.
 *
 * <p><b>La règle qui gouverne ce fichier</b> : quand la table ne connaît pas un format, elle
 * **retombe sur son écriture technique**. Mieux vaut un mot obscur qu'un mot faux — c'est la règle
 * du libellé d'interpréteur de SF-45-05, qui n'écrit jamais « inconnu ».
 *
 * <p>Le <b>serveur</b>, lui, garde son message exact : il s'adresse à un appelant d'API. La
 * traduction est un travail d'écran, et elle vit ici — à un seul endroit, pour que deux chemins de
 * dépôt ne puissent pas dire deux choses différentes du même refus.
 */

/**
 * Durée d'affichage d'un refus de format. Le message tient en trois phrases et donne une
 * manipulation à faire : les cinq secondes des autres notifications ne suffisent pas à le lire.
 */
export const REFUSAL_SNACK_DURATION_MS = 10000;

/** Un fichier, réduit à ce dont la traduction a besoin (un `File` en est un). */
export interface NamedFile {
  name: string;
  type: string;
}

/** Nom courant des types acceptés par le produit, et des plus courants qu'il refuse. */
const MEDIA_TYPE_NAMES: Readonly<Record<string, string>> = {
  'application/pdf': 'PDF',
  'image/png': 'PNG',
  'image/jpeg': 'JPEG',
  'image/tiff': 'TIFF',
  'image/gif': 'GIF',
  'image/webp': 'WebP',
  'image/bmp': 'BMP',
  'image/heic': 'HEIC',
  'image/svg+xml': 'SVG',
  'text/plain': 'TXT',
  'text/markdown': 'Markdown',
  'text/csv': 'CSV',
  'application/json': 'JSON',
  'application/zip': 'archive ZIP',
};

/**
 * Nom courant d'un format, vu depuis son extension — ce que l'utilisateur lit dans son explorateur
 * de fichiers, et ce qu'il dira au téléphone.
 */
const EXTENSION_NAMES: Readonly<Record<string, string>> = {
  docx: 'Word',
  doc: 'Word',
  dotx: 'Word',
  xlsx: 'Excel',
  xls: 'Excel',
  pptx: 'PowerPoint',
  ppt: 'PowerPoint',
  odt: 'OpenDocument texte',
  ods: 'OpenDocument classeur',
  odp: 'OpenDocument présentation',
  rtf: 'RTF',
  pages: 'Pages',
  numbers: 'Numbers',
  key: 'Keynote',
  epub: 'EPUB',
  zip: 'archive ZIP',
  rar: 'archive RAR',
  '7z': 'archive 7z',
  heic: 'HEIC',
  psd: 'Photoshop',
  ai: 'Illustrator',
};

/**
 * Applications à nommer dans le remède, par extension. Nommer le bouton exact (« dans Word, Fichier
 * > Enregistrer sous > PDF ») est ce qui sépare un message utile d'un message juste.
 */
const EXPORT_APPLICATIONS: Readonly<Record<string, string>> = {
  docx: 'Word',
  doc: 'Word',
  dotx: 'Word',
  xlsx: 'Excel',
  xls: 'Excel',
  pptx: 'PowerPoint',
  ppt: 'PowerPoint',
};

/** Extensions de documents bureautiques : ceux dont l'export en PDF est à un clic. */
const OFFICE_EXTENSIONS: readonly string[] = [
  'docx', 'doc', 'dotx', 'xlsx', 'xls', 'pptx', 'ppt',
  'odt', 'ods', 'odp', 'rtf', 'pages', 'numbers', 'key', 'epub',
];

/** Extension d'un nom de fichier, en minuscules, ou `null` s'il n'en a pas. */
export function fileExtension(filename: string): string | null {
  const lastDot = filename.lastIndexOf('.');
  if (lastDot <= 0 || lastDot === filename.length - 1) {
    return null;
  }
  return filename.slice(lastDot + 1).toLowerCase();
}

/** Type MIME normalisé (minuscules, sans paramètre `; charset=…`), ou `null` s'il est absent. */
export function normalizeMediaType(mediaType: string | null | undefined): string | null {
  if (!mediaType) {
    return null;
  }
  const normalized = mediaType.split(';')[0].trim().toLowerCase();
  return normalized.length > 0 ? normalized : null;
}

/**
 * Nom courant d'un type MIME accepté. **Retombe sur le type technique** quand la table ne le connaît
 * pas : mieux vaut un mot obscur qu'un mot faux.
 */
export function mediaTypeName(mediaType: string): string {
  const normalized = normalizeMediaType(mediaType);
  if (!normalized) {
    return '';
  }
  return MEDIA_TYPE_NAMES[normalized] ?? normalized;
}

/**
 * Nom du format **refusé**, tel qu'on va le dire à l'utilisateur : « Word (.docx) », « .xyz », ou à
 * défaut le type MIME déclaré. Ne suppose jamais rien : sans extension connue et sans type déclaré,
 * il n'y a rien à nommer et la fonction rend `null`.
 *
 * <p>L'extension passe **avant** le type MIME : le navigateur annonce parfois
 * `application/octet-stream` pour un `.docx`, alors que l'extension est ce que l'utilisateur voit.
 */
export function refusedFormatName(file: NamedFile): string | null {
  const extension = fileExtension(file.name);
  if (extension) {
    const known = EXTENSION_NAMES[extension];
    return known ? `${known} (.${extension})` : `.${extension}`;
  }
  const mediaType = normalizeMediaType(file.type);
  if (mediaType) {
    return MEDIA_TYPE_NAMES[mediaType] ?? mediaType;
  }
  return null;
}

/**
 * Énonce les formats acceptés en noms courants — « PDF, images (PNG, JPEG, TIFF) » — à partir de la
 * liste du serveur. La phrase change donc quand la configuration change, sans toucher à l'écran.
 */
export function acceptedFormatsSentence(mediaTypes: readonly string[]): string {
  const families = new Map<string, string[]>();
  for (const raw of mediaTypes) {
    const mediaType = normalizeMediaType(raw);
    if (!mediaType) {
      continue;
    }
    const family = mediaType.startsWith('image/')
      ? 'images'
      : mediaType.startsWith('text/')
        ? 'texte'
        : '';
    const members = families.get(family);
    if (members) {
      members.push(mediaTypeName(mediaType));
    } else {
      families.set(family, [mediaTypeName(mediaType)]);
    }
  }
  const parts: string[] = [];
  for (const [family, members] of families) {
    if (!family) {
      parts.push(...members);
    } else if (members.length === 1) {
      parts.push(members[0]);
    } else {
      parts.push(`${family} (${members.join(', ')})`);
    }
  }
  return parts.join(', ');
}

/**
 * Plafond de taille, dit comme on le dit — « 20 Mo » (F-85 / SF-85-03). Lu au serveur, jamais écrit
 * en dur : l'écran bibliothèque annonçait « 20 Mo » dans son gabarit, sans rapport avec la
 * configuration réelle.
 */
export function maxSizeLabel(maxBytes: number | null | undefined): string {
  if (!maxBytes || maxBytes <= 0) {
    return '';
  }
  const megabytes = Math.floor(maxBytes / (1024 * 1024));
  return megabytes >= 1 ? `${megabytes} Mo` : `${Math.max(1, Math.round(maxBytes / 1024))} Ko`;
}

/** Nombre d'extensions montrées avant de passer au compte total. Quatre se lisent ; soixante non. */
const EXTENSIONS_SHOWN = 4;

/**
 * Résume une longue liste d'extensions : les premières, puis le total (F-85 / SF-85-03).
 * « .txt, .md, .js, .java… (60 formats) ».
 */
export function extensionsSummary(extensions: readonly string[]): string {
  if (extensions.length === 0) {
    return '';
  }
  const shown = extensions.slice(0, EXTENSIONS_SHOWN).map((extension) => `.${extension}`);
  if (extensions.length <= EXTENSIONS_SHOWN) {
    return shown.join(', ');
  }
  return `${shown.join(', ')}… (${extensions.length} formats)`;
}

/** Le remède : ce que l'utilisateur doit faire, maintenant, pour que ça passe. */
function remedy(file: NamedFile, acceptsPdf: boolean): string {
  const extension = fileExtension(file.name);
  if (!acceptsPdf) {
    // Aucun PDF accepté sur ce chemin : proposer d'exporter en PDF serait un conseil faux.
    return 'Choisissez un fichier dans un des formats acceptés.';
  }
  if (extension && OFFICE_EXTENSIONS.includes(extension)) {
    const application = EXPORT_APPLICATIONS[extension];
    return application
      ? `Exportez votre document en PDF : dans ${application}, Fichier > Enregistrer sous > PDF.`
      : 'Exportez votre document en PDF depuis l’application qui l’a créé.';
  }
  return 'Exportez-le en PDF avant de le déposer.';
}

/**
 * Le message de refus, en trois temps : ce qui a été refusé, ce qui passe, quoi faire. Rend `null`
 * si le fichier est acceptable, ou si la liste blanche n'est pas connue — dans ce dernier cas
 * l'écran n'a rien de vrai à dire, et il laisse parler le serveur.
 */
export function fileRejectionMessage(
  file: NamedFile,
  allowedMediaTypes: readonly string[] | null | undefined,
): string | null {
  if (!allowedMediaTypes || allowedMediaTypes.length === 0) {
    return null;
  }
  const mediaType = normalizeMediaType(file.type);
  const allowed = allowedMediaTypes.map((type) => normalizeMediaType(type));
  if (mediaType && allowed.includes(mediaType)) {
    return null;
  }
  return rejectionSentences(file, allowedMediaTypes);
}

/**
 * Le même message, mais sans se demander si le fichier passe : utilisé pour traduire un `415` déjà
 * rendu par le serveur.
 */
export function rejectionSentences(
  file: NamedFile,
  allowedMediaTypes: readonly string[],
): string {
  const refused = refusedFormatName(file);
  const what = refused
    ? `Les fichiers ${refused} ne sont pas acceptés.`
    : "Ce fichier n'est pas accepté.";
  const accepted = `Formats acceptés : ${acceptedFormatsSentence(allowedMediaTypes)}.`;
  const acceptsPdf = allowedMediaTypes.some((type) => normalizeMediaType(type) === 'application/pdf');
  return `${what} ${accepted} ${remedy(file, acceptsPdf)}`;
}
