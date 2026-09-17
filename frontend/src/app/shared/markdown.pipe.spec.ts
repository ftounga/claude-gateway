import { SecurityContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { DomSanitizer } from '@angular/platform-browser';

import { MarkdownPipe, renderMarkdown, stripTurnMetadata } from './markdown.pipe';

describe('renderMarkdown', () => {
  it('rend titres, gras, listes et code', () => {
    expect(renderMarkdown('## Titre')).toContain('<h2');
    expect(renderMarkdown('**gras**')).toContain('<strong>');
    expect(renderMarkdown('- a\n- b')).toContain('<li>');
    expect(renderMarkdown('`code`')).toContain('<code>');
  });

  it('assainit le HTML dangereux (script neutralisé, onerror supprimé)', () => {
    expect(renderMarkdown('<script>alert(1)</script>')).not.toContain('<script>');
    expect(renderMarkdown('<img src=x onerror=alert(1)>')).not.toContain('onerror');
  });

  it('ouvre les liens dans un nouvel onglet avec noopener', () => {
    const out = renderMarkdown('[x](https://example.com)');
    expect(out).toContain('target="_blank"');
    expect(out).toContain('noopener');
  });

  it('renvoie une chaîne vide pour une entrée vide/undefined/null', () => {
    expect(renderMarkdown('')).toBe('');
    expect(renderMarkdown(undefined)).toBe('');
    expect(renderMarkdown(null)).toBe('');
  });

  it('retire le marqueur de fin de tour (F-125 / SF-125-01), sans montrer la plomberie', () => {
    const out = renderMarkdown(
      "Oui, ça s'est bien passé.\n\n<!-- fin-de-tour: promotion=aucune; promu=aucune; dette=0 -->",
    );
    expect(out).not.toContain('fin-de-tour');
    expect(out).not.toContain('promotion');
    expect(out).toContain("Oui, ça s'est bien passé.");
  });

  it('ne touche pas un autre commentaire HTML que le marqueur', () => {
    expect(stripTurnMetadata('Texte <!-- todo -->')).toBe('Texte <!-- todo -->');
    expect(stripTurnMetadata('A<!--FIN-DE-TOUR: dette=0 -->B')).toBe('AB');
  });
});

describe('MarkdownPipe', () => {
  it('renvoie du SafeHtml assaini', () => {
    const pipe = TestBed.runInInjectionContext(() => new MarkdownPipe());
    const sanitizer = TestBed.inject(DomSanitizer);

    const safe = pipe.transform('**x**');

    // SafeHtml opaque : on le ré-extrait via le sanitizer pour vérifier le HTML rendu.
    expect(sanitizer.sanitize(SecurityContext.HTML, safe)).toContain('<strong>');
  });
});
