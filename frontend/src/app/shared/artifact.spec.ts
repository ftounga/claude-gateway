import {
  ArtifactSourceMessage,
  artifactTypeFromLanguage,
  extractArtifacts,
  messageHasArtifacts,
} from './artifact';

function assistant(id: string, content: string): ArtifactSourceMessage {
  return { id, role: 'ASSISTANT', content };
}

describe('artifactTypeFromLanguage', () => {
  it('classe mail/email/eml comme mail', () => {
    expect(artifactTypeFromLanguage('mail')).toBe('mail');
    expect(artifactTypeFromLanguage('EMAIL')).toBe('mail');
    expect(artifactTypeFromLanguage('eml')).toBe('mail');
  });

  it('classe markdown/html/doc comme doc', () => {
    expect(artifactTypeFromLanguage('markdown')).toBe('doc');
    expect(artifactTypeFromLanguage('html')).toBe('doc');
    expect(artifactTypeFromLanguage('document')).toBe('doc');
  });

  it('classe le reste (ou absence) comme code', () => {
    expect(artifactTypeFromLanguage('typescript')).toBe('code');
    expect(artifactTypeFromLanguage('sql')).toBe('code');
    expect(artifactTypeFromLanguage(null)).toBe('code');
    expect(artifactTypeFromLanguage('')).toBe('code');
  });
});

describe('extractArtifacts', () => {
  it('extrait un bloc code avec langage conservé et id stable', () => {
    const artifacts = extractArtifacts([
      assistant('m1', 'Voici :\n```typescript\nconst a = 1;\n```\nFin.'),
    ]);
    expect(artifacts.length).toBe(1);
    expect(artifacts[0]).toEqual(
      jasmine.objectContaining({
        id: 'm1#0',
        messageId: 'm1',
        index: 0,
        type: 'code',
        language: 'typescript',
        content: 'const a = 1;',
      }),
    );
    expect(artifacts[0].title).toBe('Code (typescript)');
  });

  it('classe les blocs mail et doc selon leur token', () => {
    const artifacts = extractArtifacts([
      assistant('m1', '```mail\nBonjour,\nCordialement\n```'),
      assistant('m2', '```markdown\n# Titre\n```'),
    ]);
    expect(artifacts.map((a) => a.type)).toEqual(['mail', 'doc']);
  });

  it('ignore les messages utilisateur', () => {
    const artifacts = extractArtifacts([
      { id: 'u1', role: 'USER', content: '```js\nalert(1)\n```' },
    ]);
    expect(artifacts.length).toBe(0);
  });

  it('numérote plusieurs blocs d’un même message avec des ids distincts', () => {
    const artifacts = extractArtifacts([
      assistant('m1', '```js\na\n```\ntexte\n```sql\nSELECT 1\n```'),
    ]);
    expect(artifacts.map((a) => a.id)).toEqual(['m1#0', 'm1#1']);
    expect(artifacts.map((a) => a.language)).toEqual(['js', 'sql']);
  });

  it('ignore un bloc vide', () => {
    const artifacts = extractArtifacts([assistant('m1', '```js\n\n```')]);
    expect(artifacts.length).toBe(0);
  });

  it('ignore un fence non clos (streaming en cours)', () => {
    const artifacts = extractArtifacts([assistant('m1', '```js\nconst a = 1;')]);
    expect(artifacts.length).toBe(0);
  });

  it('gère l’absence de token de langage (type code, langage null)', () => {
    const artifacts = extractArtifacts([assistant('m1', '```\nplain\n```')]);
    expect(artifacts.length).toBe(1);
    expect(artifacts[0].language).toBeNull();
    expect(artifacts[0].type).toBe('code');
    expect(artifacts[0].title).toBe('Code');
  });
});

describe('messageHasArtifacts', () => {
  it('détecte la présence d’au moins un artefact', () => {
    expect(messageHasArtifacts(assistant('m1', '```js\na\n```'))).toBeTrue();
    expect(messageHasArtifacts(assistant('m1', 'aucun bloc'))).toBeFalse();
  });
});
