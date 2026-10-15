/**
 * Test de la garde des templates inline (F-197 / SF-197-01).
 * Les échantillons sont lus en texte BRUT, tel que le script lit les fichiers .ts.
 */
import assert from 'node:assert/strict';
import { escapedQuotesInExpressions, inlineTemplates } from './verify-inline-templates.mjs';

const bs = '\\';

// Le défaut d'origine : `\'` dans une interpolation.
const amendement = `{{ p.amendment ? 'Valider l${bs}'amendement et reprendre' : 'Valider le plan et lancer' }}`;
assert.equal(escapedQuotesInExpressions(amendement).length, 1, 'interpolation avec \\\' signalée');

// Interpolation sur plusieurs lignes.
assert.equal(escapedQuotesInExpressions(`{{ a\n ? 'l${bs}'infra'\n : 'b' }}`).length, 1, 'interpolation multiligne signalée');

// Liaison de propriété et bloc de contrôle.
assert.equal(escapedQuotesInExpressions(`<b [title]="'l${bs}'aide'"></b>`).length, 1, 'liaison signalée');
assert.equal(escapedQuotesInExpressions(`@if (x === 'l${bs}'a') {}`).length, 1, 'bloc de contrôle signalé');

// Échappement Angular correct (`\\'` brut, cuit en `\'`) : non signalé.
assert.equal(escapedQuotesInExpressions(`<b [attr.aria-label]="'Ouvrir l${bs}${bs}'aperçu'"></b>`).length, 0, '\\\\\' correct non signalé');

// Texte statique hors expression : inoffensif, non signalé.
assert.equal(escapedQuotesInExpressions(`<small>changement d${bs}'infra</small> {{ x }}`).length, 0, 'texte statique non signalé');

// Libellé sorti en TS : rien à signaler.
assert.equal(escapedQuotesInExpressions('{{ validateLabel() }}').length, 0, 'computed non signalé');

// Extraction : un template inline par composant, texte brut conservé.
const source = "@Component({ template: `<p>{{ a ? 'x' : 'l" + bs + "'y' }}</p>`, styles: `p{}` })";
const templates = inlineTemplates(source);
assert.equal(templates.length, 1);
assert.equal(escapedQuotesInExpressions(templates[0].text).length, 1);

console.log('verify-inline-templates.test : OK');
