/**
 * Les captures de preuve (F-212 / SF-212-02) — la partie PURE : d'un relevé de session du poste
 * (commandes et sorties BRUTES, rendues par le runner) à la page HTML d'une ou plusieurs fenêtres de
 * terminal, fidèles au terminal par défaut de la plateforme.
 *
 * Amendement PO du 2026-10-24 : RIEN dans l'image ne trahit qu'elle est générée — pas de pied, pas de
 * mention. L'invite est celle de la vraie machine (compte, nom d'hôte court, dossier avec ~), le style
 * celui du terminal par défaut : GNOME Terminal + bash (Linux), Terminal.app + zsh ou bash (macOS),
 * Windows Terminal + PowerShell / cmd / Git Bash (Windows). Polices libres, embarquées dans l'image.
 *
 * Une fenêtre montre la session entière, comme on la verrait à l'écran : invite, commande, sortie,
 * invite suivante… Elle n'est découpée en plusieurs fenêtres que si elle dépasse MAX_ROWS lignes.
 *
 * Aucune dépendance à chromium ici : `node --test diagram-renderer/tests/`.
 */

"use strict";

const MIN_COLS = 80;
const MAX_COLS = 120;
const MIN_ROWS = 12;
const MAX_ROWS = 50;
const MAX_COMMANDS = 8;
const MAX_TEXT_CHARS = 128 * 1024;
const MAX_BODY_BYTES = 2 * 1024 * 1024;

class ShotError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

// ------------------------------------------------------------------ thèmes

/** Palette Tango de GNOME Terminal (thème Ubuntu). */
const TANGO = ["#2E3436", "#CC0000", "#4E9A06", "#C4A000", "#3465A4", "#75507B", "#06989A", "#D3D7CF",
  "#555753", "#EF2929", "#8AE234", "#FCE94F", "#729FCF", "#AD7FA8", "#34E2E2", "#EEEEEC"];
/** Terminal.app, profil « Basic ». */
const MAC_BASIC = ["#000000", "#990000", "#00A600", "#999900", "#0000B2", "#B200B2", "#00A6B2", "#BFBFBF",
  "#666666", "#E50000", "#00D900", "#E5E500", "#0000FF", "#E500E5", "#00E5E5", "#E5E5E5"];
/** Windows Terminal, thème « Campbell ». */
const CAMPBELL = ["#0C0C0C", "#C50F1F", "#13A10E", "#C19C00", "#0037DA", "#881798", "#3A96DD", "#CCCCCC",
  "#767676", "#E74856", "#16C60C", "#F9F1A5", "#3B78FF", "#B4009E", "#61D6D6", "#F2F2F2"];

const THEMES = {
  gnome: {
    palette: TANGO, fg: "#FFFFFF", bg: "#300A24", font: "'UbuntuMono Nerd Font Mono', 'DejaVu Sans Mono', monospace",
    fontSize: 17, lineHeight: 1.18, chrome: "gnome",
  },
  mac: {
    palette: MAC_BASIC, fg: "#000000", bg: "#FFFFFF", font: "'DejaVu Sans Mono', monospace",
    fontSize: 13, lineHeight: 1.2, chrome: "mac",
  },
  windows: {
    palette: CAMPBELL, fg: "#CCCCCC", bg: "#0C0C0C",
    font: "'CaskaydiaCove Nerd Font Mono', 'DejaVu Sans Mono', monospace",
    fontSize: 14, lineHeight: 1.25, chrome: "windows",
  },
};

// ------------------------------------------------------------------ entrée

function text(value, max) {
  if (value === undefined || value === null) {
    return "";
  }
  const s = String(value);
  return s.length > max ? s.slice(0, max) : s;
}

/**
 * Valide le relevé. Ne garde que ce qui sert à l'image.
 *
 * @throws {ShotError} 400 si le relevé est mal formé
 */
