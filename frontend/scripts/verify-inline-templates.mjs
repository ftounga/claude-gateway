#!/usr/bin/env node
// F-197 / SF-197-01 — garde des templates inline.
//
// Dans un template inline écrit en backticks (`template: \`...\``), la séquence `\'` est
// « cuite » par JavaScript en `'` AVANT qu'Angular ne lise le template. Placée dans une
// interpolation `{{ ... }}` ou une liaison (`[x]="..."`, `(x)="..."`, `@if (...)`), elle
// laisse un nombre impair d'apostrophes : Angular ne trouve plus la fin de l'expression et
// rend l'interpolation BRUTE à l'écran (« {{ p.amendment ? … }} »), sans erreur de build.
//
// Ce script échoue (code 1) si une telle séquence est trouvée dans une expression d'un
// template inline. Le texte statique hors expression (« l\'infra ») reste inoffensif.
//
// Usage : node scripts/verify-inline-templates.mjs [racine=src]

import fs from 'node:fs';
import path from 'node:path';

const root = process.argv[2] ?? 'src';

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (p.endsWith('.ts') && !p.endsWith('.spec.ts')) out.push(p);
  }
  return out;
}

/** Extrait le texte BRUT de chaque template inline en backticks, avec son offset. */
export function inlineTemplates(source) {
  const found = [];
  const re = /template:\s*`/g;
  let m;
  while ((m = re.exec(source))) {
    const start = m.index + m[0].length;
    let j = start;
    while (j < source.length && source[j] !== '`') {
      if (source[j] === '\\') j++;
      j++;
    }
    found.push({ start, text: source.slice(start, j) });
    re.lastIndex = j + 1;
  }
  return found;
}

// `\'` précédé d'un nombre PAIR de barres (0, 2…) : JS le cuit en `'` nu. `\\'` (cuit en `\'`)
// est, lui, l'échappement Angular correct et n'est pas signalé.
const COOKED_TO_BARE_QUOTE = /(?:^|[^\\])(?:\\\\)*\\'/;

/** Retourne les expressions (interpolations, liaisons, blocs de contrôle) contenant `\'`. */
export function escapedQuotesInExpressions(template) {
  const hits = [];
  const patterns = [
    /\{\{([\s\S]*?)\}\}/g, // interpolation
    /[\[(][\w.\-@]+[\])]+\s*=\s*"([^"]*)"/g, // [prop]="…", (event)="…", [(model)]="…"
    /@(?:if|else if|for|switch|case|defer)\s*\(([^)]*\))/g, // blocs de contrôle
  ];
  for (const re of patterns) {
    let m;
    while ((m = re.exec(template))) {
      if (COOKED_TO_BARE_QUOTE.test(m[1])) hits.push({ index: m.index, expr: m[0] });
    }
  }
  return hits;
}

function lineOf(source, index) {
  return source.slice(0, index).split('\n').length;
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname);
if (isMain) {
  const problems = [];
  let scanned = 0;
  for (const file of walk(root)) {
    const source = fs.readFileSync(file, 'utf8');
    for (const t of inlineTemplates(source)) {
      scanned++;
      for (const hit of escapedQuotesInExpressions(t.text)) {
        problems.push(`${file}:${lineOf(source, t.start + hit.index)} — ${hit.expr.replace(/\s+/g, ' ').slice(0, 140)}`);
      }
    }
  }
  if (problems.length) {
    console.error(`verify-inline-templates : ${problems.length} expression(s) avec \\' dans un template inline`);
    console.error("(JavaScript transforme \\' en ' : Angular affichera l'expression brute « {{ … }} »).");
    console.error('Sortez le libellé dans une constante / un computed TS.');
    for (const p of problems) console.error('  ' + p);
    process.exit(1);
  }
  console.log(`verify-inline-templates : OK (${scanned} templates inline vérifiés)`);
}
