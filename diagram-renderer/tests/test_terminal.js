/**
 * Tests des captures de preuve (F-212 / SF-212-02) — la partie PURE de `terminal.js`.
 *
 *   node --test diagram-renderer/tests/
 *
 * La photographie elle-même (chromium) est vérifiée sur l'image construite (mini-spec).
 */

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const t = require("../terminal.js");

function session(extra) {
  return {
    os: "linux", user: "franck", host: "pc-dev", home: "/home/franck", execShell: "bash",
    cwd: "/home/franck/projet",
    commands: [
      { command: "cd api", stdout: "", stderr: "", cwd: "/home/franck/projet/api", status: "done" },
      { command: "ls", stdout: "\u001b[01;34msrc\u001b[0m  pom.xml\n", stderr: "", cwd: "/home/franck/projet/api", status: "done" },
    ],
    ...extra,
  };
}

function rowsText(rows) {
  return rows.map((r) => r.map((c) => c.ch).join(""));
}

test("Linux : invite bash Ubuntu réelle (user@host:~/chemin$), dossier AVANT chaque commande, invite finale", () => {
  const screen = t.screenOf(t.parseSession(session()));
  const text = rowsText(screen.rows);
  assert.equal(screen.kind, "linux-bash");
  assert.equal(text[0], "franck@pc-dev:~/projet$ cd api");
  assert.equal(text[1], "franck@pc-dev:~/projet/api$ ls");
  assert.equal(text[2], "src  pom.xml");
  assert.equal(text[3], "franck@pc-dev:~/projet/api$ \u2588");
  assert.equal(screen.title, "franck@pc-dev: ~/projet/api");
  // Couleurs ANSI de la sortie : « src » en bleu gras.
  const src = screen.rows[2][0];
  assert.equal(src.style.bold, true);
  assert.equal(src.style.fgIndex, 4);
  // Gras = couleur vive (réglage par défaut des terminaux imités).
  assert.match(t.renderHtml(t.parseSession(session())).html, /color:#729FCF;font-weight:bold">src/);
});

test("aucun pied ni mention de génération dans la page", () => {
  const { html } = t.renderHtml(t.parseSession(session()));
  for (const word of ["code 0", "générée", "Capture", "durée", "exit"]) {
    assert.ok(!html.includes(word), "ne doit pas contenir : " + word);
  }
});

test("macOS zsh : user@Host dernier-segment %, titre « chemin — -zsh — 80×N »", () => {
  const s = t.parseSession(session({ os: "macos", execShell: "zsh", home: "/Users/franck",
    cwd: "/Users/franck/projet", host: "MacBook-Pro",
    commands: [{ command: "pwd", stdout: "/Users/franck/projet\n", status: "done", cwd: "/Users/franck/projet" }] }));
  const screen = t.screenOf(s);
  assert.equal(rowsText(screen.rows)[0], "franck@MacBook-Pro projet % pwd");
  const { html } = t.renderHtml(s);
  assert.match(html, /projet — -zsh — 80×12/);
});

test("macOS bash : Host:chemin user$", () => {
  const screen = t.screenOf(t.parseSession(session({ os: "macos", execShell: "bash", home: "/Users/franck",
    cwd: "/Users/franck", host: "MacBook-Pro", commands: [{ command: "true", status: "done" }] })));
  assert.equal(rowsText(screen.rows)[0], "MacBook-Pro:~ franck$ true");
});

test("Windows PowerShell : PS C:\\…>, l'invite suit directement la sortie", () => {
  const screen = t.screenOf(t.parseSession({ os: "windows", user: "x", host: "PC-CAGIP", home: "C:\\Users\\x",
    execShell: "powershell", cwd: "C:\\Users\\x\\app",
    commands: [{ command: "Get-Date", stdout: "samedi 24 octobre 2026\n", status: "done", cwd: "C:\\Users\\x\\app" }] }));
  const text = rowsText(screen.rows);
  assert.equal(text[0], "PS C:\\Users\\x\\app> Get-Date");
  assert.equal(text[1], "samedi 24 octobre 2026");
  assert.equal(text[2], "PS C:\\Users\\x\\app> \u2588");
  assert.equal(screen.title, "Windows PowerShell");
});

test("Windows Git Bash : invite MINGW64 sur deux lignes avec ~", () => {
  const screen = t.screenOf(t.parseSession({ os: "windows", user: "x", host: "PC", home: "C:\\Users\\x",
    execShell: "bash", cwd: "/c/Users/x/app", commands: [{ command: "ls", stdout: "a\n", status: "done" }] }));
  const text = rowsText(screen.rows);
  assert.equal(text[0], "x@PC MINGW64 ~/app");
  assert.equal(text[1], "$ ls");
});

test("retour à la ligne d'un vrai terminal à 80 colonnes, \\r écrase la ligne, séquences inconnues ignorées", () => {
  const long = "x".repeat(170);
  const screen = t.screenOf(t.parseSession(session({ commands: [
    { command: "seq", stdout: "a".repeat(85) + "\n10%\r100%\n\u001b[2Jfin\n", status: "done" }] })));
  // 85 caractères : la ligne la plus large fixe la largeur (85 ≤ 120).
  assert.equal(screen.cols, 85);
  const text = rowsText(screen.rows);
  assert.equal(text[2], "100%");
  assert.equal(text[3], "fin");
  const wide = t.screenOf(t.parseSession(session({ commands: [{ command: "w", stdout: long, status: "done" }] })));
  assert.equal(wide.cols, 120);
  assert.equal(rowsText(wide.rows)[1].length, 120);
  assert.equal(rowsText(wide.rows)[2].length, 50);
});

test("une commande interrompue (exit) arrête la session : pas d'invite finale, les suivantes absentes", () => {
  const screen = t.screenOf(t.parseSession(session({ commands: [
    { command: "exit 3", status: "interrupted" },
    { command: "echo jamais", status: "not_run" }] })));
  const text = rowsText(screen.rows);
  assert.deepEqual(text, ["franck@pc-dev:~/projet$ exit 3"]);
});

test("une session trop haute est découpée en plusieurs fenêtres de 50 lignes au plus", () => {
  const out = Array.from({ length: 120 }, (_, i) => "ligne " + i).join("\n") + "\n";
  const page = t.renderHtml(t.parseSession(session({ commands: [{ command: "cat f", stdout: out, status: "done" }] })));
  assert.equal(page.windows, 3);
  assert.equal((page.html.match(/data-shot=/g) || []).length, 3);
});

test("le texte est échappé (pas d'injection HTML dans la page)", () => {
  const { html } = t.renderHtml(t.parseSession(session({ commands: [
    { command: "echo '<script>alert(1)</script>'", stdout: "<img src=x onerror=1>\n", status: "done" }] })));
  assert.ok(!html.includes("<script>alert"));
  assert.ok(!html.includes("<img src=x"));
  assert.ok(html.includes("&lt;img src=x onerror=1&gt;"));
});

test("relevé invalide : 400", () => {
  for (const bad of [null, [], {}, { commands: [] }, { commands: [{}] },
    { commands: Array.from({ length: 9 }, () => ({ command: "a" })) }]) {
    assert.throws(() => t.parseSession(bad), (e) => e instanceof t.ShotError && e.status === 400);
  }
});

test("couleurs 256 et 24 bits", () => {
  const screen = t.screenOf(t.parseSession(session({ commands: [
    { command: "c", stdout: "\u001b[38;5;196mR\u001b[38;2;1;2;3mG\u001b[0mN\n", status: "done" }] })));
  const row = screen.rows[1];
  assert.equal(row[0].style.fg, "rgb(255,0,0)");
  assert.equal(row[1].style.fg, "rgb(1,2,3)");
  assert.equal(row[2].style.fg, undefined);
});