function parseSession(payload) {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    throw new ShotError(400, "Le relevé est manquant.");
  }
  const commands = payload.commands;
  if (!Array.isArray(commands) || commands.length === 0 || commands.length > MAX_COMMANDS) {
    throw new ShotError(400, "Il faut 1 à " + MAX_COMMANDS + " commandes.");
  }
  const os = ["linux", "macos", "windows"].includes(payload.os) ? payload.os : "linux";
  const session = {
    os,
    user: text(payload.user, 64) || "user",
    host: text(payload.host, 64) || "localhost",
    home: text(payload.home, 1024),
    shell: text(payload.execShell || payload.interactiveShell || payload.shell, 32).toLowerCase() || "bash",
    cwd: text(payload.cwd, 4096),
    commands: [],
  };
  for (const c of commands) {
    if (!c || typeof c !== "object" || typeof c.command !== "string" || c.command.trim() === "") {
      throw new ShotError(400, "Chaque commande doit porter son texte.");
    }
    session.commands.push({
      command: text(c.command, 8192),
      stdout: text(c.stdout, MAX_TEXT_CHARS),
      stderr: text(c.stderr, MAX_TEXT_CHARS),
      cwd: text(c.cwd, 4096),
      status: ["done", "interrupted", "not_run"].includes(c.status) ? c.status : "done",
    });
  }
  return session;
}

// ------------------------------------------------------------------ style de la plateforme

/** Le genre de fenêtre et d'invite : linux-bash, mac-zsh, mac-bash, win-powershell, win-cmd, win-bash. */
function flavor(session) {
  const posixLike = ["bash", "zsh", "sh", "dash", "ksh", "posix"].includes(session.shell);
  if (session.os === "windows") {
    if (session.shell === "cmd") {
      return "win-cmd";
    }
    return posixLike ? "win-bash" : "win-powershell";
  }
  if (session.os === "macos") {
    return session.shell === "zsh" ? "mac-zsh" : "mac-bash";
  }
  return "linux-bash";
}

function themeOf(kind) {
  if (kind.startsWith("mac")) {
    return THEMES.mac;
  }
  return kind.startsWith("win") ? THEMES.windows : THEMES.gnome;
}

/** Chemin d'un dossier Windows vu par Git Bash : C:\Users\x → /c/Users/x. */
function msysPath(p) {
  const m = /^([A-Za-z]):[\\/]?(.*)$/.exec(p || "");
  if (!m) {
    return (p || "").replace(/\\/g, "/");
  }
  const rest = m[2].replace(/\\/g, "/");
  return "/" + m[1].toLowerCase() + (rest ? "/" + rest : "");
}

/** ~ pour le dossier personnel, comme bash (\w) et zsh (%~). */
function tilde(dir, home) {
  if (!dir) {
    return "~";
  }
  if (home && (dir === home || dir.startsWith(home.endsWith("/") ? home : home + "/"))) {
    return "~" + dir.slice(home.replace(/\/$/, "").length);
  }
  return dir;
}

/** Dernier segment (\W de bash, %1~ de zsh). */
function lastSegment(dir, home) {
  const t = tilde(dir, home);
  if (t === "~" || t === "/") {
    return t;
  }
  const parts = t.split("/").filter((s) => s.length > 0);
  return parts.length === 0 ? "/" : parts[parts.length - 1];
}

/**
 * L'invite affichée devant une commande, en segments `{ text, style }`, et le titre de fenêtre.
 * Les couleurs suivent les invites par défaut des distributions et des terminaux.
 */
function prompt(kind, session, dir, theme) {
  const p = theme.palette;
  const { user, host } = session;
  switch (kind) {
    case "mac-zsh":
      return [{ text: user + "@" + host + " " + lastSegment(dir, session.home) + " % ", style: {} }];
    case "mac-bash":
      return [{ text: host + ":" + lastSegment(dir, session.home) + " " + user + "$ ", style: {} }];
    case "win-powershell":
      return [{ text: "PS " + dir + "> ", style: {} }];
    case "win-cmd":
      return [{ text: dir + ">", style: {} }];
    case "win-bash": {
      const home = msysPath(session.home);
      const shown = tilde(msysPath(dir), home);
      return [
        { text: user + "@" + host + " ", style: { fg: p[2] } },
        { text: "MINGW64 ", style: { fg: p[5] } },
        { text: shown, style: { fg: p[3] } },
        { text: "\n$ ", style: {} },
      ];
    }
    default:
      return [
        { text: user + "@" + host, style: { fg: p[2], fgIndex: 2, bold: true } },
        { text: ":", style: {} },
        { text: tilde(dir, session.home), style: { fg: p[4], fgIndex: 4, bold: true } },
        { text: "$ ", style: {} },
      ];
  }
}

