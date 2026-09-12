import {
  acceptedFormatsSentence,
  extensionsSummary,
  fileRejectionMessage,
  maxSizeLabel,
  mediaTypeName,
  refusedFormatName,
  rejectionSentences,
} from './file-format-names';

/** Types acceptés par le chemin « bibliothèque » (ce que le serveur publie aujourd'hui). */
const OCR_TYPES = ['application/pdf', 'image/png', 'image/jpeg', 'image/tiff'];

/** Le type MIME qu'un navigateur déclare pour un `.docx`. */
const DOCX = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

/** Repère un type MIME dans un texte : `mot/mot`. C'est précisément ce qui ne doit plus s'afficher. */
const LOOKS_LIKE_A_MEDIA_TYPE = /[a-z]+\/[a-z0-9.+-]+/;

describe('file-format-names (F-85 / SF-85-02)', () => {
  describe('le test de la feature', () => {
    it("un .docx refusé rend un message sans aucun type MIME, et qui nomme le PDF", () => {
      const message = fileRejectionMessage({ name: 'rapport.docx', type: DOCX }, OCR_TYPES);

      expect(message).not.toBeNull();
      expect(message).not.toMatch(LOOKS_LIKE_A_MEDIA_TYPE);
      expect(message).toContain('PDF');
      expect(message).toContain('Word (.docx)');
    });

    it('dit les trois choses, dans cet ordre : ce qui est refusé, ce qui passe, quoi faire', () => {
      const message = fileRejectionMessage({ name: 'rapport.docx', type: DOCX }, OCR_TYPES)!;

      const refused = message.indexOf('Les fichiers Word (.docx) ne sont pas acceptés.');
      const accepted = message.indexOf('Formats acceptés : PDF, images (PNG, JPEG, TIFF).');
      const todo = message.indexOf(
        'Exportez votre document en PDF : dans Word, Fichier > Enregistrer sous > PDF.',
      );

      expect(refused).toBe(0);
      expect(accepted).toBeGreaterThan(refused);
      expect(todo).toBeGreaterThan(accepted);
    });
  });

  describe('quand la table ne connaît pas', () => {
    it("retombe sur le type technique plutôt que d'inventer un nom", () => {
      expect(mediaTypeName('application/x-chose')).toBe('application/x-chose');
      expect(mediaTypeName('application/pdf')).toBe('PDF');
    });

    it("nomme un format inconnu par son extension, sans rien affirmer d'autre", () => {
      expect(refusedFormatName({ name: 'donnees.xyz', type: '' })).toBe('.xyz');
      expect(refusedFormatName({ name: 'rapport.docx', type: DOCX })).toBe('Word (.docx)');
    });

    it('sans extension, retombe sur le type déclaré', () => {
      expect(refusedFormatName({ name: 'fichier', type: 'application/x-chose' }))
        .toBe('application/x-chose');
    });

    it("sans extension ni type, n'affirme rien du tout", () => {
      expect(refusedFormatName({ name: 'fichier', type: '' })).toBeNull();

      const message = rejectionSentences({ name: 'fichier', type: '' }, OCR_TYPES);
      expect(message.startsWith("Ce fichier n'est pas accepté.")).toBeTrue();
      expect(message).toContain('PDF');
      // Le mot « inconnu » n'apparaît jamais : on dit ce qu'on sait, pas ce qu'on ignore.
      expect(message).not.toContain('inconnu');
    });

    it('un type inconnu reste lisible dans le message complet', () => {
      const message = rejectionSentences({ name: 'archive.xyz', type: 'application/x-chose' }, OCR_TYPES);

      expect(message).toContain('.xyz');
      expect(message).toContain('Formats acceptés : PDF, images (PNG, JPEG, TIFF).');
    });
  });

  describe('la phrase des formats acceptés', () => {
    it('groupe les images et garde les autres tels quels', () => {
      expect(acceptedFormatsSentence(OCR_TYPES)).toBe('PDF, images (PNG, JPEG, TIFF)');
    });

    it('groupe le texte', () => {
      expect(
        acceptedFormatsSentence([
          'application/pdf',
          'image/png',
          'text/plain',
          'text/markdown',
          'text/csv',
        ]),
      ).toBe('PDF, PNG, texte (TXT, Markdown, CSV)');
    });

    it("est dérivée de la liste : elle change quand le serveur change, sans toucher à l'écran", () => {
      expect(acceptedFormatsSentence([...OCR_TYPES, 'image/webp'])).toBe(
        'PDF, images (PNG, JPEG, TIFF, WebP)',
      );
    });

    it('nomme un type accepté inconnu par son écriture technique', () => {
      expect(acceptedFormatsSentence(['application/pdf', 'application/x-chose'])).toBe(
        'PDF, application/x-chose',
      );
    });
  });

  describe('quoi faire', () => {
    it("nomme l'application quand elle est connue", () => {
      expect(fileRejectionMessage({ name: 'budget.xlsx', type: '' }, OCR_TYPES)).toContain(
        'dans Excel, Fichier > Enregistrer sous > PDF',
      );
    });

    it("ne nomme pas d'application quand elle ne l'est pas", () => {
      const message = fileRejectionMessage({ name: 'notes.odt', type: '' }, OCR_TYPES)!;
      expect(message).toContain('Exportez votre document en PDF');
      expect(message).not.toContain('dans Word');
    });

    it("ne propose jamais le PDF quand le chemin ne l'accepte pas", () => {
      const message = fileRejectionMessage({ name: 'rapport.docx', type: DOCX }, [
        'text/plain',
        'text/csv',
      ])!;
      expect(message).not.toContain('PDF');
      expect(message).toContain('Choisissez un fichier dans un des formats acceptés.');
    });
  });

  describe('dire ce qui passe avant l’essai (SF-85-03)', () => {
    it('dit le plafond comme on le dit', () => {
      expect(maxSizeLabel(20 * 1024 * 1024)).toBe('20 Mo');
      expect(maxSizeLabel(32 * 1024 * 1024)).toBe('32 Mo');
      expect(maxSizeLabel(512 * 1024)).toBe('512 Ko');
      expect(maxSizeLabel(0)).toBe('');
      expect(maxSizeLabel(null)).toBe('');
    });

    it('résume une longue liste par ses premières extensions et son total', () => {
      expect(extensionsSummary(['txt', 'md', 'js', 'java', 'py', 'go'])).toBe(
        '.txt, .md, .js, .java… (6 formats)',
      );
    });

    it('ne résume pas une liste courte : elle tient en entier', () => {
      expect(extensionsSummary(['zip'])).toBe('.zip');
      expect(extensionsSummary(['txt', 'md'])).toBe('.txt, .md');
      expect(extensionsSummary([])).toBe('');
    });
  });

  describe('ce qui ne doit pas être refusé', () => {
    it('laisse passer un fichier acceptable', () => {
      expect(fileRejectionMessage({ name: 'contrat.pdf', type: 'application/pdf' }, OCR_TYPES))
        .toBeNull();
      expect(fileRejectionMessage({ name: 'scan.PNG', type: 'IMAGE/PNG' }, OCR_TYPES)).toBeNull();
      expect(
        fileRejectionMessage({ name: 'notes.txt', type: 'text/plain; charset=utf-8' }, [
          'text/plain',
        ]),
      ).toBeNull();
    });

    it("ne refuse rien quand la liste blanche n'est pas connue — l'écran n'invente pas", () => {
      expect(fileRejectionMessage({ name: 'rapport.docx', type: DOCX }, null)).toBeNull();
      expect(fileRejectionMessage({ name: 'rapport.docx', type: DOCX }, [])).toBeNull();
    });
  });
});
