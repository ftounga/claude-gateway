/**
 * Source unique des informations légales de l'éditeur (F-29 SF-29-03).
 *
 * Toutes les pages légales lisent ces valeurs : une correction de raison sociale,
 * d'adresse ou de dirigeant se fait ici et nulle part ailleurs.
 *
 * Origine des données — aucune valeur n'est inventée :
 *   - société, forme juridique, capital, SIREN, siège, président : extrait Kbis du 23/12/2025
 *   - directeur de la publication et e-mail : fournis explicitement par l'éditeur
 *   - hébergeur : entité européenne d'AWS (infrastructure eu-west-3, Paris)
 *
 * Le numéro de TVA intracommunautaire n'est volontairement PAS publié : il est absent
 * du Kbis et l'assujettissement n'est pas confirmé. Publier un numéro pour une société
 * en franchise en base serait une mention fausse.
 */
export interface LegalCompany {
  readonly name: string;
  readonly legalForm: string;
  readonly capital: string;
  readonly siren: string;
  readonly rcsCity: string;
  readonly registeredAt: string;
  readonly address: string;
  readonly president: string;
  readonly publicationDirector: string;
  readonly contactEmail: string;
  readonly activity: string;
}

export interface LegalHost {
  readonly name: string;
  readonly address: string;
  readonly region: string;
}

export const LEGAL_COMPANY: LegalCompany = {
  name: 'NG-CONSULTING',
  legalForm: 'Société par actions simplifiée à associé unique (SASU)',
  capital: '100,00 €',
  siren: '995 322 450',
  rcsCity: 'Paris',
  registeredAt: '23 décembre 2025',
  address: '60 rue François 1er, 75008 Paris, France',
  president: 'NG-ACQUISITIONS, SASU immatriculée au R.C.S. de Paris sous le numéro 993 599 836',
  publicationDirector: 'Franck TOUNGA',
  contactEmail: 'tounga.franck@ng-itconsulting.com',
  activity:
    'Conception et développement de logiciels et outils informatiques, ainsi que leur exploitation et maintenance',
};

export const LEGAL_HOST: LegalHost = {
  name: 'Amazon Web Services EMEA SARL',
  address: '38 avenue John F. Kennedy, L-1855 Luxembourg',
  region: 'Europe (Paris) — eu-west-3, France',
};

/** Nom commercial du service, distinct de la raison sociale de l'éditeur. */
export const SERVICE_NAME = 'Claude Portal';

/** Date de dernière mise à jour des documents légaux, affichée en pied de page. */
export const LEGAL_LAST_UPDATE = '23 août 2026';