/** Invite de continuation d'une commande sur plusieurs lignes. */
function continuation(kind) {
  if (kind === "win-powershell") {
    return ">> ";
  }
  if (kind === "win-cmd") {
    return "More? ";
  }
  return kind === "mac-zsh" ? "> " : "> ";
}

// ------------------------------------------------------------------ émulation (lignes et couleurs)

/**
 * Un écran minimal : des lignes de cellules stylées, une largeur fixe, le retour à la ligne d'un vrai
 * terminal, les séquences SGR (couleurs, gras, souligné, inverse) ; les autres séquences d'échappement
 * sont ignorées, `\r` ramène en début de ligne (barres de progression), `\t` va au taquet de 8.
 */
class Screen {
  constructor(cols, theme) {
    this.cols = cols;
    this.theme = theme;
    this.rows = [[]];
    this.col = 0;
    this.style = {};
  }

  get row() {
    return this.rows[this.rows.length - 1];
  }

  newLine() {
    this.rows.push([]);
    this.col = 0;
  }

  put(ch, style) {
    if (this.col >= this.cols) {
      this.newLine();
    }
    const row = this.row;
    while (row.length < this.col) {
      row.push({ ch: " ", style: {} });
    }
    row[this.col] = { ch, style: style || {} };
    this.col++;
  }

  /** Texte de l'application (invite, commande) : pas de séquences d'échappement à interpréter. */
  plain(textValue, style) {
    for (const ch of textValue) {
      if (ch === "\n") {
        this.newLine();
      } else if (ch !== "\r") {
        this.put(ch, style);
      }
    }
  }

  /** Sortie brute d'une commande. */
  output(raw) {
    let i = 0;
    const s = raw;
    while (i < s.length) {
      const ch = s[i];
      if (ch === "\u001b") {
        i = this.escape(s, i);
        continue;
      }
      if (ch === "\n") {
        this.newLine();
      } else if (ch === "\r") {
        this.col = 0;
      } else if (ch === "\t") {
        const next = Math.min(this.cols, (Math.floor(this.col / 8) + 1) * 8);
        while (this.col < next) {
          this.put(" ", this.style);
        }
      } else if (ch === "\b") {
        this.col = Math.max(0, this.col - 1);
      } else if (ch.charCodeAt(0) < 0x20 || ch === "\u007f") {
        // Caractère de contrôle invisible dans un terminal : rien à dessiner.
      } else {
        const code = s.codePointAt(i);
        const glyph = String.fromCodePoint(code);
        this.put(glyph, { ...this.style });
        i += glyph.length;
        continue;
      }
      i++;
    }
  }

  escape(s, i) {
    const next = s[i + 1];
    if (next === "[") {
      let j = i + 2;
      while (j < s.length && !(s.charCodeAt(j) >= 0x40 && s.charCodeAt(j) <= 0x7e)) {
        j++;
      }
      const final = s[j];
      const params = s.slice(i + 2, j);
      if (final === "m") {
        this.sgr(params);
      } else if (final === "K") {
        const row = this.row;
        if (params === "" || params === "0") {
          row.length = Math.min(row.length, this.col);
        } else if (params === "2") {
          row.length = 0;
        }
      }
      return j + 1;
    }
    if (next === "]") {
      // OSC (titre de fenêtre…) : jusqu'à BEL ou ST.
      let j = i + 2;
      while (j < s.length && s[j] !== "\u0007" && !(s[j] === "\u001b" && s[j + 1] === "\\")) {
        j++;
      }
      return s[j] === "\u0007" ? j + 1 : j + 2;
    }
    return i + 2;
  }

