/**
 * La preuve FICHIER (F-268 / SF-268-01) — la partie PURE : d'un extrait réel de fichier (lu sur le poste
 * par la gateway, secrets déjà masqués) à la page HTML d'une fenêtre d'éditeur sombre, une par fichier.
 *
 * Le style suit la règle « Style des preuves » du compte : fond navy #0B1020, coloration syntaxique,
 * numéros de ligne, ligne(s) en cause surlignée(s) en orange, annotation courte. Comme pour le terminal,
 * rien dans l'image ne dit qu'elle est générée (pas de pied, pas de mention).
 *
 * La coloration est volontairement simple (jetons par ligne, état pour les commentaires de bloc) : elle
 * sert la lecture d'un extrait, pas l'analyse du langage. Aucune dépendance : `node --test`.
 */

"use strict";

const MAX_FILES = 6;
const MAX_LINES = 80;
const MAX_LINE_CHARS = 400;
const MAX_PATH_CHARS = 512;
const MAX_NOTE_CHARS = 200;
const MAX_BODY_BYTES = 2 * 1024 * 1024;

class EditorError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

// ------------------------------------------------------------------ thème

const THEME = {
  bg: "#0B1020",
  bar: "#070B17",
  tab: "#0B1020",
  border: "#1C2540",
  fg: "#E4E8F4",
  gutter: "#4F5A78",
  crumbs: "#8A94B2",
  accent: "#FF8A3D",
  hlBg: "rgba(255,138,61,.16)",
  noteBg: "#151D36",
  noteFg: "#FFE2CC",
  font: "'CaskaydiaCove Nerd Font Mono', 'DejaVu Sans Mono', monospace",
  fontSize: 15,
  lineHeight: 22,
};

/** Couleur par genre de jeton. */
const TOKEN_COLORS = {
  comment: "#6B7699",
  string: "#A8DC76",
  number: "#F7A26C",
  keyword: "#C79BFF",
  constant: "#FF7A93",
  attr: "#7FB4FF",
  type: "#FFD173",
  punct: "#8FD8F0",
};

// ------------------------------------------------------------------ langages

const WORDS = (s) => new Set(s.split(/\s+/).filter(Boolean));

const C_LIKE = { line: ["//"], block: ["/*", "*/"], quotes: ["\"", "'"] };

