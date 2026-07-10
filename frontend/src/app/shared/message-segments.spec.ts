import { BlockSegment } from './copy-block.model';
import { copyBlockTypeFromLanguage, splitMessageSegments } from './message-segments';

/** Renvoie les blocs copiables des segments (filtre la prose). */
function blocks(content: string): BlockSegment['block'][] {
  return splitMessageSegments(content)
    .filter((s): s is BlockSegment => s.kind === 'block')
    .map((s) => s.block);
}

describe('copyBlockTypeFromLanguage', () => {
  it('classe mail/email/eml comme mail', () => {
    expect(copyBlockTypeFromLanguage('mail')).toBe('mail');
    expect(copyBlockTypeFromLanguage('EMAIL')).toBe('mail');
    expect(copyBlockTypeFromLanguage('eml')).toBe('mail');
  });

  it('classe markdown/html/doc comme doc', () => {
    expect(copyBlockTypeFromLanguage('markdown')).toBe('doc');
    expect(copyBlockTypeFromLanguage('html')).toBe('doc');
    expect(copyBlockTypeFromLanguage('document')).toBe('doc');
  });

  it('classe le reste (ou absence) comme code', () => {
    expect(copyBlockTypeFromLanguage('typescript')).toBe('code');
    expect(copyBlockTypeFromLanguage('sql')).toBe('code');
    expect(copyBlockTypeFromLanguage(null)).toBe('code');
    expect(copyBlockTypeFromLanguage('')).toBe('code');
  });
});

describe('splitMessageSegments', () => {
  it('renvoie une liste vide pour un contenu vide', () => {
    expect(splitMessageSegments('')).toEqual([]);
    expect(splitMessageSegments(null)).toEqual([]);
  });

  it('renvoie un seul segment prose quand il n’y a aucun bloc', () => {
    const segments = splitMessageSegments('Bonjour, voici du **texte** sans bloc.');
    expect(segments.length).toBe(1);
    expect(segments[0]).toEqual({ kind: 'prose', text: 'Bonjour, voici du **texte** sans bloc.' });
  });

  it('découpe prose + bloc code en préservant le langage', () => {
    const segments = splitMessageSegments('Voici :\n```typescript\nconst a = 1;\n```\nFin.');
    expect(segments.map((s) => s.kind)).toEqual(['prose', 'block', 'prose']);
    const block = (segments[1] as BlockSegment).block;
    expect(block).toEqual(
      jasmine.objectContaining({ type: 'code', language: 'typescript', content: 'const a = 1;' }),
    );
    expect(block.title).toBe('Code (typescript)');
  });

  it('préserve l’ordre de plusieurs blocs interleavés', () => {
    const segments = splitMessageSegments('A\n```js\nx\n```\nB\n```sql\nSELECT 1\n```\nC');
    expect(segments.map((s) => s.kind)).toEqual(['prose', 'block', 'prose', 'block', 'prose']);
    expect(blocks('A\n```js\nx\n```\nB\n```sql\nSELECT 1\n```\nC').map((b) => b.language)).toEqual([
      'js',
      'sql',
    ]);
  });

  it('classe les blocs mail et doc selon leur token', () => {
    expect(blocks('```mail\nBonjour,\nCordialement\n```').map((b) => b.type)).toEqual(['mail']);
    expect(blocks('```markdown\n# Titre\n```').map((b) => b.type)).toEqual(['doc']);
  });

  it('traite un fence vide comme de la prose (aucun bloc)', () => {
    expect(blocks('```js\n\n```').length).toBe(0);
  });

  it('traite un fence non clos comme de la prose (streaming en cours)', () => {
    const segments = splitMessageSegments('```js\nconst a = 1;');
    expect(segments.map((s) => s.kind)).toEqual(['prose']);
    expect(blocks('```js\nconst a = 1;').length).toBe(0);
  });

  it('gère l’absence de token de langage (type code, libellé « Code »)', () => {
    const [block] = blocks('```\nplain\n```');
    expect(block.language).toBeNull();
    expect(block.type).toBe('code');
    expect(block.title).toBe('Code');
  });
});