  sgr(params) {
    const codes = params === "" ? [0] : params.split(";").map((v) => parseInt(v, 10) || 0);
    const p = this.theme.palette;
    for (let k = 0; k < codes.length; k++) {
      const c = codes[k];
      if (c === 0) {
        this.style = {};
      } else if (c === 1) {
        this.style.bold = true;
      } else if (c === 2) {
        this.style.dim = true;
      } else if (c === 3) {
        this.style.italic = true;
      } else if (c === 4) {
        this.style.underline = true;
      } else if (c === 7) {
        this.style.inverse = true;
      } else if (c === 22) {
        delete this.style.bold;
        delete this.style.dim;
      } else if (c === 23) {
        delete this.style.italic;
      } else if (c === 24) {
        delete this.style.underline;
      } else if (c === 27) {
        delete this.style.inverse;
      } else if (c >= 30 && c <= 37) {
        this.style.fg = p[c - 30];
        this.style.fgIndex = c - 30;
      } else if (c === 39) {
        delete this.style.fg;
        delete this.style.fgIndex;
      } else if (c >= 40 && c <= 47) {
        this.style.bg = p[c - 40];
      } else if (c === 49) {
        delete this.style.bg;
      } else if (c >= 90 && c <= 97) {
        this.style.fg = p[c - 90 + 8];
        delete this.style.fgIndex;
      } else if (c >= 100 && c <= 107) {
        this.style.bg = p[c - 100 + 8];
      } else if ((c === 38 || c === 48) && codes[k + 1] === 5) {
        this.style[c === 38 ? "fg" : "bg"] = color256(codes[k + 2] || 0, p);
        if (c === 38) {
          delete this.style.fgIndex;
        }
        k += 2;
      } else if ((c === 38 || c === 48) && codes[k + 1] === 2) {
        const [r, g, b] = [codes[k + 2] || 0, codes[k + 3] || 0, codes[k + 4] || 0];
        this.style[c === 38 ? "fg" : "bg"] = "rgb(" + r + "," + g + "," + b + ")";
        if (c === 38) {
          delete this.style.fgIndex;
        }
        k += 4;
      }
    }
  }
}

function color256(n, palette) {
  if (n < 16) {
    return palette[n];
  }
  if (n < 232) {
    const v = n - 16;
    const level = (x) => (x === 0 ? 0 : 55 + x * 40);
    return "rgb(" + level(Math.floor(v / 36)) + "," + level(Math.floor(v / 6) % 6) + "," + level(v % 6) + ")";
  }
  const gray = 8 + (n - 232) * 10;
  return "rgb(" + gray + "," + gray + "," + gray + ")";
}

/** Strip ANSI : pour mesurer la largeur utile. */
function visibleLines(raw) {
  // eslint-disable-next-line no-control-regex
  return raw.replace(/\u001b\[[0-9;?]*[ -/]*[@-~]/g, "").replace(/\u001b\][^\u0007]*\u0007/g, "").split("\n");
}

/** Largeur de la fenêtre : 80 colonnes (le défaut des trois terminaux), jusqu'à 120 si la sortie l'exige. */
function columnsFor(session, kind, theme) {
  let widest = 0;
  for (const c of session.commands) {
    const head = prompt(kind, session, c.cwd || session.cwd, theme).map((s) => s.text).join("");
    const lastPromptLine = head.split("\n").pop();
    for (const line of [lastPromptLine + c.command.split("\n")[0]]
      .concat(visibleLines(c.stdout), visibleLines(c.stderr))) {
      widest = Math.max(widest, [...line].length);
    }
  }
  return Math.max(MIN_COLS, Math.min(MAX_COLS, widest));
}

/**
 * La session sous forme de lignes d'écran : pour chaque commande exécutée, l'invite (dossier AVANT la
 * commande), la commande, sa sortie standard puis sa sortie d'erreur ; une invite finale avec le
 * curseur si le shell est toujours là.
 */
function screenOf(session) {
  const kind = flavor(session);
  const theme = themeOf(kind);
  const cols = columnsFor(session, kind, theme);
  const screen = new Screen(cols, theme);
  let dir = session.cwd;
  let alive = true;
  let first = true;
  for (const c of session.commands) {
    if (c.status === "not_run") {
      break;
    }
    if (!first) {
      screen.newLine();
    }
    first = false;
    for (const seg of prompt(kind, session, dir, theme)) {
      screen.plain(seg.text, seg.style);
    }
    const lines = c.command.split("\n");
    screen.plain(lines[0], {});
    for (const extra of lines.slice(1)) {
      screen.newLine();
      screen.plain(continuation(kind) + extra, {});
    }
    screen.style = {};
    if (c.stdout.length > 0) {
      screen.newLine();
      screen.output(c.stdout.endsWith("\n") ? c.stdout.slice(0, -1) : c.stdout);
    }
    screen.style = {};
    if (c.stderr.length > 0) {
      screen.newLine();
      screen.output(c.stderr.endsWith("\n") ? c.stderr.slice(0, -1) : c.stderr);
    }
    screen.style = {};
    if (c.status === "interrupted") {
      alive = false;
      break;
    }
    if (c.cwd) {
      dir = c.cwd;
    }
    // cmd laisse une ligne vide avant l'invite suivante (PowerShell non : ses lignes vides viennent
    // de la sortie formatée elle-même).
    if (kind === "win-cmd") {
      screen.newLine();
    }
  }
  if (alive) {
    screen.newLine();
    for (const seg of prompt(kind, session, dir, theme)) {
      screen.plain(seg.text, seg.style);
    }
    screen.put("\u2588", { cursor: true });
  }
  return { kind, theme, cols, rows: screen.rows, title: titleOf(kind, session, dir) };
}