const LANGS = {
  hcl: {
    line: ["#", "//"], block: ["/*", "*/"], quotes: ["\""],
    keywords: WORDS("resource data variable output module provider locals terraform backend required_providers "
      + "dynamic content for in if for_each count depends_on lifecycle moved import check"),
    constants: WORDS("true false null"),
    attrBeforeEq: true,
  },
  yaml: {
    line: ["#"], quotes: ["\"", "'"], constants: WORDS("true false null yes no on off ~"), yamlKeys: true,
  },
  json: { quotes: ["\""], constants: WORDS("true false null"), jsonKeys: true },
  js: {
    ...C_LIKE, quotes: ["\"", "'", "`"],
    keywords: WORDS("const let var function return if else for while do switch case break continue new class "
      + "extends import export from default async await try catch finally throw typeof instanceof of in this "
      + "interface type enum implements public private protected readonly static get set"),
    constants: WORDS("true false null undefined NaN Infinity"),
  },
  java: {
    ...C_LIKE,
    keywords: WORDS("package import public private protected static final abstract class interface enum record "
      + "extends implements return if else for while do switch case break continue new try catch finally throw "
      + "throws this super void synchronized volatile var default instanceof yield sealed permits"),
    constants: WORDS("true false null"),
    types: WORDS("int long double float boolean char byte short String Integer Long Boolean List Map Set Optional UUID"),
  },
  kotlin: {
    ...C_LIKE, quotes: ["\""],
    keywords: WORDS("package import fun val var class object interface data sealed open override private public "
      + "internal protected return if else when for while do try catch finally throw is as in companion"),
    constants: WORDS("true false null"),
  },
  csharp: {
    ...C_LIKE,
    keywords: WORDS("using namespace public private protected internal static readonly class interface struct "
      + "record enum return if else for foreach while do switch case break continue new try catch finally throw "
      + "var async await this base void override virtual abstract sealed get set"),
    constants: WORDS("true false null"),
  },
  go: {
    ...C_LIKE, quotes: ["\"", "`", "'"],
    keywords: WORDS("package import func return if else for range switch case default break continue go defer "
      + "select chan map struct interface type var const"),
    constants: WORDS("true false nil iota"),
  },
  python: {
    line: ["#"], quotes: ["\"", "'"],
    keywords: WORDS("def class return if elif else for while in not and or is import from as with try except "
      + "finally raise pass break continue lambda yield async await global nonlocal assert del"),
    constants: WORDS("True False None self"),
  },
  ruby: {
    line: ["#"], quotes: ["\"", "'"],
    keywords: WORDS("def class module end if elsif else unless while until for in do return yield begin rescue "
      + "ensure require include attr_accessor self"),
    constants: WORDS("true false nil"),
  },
  php: {
    ...C_LIKE, line: ["//", "#"],
    keywords: WORDS("function class public private protected static return if else elseif foreach for while new "
      + "use namespace echo try catch throw extends implements"),
    constants: WORDS("true false null"),
  },
  shell: {
    line: ["#"], quotes: ["\"", "'"],
    keywords: WORDS("if then else elif fi for in do done while until case esac function return export local "
      + "readonly set unset source exit"),
    constants: WORDS("true false"),
    variables: true,
  },
  sql: {
    line: ["--"], block: ["/*", "*/"], quotes: ["'"], caseInsensitive: true,
    keywords: WORDS("select from where and or not insert into values update set delete create table alter drop "
      + "index join left right inner outer on as group by order having limit offset distinct union all case when "
      + "then else end primary key foreign references grant revoke with is in like between exists"),
    constants: WORDS("null true false"),
  },
  xml: { block: ["<!--", "-->"], quotes: ["\"", "'"], markup: true },
  ini: { line: ["#", ";"], quotes: ["\"", "'"], constants: WORDS("true false"), iniKeys: true },
  docker: {
    line: ["#"], quotes: ["\"", "'"], caseInsensitive: false,
    keywords: WORDS("FROM RUN CMD LABEL EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD "
      + "STOPSIGNAL HEALTHCHECK SHELL AS"),
    variables: true,
  },
  plain: {},
};

const EXTENSIONS = {
  tf: "hcl", tfvars: "hcl", hcl: "hcl", nomad: "hcl",
  yaml: "yaml", yml: "yaml",
  json: "json", jsonc: "json",
  js: "js", mjs: "js", cjs: "js", ts: "js", tsx: "js", jsx: "js",
  java: "java", kt: "kotlin", kts: "kotlin", cs: "csharp", go: "go",
  py: "python", rb: "ruby", php: "php",
  sh: "shell", bash: "shell", zsh: "shell", ps1: "shell",
  sql: "sql",
  xml: "xml", html: "xml", htm: "xml", svg: "xml", pom: "xml",
  properties: "ini", ini: "ini", conf: "ini", cfg: "ini", env: "ini", toml: "ini",
};

/** Le langage d'un chemin, par son extension ou son nom ; `plain` à défaut. */
function languageOf(filePath) {
  const name = String(filePath || "").replace(/\\/g, "/").split("/").pop() || "";
  const lower = name.toLowerCase();
  if (lower === "dockerfile" || lower.startsWith("dockerfile.") || lower.endsWith(".dockerfile")) {
    return "docker";
  }
  if (lower === ".env" || lower.startsWith(".env.")) {
    return "ini";
  }
  const dot = lower.lastIndexOf(".");
  return dot >= 0 ? EXTENSIONS[lower.slice(dot + 1)] || "plain" : "plain";
}

// ------------------------------------------------------------------ jetons

