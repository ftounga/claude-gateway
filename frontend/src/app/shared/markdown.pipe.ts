import { Pipe, PipeTransform, inject } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { marked } from 'marked';
import DOMPurify from 'dompurify';

/**
 * Tous les liens rendus s'ouvrent dans un nouvel onglet, sans fuite d'`opener` (sécurité).
 * Le hook est enregistré une seule fois au chargement du module (idempotent à l'échelle du bundle).
 */
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.nodeName === 'A') {
    node.setAttribute('target', '_blank');
    node.setAttribute('rel', 'noopener noreferrer');
  }
});

/**
 * Convertit du Markdown en HTML **assaini**, prêt pour un binding `[innerHTML]`.
 *
 * <p>Le contenu provient d'un LLM : le HTML produit par {@code marked} est systématiquement passé
 * dans {@code DOMPurify} avant affichage (neutralise {@code <script>}, {@code onerror}, {@code javascript:}…).
 * Fonction pure exposée séparément du pipe pour être testable sans TestBed.</p>
 */
/**
 * Marqueur de métadonnées de fin de tour (F-52), lu par le produit et jamais par le lecteur
 * (F-125 / SF-125-01). Casse et espaces libres, corps quelconque jusqu'au `-->`, plusieurs
 * occurrences. On ne vise QUE ce marqueur précis : tout autre commentaire HTML est laissé tel quel.
 */
const TURN_METADATA_MARKER = /<!--\s*fin-de-tour\s*:[\s\S]*?-->/gi;

/**
 * Retire le marqueur de fin de tour du contenu assistant avant tout rendu (F-125 / SF-125-01).
 *
 * <p>Défense en profondeur : le backend le retire déjà des nouvelles réponses, mais les messages
 * historiques déjà persistés le portent encore. La tenue de la carte se fait en coulisse ; le
 * marqueur ne doit jamais être visible ni « expliqué » dans le fil.</p>
 */
export function stripTurnMetadata(value: string): string {
  return value.replace(TURN_METADATA_MARKER, '').replace(/\n{3,}/g, '\n\n').trim();
}

export function renderMarkdown(value: string | null | undefined): string {
  if (!value) {
    return '';
  }
  const rawHtml = marked.parse(stripTurnMetadata(value), { async: false }) as string;
  return DOMPurify.sanitize(rawHtml, { ADD_ATTR: ['target'] });
}

/**
 * Pipe `markdown` : rend le Markdown des réponses de l'assistant dans le fil de chat (F-02 / SF-02-03).
 * Le résultat est marqué {@link SafeHtml} <b>après</b> assainissement DOMPurify (on ne s'en remet pas
 * au seul sanitizer d'Angular, pour conserver la mise en forme voulue tout en neutralisant le danger).
 */
@Pipe({ name: 'markdown', standalone: true })
export class MarkdownPipe implements PipeTransform {
  private readonly sanitizer = inject(DomSanitizer);

  transform(value: string | null | undefined): SafeHtml {
    return this.sanitizer.bypassSecurityTrustHtml(renderMarkdown(value));
  }
}