function titleOf(kind, session, dir) {
  switch (kind) {
    case "linux-bash":
      return session.user + "@" + session.host + ": " + tilde(dir, session.home);
    case "mac-zsh":
      return lastSegment(dir, session.home) + " — -zsh";
    case "mac-bash":
      return lastSegment(dir, session.home) + " — -bash";
    case "win-cmd":
      return "Invite de commandes";
    case "win-bash":
      return "Git Bash";
    default:
      return "Windows PowerShell";
  }
}

// ------------------------------------------------------------------ HTML

function escapeHtml(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function styleCss(style, theme) {
  // Gras = couleur vive : le réglage par défaut de GNOME Terminal (Ubuntu), Terminal.app et
  // Windows Terminal (« intenseTextStyle »).
  let fg = style.bold && style.fgIndex !== undefined && style.fgIndex < 8
    ? theme.palette[style.fgIndex + 8] : (style.fg || null);
  let bg = style.bg || null;
  if (style.inverse) {
    [fg, bg] = [bg || theme.bg, fg || theme.fg];
  }
  const css = [];
  if (fg) {
    css.push("color:" + fg);
  }
  if (bg) {
    css.push("background:" + bg);
  }
  if (style.bold) {
    css.push("font-weight:bold");
  }
  if (style.dim) {
    css.push("opacity:.7");
  }
  if (style.italic) {
    css.push("font-style:italic");
  }
  if (style.underline) {
    css.push("text-decoration:underline");
  }
  if (style.cursor) {
    css.push("color:" + theme.fg);
  }
  return css.join(";");
}

function rowHtml(row, theme) {
  let html = "";
  let current = null;
  let buffer = "";
  const flush = () => {
    if (buffer) {
      html += current ? "<span style=\"" + current + "\">" + escapeHtml(buffer) + "</span>" : escapeHtml(buffer);
    }
    buffer = "";
  };
  for (const cell of row) {
    const css = styleCss(cell.style, theme);
    if (css !== current && !(css === "" && current === null)) {
      flush();
      current = css || null;
    }
    buffer += cell.ch;
  }
  flush();
  return html;
}

const WINDOW_CSS = `
*{box-sizing:border-box;margin:0;padding:0}
html,body{background:transparent}
body{padding:28px;display:flex;flex-direction:column;gap:40px;align-items:flex-start}
.w{overflow:hidden;box-shadow:0 10px 28px rgba(0,0,0,.35),0 0 0 1px rgba(0,0,0,.25)}
.t{white-space:pre;font-variant-ligatures:none;padding:4px 6px}
.r{display:block;height:var(--lh)}
.gnome{border-radius:12px 12px 0 0}
.gnome .bar{height:46px;background:#303030;color:#fff;display:flex;align-items:center;justify-content:center;
 position:relative;font:bold 15px 'DejaVu Sans',sans-serif;border-bottom:1px solid #1e1e1e}
.gnome .btns{position:absolute;right:12px;top:0;height:46px;display:flex;gap:12px;align-items:center}
.gnome .btn{width:24px;height:24px;border-radius:50%;background:#4a4a4a;color:#fff;font:13px 'DejaVu Sans',sans-serif;
 display:flex;align-items:center;justify-content:center}
.gnome .left{position:absolute;left:12px;top:9px;width:34px;height:28px;border-radius:6px;background:#3d3d3d;
 color:#fff;display:flex;align-items:center;justify-content:center;font:16px 'DejaVu Sans',sans-serif}
.mac{border-radius:10px;border:1px solid #b9b9b9}
.mac .bar{height:28px;background:linear-gradient(#ececec,#d6d6d6);border-bottom:1px solid #b1b1b1;display:flex;
 align-items:center;justify-content:center;position:relative;font:13px 'DejaVu Sans',sans-serif;color:#4d4d4d}
.mac .dots{position:absolute;left:9px;top:8px;display:flex;gap:8px}
.mac .dot{width:12px;height:12px;border-radius:50%}
.windows{border-radius:8px;border:1px solid #3a3a3a}
.windows .bar{height:40px;background:#202020;display:flex;align-items:flex-end;position:relative;padding-left:8px}
.windows .tab{height:32px;min-width:220px;background:#0c0c0c;color:#fff;border-radius:8px 8px 0 0;display:flex;
 align-items:center;padding:0 12px;gap:10px;font:12px 'DejaVu Sans',sans-serif}
.windows .tab .x{margin-left:auto;color:#bbb;font-size:11px}
.windows .plus{color:#ddd;font:16px 'DejaVu Sans',sans-serif;padding:0 14px 8px}
.windows .ctl{position:absolute;right:0;top:0;height:40px;display:flex;color:#ddd;font:13px 'DejaVu Sans',sans-serif}
.windows .ctl span{width:46px;display:flex;align-items:center;justify-content:center}
`;

function chromeHtml(screen, title, cols, rows) {
  const t = escapeHtml(title);
  switch (screen.theme.chrome) {
    case "mac":
      return "<div class=\"bar\"><div class=\"dots\"><span class=\"dot\" style=\"background:#FF5F57\"></span>"
        + "<span class=\"dot\" style=\"background:#FEBC2E\"></span><span class=\"dot\" style=\"background:#28C840\">"
        + "</span></div>" + t + " — " + cols + "×" + rows + "</div>";
    case "windows":
      return "<div class=\"bar\"><div class=\"tab\"><span>" + t + "</span><span class=\"x\">✕</span></div>"
        + "<span class=\"plus\">+</span><div class=\"ctl\"><span>─</span><span>☐</span><span>✕</span></div></div>";
    default:
      return "<div class=\"bar\"><span class=\"left\">⊞</span>" + t
        + "<span class=\"btns\"><span class=\"btn\">─</span><span class=\"btn\">☐</span><span class=\"btn\">✕</span></span></div>";
  }
}

/**
 * La page HTML : une fenêtre par tranche de MAX_ROWS lignes (`<div class="w" data-shot="n">`).
 *
 * @returns {{ html: string, windows: number, cols: number, kind: string }}
 */
function renderHtml(session) {
  const screen = screenOf(session);
  const { theme, cols } = screen;
  const chunks = [];
  for (let i = 0; i < screen.rows.length; i += MAX_ROWS) {
    chunks.push(screen.rows.slice(i, i + MAX_ROWS));
  }
  const lh = Math.round(theme.fontSize * theme.lineHeight);
  let body = "";
  chunks.forEach((rows, n) => {
    const visibleRows = Math.max(MIN_ROWS, rows.length);
    let lines = rows.map((row) => "<span class=\"r\">" + rowHtml(row, theme) + "</span>").join("");
    for (let k = rows.length; k < visibleRows; k++) {
      lines += "<span class=\"r\"></span>";
    }
    body += "<div class=\"w " + theme.chrome + "\" data-shot=\"" + n + "\" style=\"background:" + theme.bg + "\">"
      + chromeHtml(screen, screen.title, cols, visibleRows)
      + "<div class=\"t\" style=\"--lh:" + lh + "px;width:calc(" + cols + "ch + 12px);color:" + theme.fg
      + ";font-family:" + theme.font + ";font-size:" + theme.fontSize + "px;line-height:" + lh + "px\">"
      + lines + "</div></div>";
  });
  const html = "<!doctype html><html><head><meta charset=\"utf-8\"><style>" + WINDOW_CSS
    + "</style></head><body>" + body + "</body></html>";
  return { html, windows: chunks.length, cols, kind: screen.kind };
}

module.exports = {
  ShotError, parseSession, renderHtml, screenOf, flavor, tilde, lastSegment, msysPath,
  MAX_BODY_BYTES, MAX_ROWS, MIN_COLS, MAX_COLS,
};