const NUMBER = /^-?(?:0x[0-9a-fA-F]+|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\b/;
const WORD = /^[A-Za-z_$][\w$]*/;
/** Mots à tiret (clés YAML/HCL/INI, balises). */
const HYPHEN_WORD = /^[A-Za-z_$][\w$-]*/;
const HYPHEN_LANGS = new Set(["hcl", "yaml", "ini", "xml"]);

/**
 * Découpe une ligne en jetons `{ text, kind }` (`kind` = genre de TOKEN_COLORS, ou null).
 *
 * @param {string} line
 * @param {object} lang configuration du langage
 * @param {{ inBlock: boolean }} state état porté d'une ligne à l'autre (commentaire de bloc ouvert)
 */
function tokenize(line, lang, state) {
  const tokens = [];
  const push = (text, kind) => {
    if (!text) {
      return;
    }
    const last = tokens[tokens.length - 1];
    if (last && last.kind === kind) {
      last.text += text;
    } else {
      tokens.push({ text, kind });
    }
  };
  let i = 0;
  if (state.inBlock && lang.block) {
    const end = line.indexOf(lang.block[1]);
    if (end < 0) {
      push(line, "comment");
      return tokens;
    }
    push(line.slice(0, end + lang.block[1].length), "comment");
    i = end + lang.block[1].length;
    state.inBlock = false;
  }
  const firstWordIndex = line.search(/\S/);
  while (i < line.length) {
    const rest = line.slice(i);
    const ch = line[i];
    // Commentaire de ligne.
    const lineComment = (lang.line || []).find((m) => rest.startsWith(m)
      && (m !== "#" || lang !== LANGS.shell || i === 0 || /\s/.test(line[i - 1])));
    if (lineComment) {
      push(rest, "comment");
      break;
    }
    // Commentaire de bloc.
    if (lang.block && rest.startsWith(lang.block[0])) {
      const end = rest.indexOf(lang.block[1], lang.block[0].length);
      if (end < 0) {
        push(rest, "comment");
        state.inBlock = true;
        break;
      }
      push(rest.slice(0, end + lang.block[1].length), "comment");
      i += end + lang.block[1].length;
      continue;
    }
    // Chaîne.
    if ((lang.quotes || []).includes(ch)) {
      let j = i + 1;
      while (j < line.length && line[j] !== ch) {
        j += line[j] === "\\" ? 2 : 1;
      }
      const text = line.slice(i, Math.min(line.length, j + 1));
      const after = line.slice(i + text.length);
      const isKey = (lang.jsonKeys || lang.yamlKeys) && /^\s*:/.test(after);
      push(text, isKey ? "attr" : "string");
      i += text.length;
      continue;
    }
    // Balisage (XML/HTML) : noms de balise et attributs.
    if (lang.markup && ch === "<") {
      const m = /^<\/?[\w:.-]+/.exec(rest);
      if (m) {
        push("<", "punct");
        push(m[0].slice(1), "keyword");
        i += m[0].length;
        continue;
      }
    }
    if (lang.markup) {
      const attr = /^[\w:.-]+(?==)/.exec(rest);
      if (attr && i > 0 && /\s/.test(line[i - 1])) {
        push(attr[0], "attr");
        i += attr[0].length;
        continue;
      }
    }
    // Variables de shell.
    if (lang.variables && ch === "$") {
      const m = /^\$(?:\{[^}]*\}|[A-Za-z_]\w*|[0-9@#?*$!-])/.exec(rest);
      if (m) {
        push(m[0], "attr");
        i += m[0].length;
        continue;
      }
    }
    // Nombre.
    if (/[0-9-]/.test(ch) && (i === 0 || !/[\w$.]/.test(line[i - 1]))) {
      const m = NUMBER.exec(rest);
      if (m && !(m[0] === "-")) {
        push(m[0], "number");
        i += m[0].length;
        continue;
      }
    }
    // Mot.
    const w = (lang.hyphens ? HYPHEN_WORD : WORD).exec(rest);
    if (w) {
      const word = w[0].replace(/-+$/, "");
      const after = line.slice(i + word.length);
      const key = lang.caseInsensitive ? word.toLowerCase() : word;
      let kind = null;
      if ((lang.yamlKeys && i === firstWordIndex || lang.yamlKeys && /^-\s*$/.test(line.slice(firstWordIndex, i)))
        && /^\s*:(\s|$)/.test(after)) {
        kind = "attr";
      } else if (lang.iniKeys && i === firstWordIndex && /^\s*[=:]/.test(after)) {
        kind = "attr";
      } else if (lang.attrBeforeEq && /^\s*=(?!=)/.test(after)) {
        kind = "attr";
      } else if (lang.keywords && lang.keywords.has(key)) {
        kind = "keyword";
      } else if (lang.constants && lang.constants.has(key)) {
        kind = "constant";
      } else if (lang.types && lang.types.has(word)) {
        kind = "type";
      } else if (/^[A-Z][a-z0-9]+[A-Za-z0-9]*$/.test(word) && lang !== LANGS.plain && lang !== LANGS.yaml
        && lang !== LANGS.ini && lang !== LANGS.docker) {
        kind = "type";
      } else if (lang !== LANGS.plain && /^\s*\(/.test(after) && !lang.markup) {
        kind = "type";
      }
      push(word, kind);
      i += word.length;
      continue;
    }
    if (lang !== LANGS.plain && /[{}()[\]=:;,.<>+*/|&!?-]/.test(ch)) {
      push(ch, "punct");
    } else {
      push(ch, null);
    }
    i += 1;
  }
  return tokens;
}

// ------------------------------------------------------------------ entrée

function text(value, max) {
  if (value === undefined || value === null) {
    return "";
  }
  const s = String(value);
  return s.length > max ? s.slice(0, max) : s;
}

function int(value, fallback) {
  const n = Number(value);
  return Number.isInteger(n) ? n : fallback;
}

/**
 * Valide la demande. Ne garde que ce qui sert à l'image.
 *
 * @throws {EditorError} 400 si la demande est mal formée
 */
function parseFiles(payload) {
  if (!payload || typeof payload !== "object" || !Array.isArray(payload.files)) {
    throw new EditorError(400, "La liste des fichiers est manquante.");
  }
  if (payload.files.length === 0 || payload.files.length > MAX_FILES) {
    throw new EditorError(400, "Il faut 1 à " + MAX_FILES + " fichiers.");
  }
  return payload.files.map((f) => {
    if (!f || typeof f !== "object" || typeof f.path !== "string" || f.path.trim() === "") {
      throw new EditorError(400, "Chaque fichier doit porter son chemin.");
    }
    if (!Array.isArray(f.lines)) {
      throw new EditorError(400, "Chaque fichier doit porter ses lignes.");
    }
    const firstLine = Math.max(1, int(f.firstLine, 1));
    const lines = f.lines.slice(0, MAX_LINES).map((l) => {
      const s = String(l === null || l === undefined ? "" : l).replace(/\r$/, "").replace(/\t/g, "  ");
      return s.length > MAX_LINE_CHARS ? s.slice(0, MAX_LINE_CHARS - 1) + "…" : s;
    });
    const last = firstLine + lines.length - 1;
    const highlights = [...new Set((Array.isArray(f.highlights) ? f.highlights : [])
      .map((n) => int(n, -1)).filter((n) => n >= firstLine && n <= last))].sort((a, b) => a - b);
    return {
      path: text(f.path.trim(), MAX_PATH_CHARS),
      firstLine,
      lines,
      highlights,
      note: text(typeof f.note === "string" ? f.note.trim() : "", MAX_NOTE_CHARS),
      totalLines: Math.max(last, int(f.totalLines, last)),
    };
  });
}

// ------------------------------------------------------------------ HTML

function escapeHtml(s) {
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function tokensHtml(tokens) {
  return tokens.map((t) => (t.kind
    ? "<span style=\"color:" + TOKEN_COLORS[t.kind] + (t.kind === "comment" ? ";font-style:italic" : "")
      + "\">" + escapeHtml(t.text) + "</span>"
    : escapeHtml(t.text))).join("");
}

const CSS = `
*{box-sizing:border-box;margin:0;padding:0}
html,body{background:transparent}
body{padding:28px;display:flex;flex-direction:column;gap:40px;align-items:flex-start}
.ed{border-radius:10px;overflow:hidden;border:1px solid ${THEME.border};background:${THEME.bg};
 box-shadow:0 12px 32px rgba(0,0,0,.45);min-width:720px}
.bar{height:38px;background:${THEME.bar};display:flex;align-items:flex-end;border-bottom:1px solid ${THEME.border};
 position:relative}
.dots{position:absolute;left:12px;top:13px;display:flex;gap:7px}
.dot{width:11px;height:11px;border-radius:50%}
.tab{margin-left:76px;height:31px;padding:0 16px;display:flex;align-items:center;gap:8px;background:${THEME.tab};
 color:${THEME.fg};font:13px 'DejaVu Sans',sans-serif;border:1px solid ${THEME.border};border-bottom:none;
 border-radius:7px 7px 0 0;position:relative;top:1px}
.tab i{width:8px;height:8px;border-radius:50%;background:${THEME.accent};display:inline-block}
.crumbs{padding:7px 16px;color:${THEME.crumbs};font:12px 'DejaVu Sans',sans-serif;border-bottom:1px solid ${THEME.border}}
.code{padding:10px 0 14px;font-family:${THEME.font};font-size:${THEME.fontSize}px;line-height:${THEME.lineHeight}px;
 color:${THEME.fg};font-variant-ligatures:none}
.l{display:flex;white-space:pre;min-height:${THEME.lineHeight}px;border-left:3px solid transparent;padding-right:24px}
.l.hl{background:${THEME.hlBg};border-left-color:${THEME.accent}}
.n{width:calc(var(--gw) + 32px);text-align:right;padding:0 18px 0 14px;color:${THEME.gutter};flex:none;user-select:none}
.l.hl .n{color:${THEME.accent};font-weight:bold}
.gap .n,.gap .t{color:${THEME.gutter}}
.note{margin:6px 24px 6px calc(var(--gw) + 35px);padding:8px 12px;background:${THEME.noteBg};color:${THEME.noteFg};
 border-left:3px solid ${THEME.accent};border-radius:6px;font:14px 'DejaVu Sans',sans-serif;position:relative;
 max-width:760px;white-space:normal}
.note:before{content:"";position:absolute;left:14px;top:-7px;border:7px solid transparent;border-top:none;
 border-bottom-color:${THEME.noteBg}}
`;

/** Une fenêtre d'éditeur pour un fichier. */
function fileHtml(file, n) {
  const lang = LANGS[languageOf(file.path)] || LANGS.plain;
  const name = file.path.replace(/\\/g, "/").split("/").pop();
  const crumbs = file.path.replace(/\\/g, "/").split("/").filter(Boolean).map(escapeHtml).join(" › ");
  const lastNumber = file.firstLine + file.lines.length - 1;
  const gutter = (String(Math.max(lastNumber, file.totalLines)).length + 1) + "ch";
  const hl = new Set(file.highlights);
  const noteAfter = file.note ? (file.highlights.length ? file.highlights[file.highlights.length - 1] : lastNumber) : -1;
  const state = { inBlock: false };
  let body = "";
  if (file.firstLine > 1) {
    body += "<div class=\"l gap\"><span class=\"n\"></span><span class=\"t\">⋯</span></div>";
  }
  file.lines.forEach((line, k) => {
    const number = file.firstLine + k;
    body += "<div class=\"l" + (hl.has(number) ? " hl" : "") + "\"><span class=\"n\">" + number
      + "</span><span class=\"t\">" + tokensHtml(tokenize(line, lang, state)) + "</span></div>";
    if (number === noteAfter) {
      body += "<div class=\"note\">" + escapeHtml(file.note) + "</div>";
    }
  });
  if (lastNumber < file.totalLines) {
    body += "<div class=\"l gap\"><span class=\"n\"></span><span class=\"t\">⋯</span></div>";
  }
  return "<div class=\"ed\" data-shot=\"" + n + "\">"
    + "<div class=\"bar\"><div class=\"dots\"><span class=\"dot\" style=\"background:#FF5F57\"></span>"
    + "<span class=\"dot\" style=\"background:#FEBC2E\"></span><span class=\"dot\" style=\"background:#28C840\"></span>"
    + "</div><div class=\"tab\"><i></i>" + escapeHtml(name) + "</div></div>"
    + "<div class=\"crumbs\">" + crumbs + "</div>"
    + "<div class=\"code\" style=\"--gw:" + gutter + "\">" + body + "</div></div>";
}

/**
 * La page HTML : une fenêtre par fichier (`<div class="ed" data-shot="n">`).
 *
 * @returns {{ html: string, windows: number }}
 */
function renderHtml(files) {
  const body = files.map(fileHtml).join("");
  const html = "<!doctype html><html><head><meta charset=\"utf-8\"><style>" + CSS
    + "</style></head><body>" + body + "</body></html>";
  return { html, windows: files.length };
}

for (const name of HYPHEN_LANGS) {
  LANGS[name].hyphens = true;
}

module.exports = {
  EditorError, parseFiles, renderHtml, languageOf, tokenize, LANGS, THEME,
  MAX_BODY_BYTES, MAX_FILES, MAX_LINES, MAX_LINE_CHARS, MAX_NOTE_CHARS,
};
