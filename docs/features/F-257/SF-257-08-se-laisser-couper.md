# Mini-spec — [F-257 / SF-257-08] Se laisser couper, finir sa phrase, entendre un mot bref

## Identifiant

`F-257 / SF-257-08`

## Feature parente

`F-257` — Jarvis converse en flux (cadrage §3.2, §4.2 R4 ; décision PO du 2026-11-11 « voie 3 »)

## Statut

`in-review`

## Date de création

2026-11-11

## Branche Git

`feat/SF-257-08-se-laisser-couper`

---

## Objectif

Dans la boucle en flux : une phrase dite en deux temps reste **un seul tour**, un mot bref et sourd (« Oui. ») est
**entendu**, et Jarvis **se laisse couper** pendant qu'il parle sans s'interrompre lui-même.

---

## Comportement attendu

### Cas nominal

1. **Phrase en deux temps** — la grâce de confirmation passe à **1,8 s** : une parole qui reprend dans ce délai annule
   le candidat ; la réponse provisoire est jetée et relancée sur la phrase entière (règles F-252 inchangées). Le
   cerveau partant au candidat (≈ 2,0 s avant sa première phrase), cette attente ne retarde pas la voix.
2. **Mot bref** — hors tour, un son au-dessus de ≈ -36 dBFS pendant ≥ 0,1 s que le VAD n'a pas pris pour de la parole
   déclenche, au silence qui suit, un `commit` discret (une sonde). Si la session d'écoute rend un texte final, il
   ouvre le tour et part **aussitôt en candidat** (`by=texte`, le texte est déjà final), confirmé après la grâce.
   Sinon rien (aucun événement, compteur `probes`).
3. **Se laisser couper** — pendant la voix de Jarvis, le micro du lien reste muet (SF-257-06) et l'interruption est
   celle, éprouvée, de l'écran (F-233 SF-233-08/09/10 : détection de voix, baisse puis coupure, part entendue,
   classement acquiescement / silence / annulation / demande), qui fonctionne en boucle en flux (banc « coupure »).

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Bruit de fond sous le seuil | aucune sonde |
| Sonde sans texte | rien ne se passe |
| Parole reprise après 1,8 s | nouveau tour (comme aujourd'hui) |

---

## Critères d'acceptation

- [ ] CA1 — Mot sourd (RMS ≈ 4 000, VAD muet) → une sonde, puis tour `speech` → `candidate by=texte` → `confirmed`.
- [ ] CA2 — Bruit sous le seuil pendant 3 s → aucune sonde.
- [ ] CA3 — Un final reçu sans tour ouvre le tour en candidat immédiat, sans second `commit`.
- [ ] CA4 — Au banc, boucle en flux : « Oui. » entendu ; « Attends. — En fait… » et « … Agenor ? — Chez CAGIP » en un
      seul tour ; interruption (scénario « coupure ») entendue. Les deux boucles mesurées, voix ElevenLabs.

---

## Périmètre

### Hors scope — décision notée dans la PR

- **Interruption côté serveur** (ouvrir le micro du lien pendant la voix de Jarvis, faire décider le service) : non
  livrée. L'interruption de l'écran couvre déjà le besoin en boucle en flux, et ouvrir le micro pendant la voix
  exposerait au risque d'écho R4 (Jarvis qui se coupe lui-même) sans mesure de l'annulation d'écho au banc. À
  reprendre si le banc « coupure » ou l'usage montre une coupure trop lente.
- Changer le modèle de voix (essai d'écoute fourni au PO à part).

---

## Technique

- Service : `app/turn.py` (`CONFIRM_SILENCE_S` 1,8 s, `PROBE_RMS`, `PROBE_MIN_S`, `rms`, sonde, `final` sans tour →
  candidat `texte`).
- Aucun endpoint, aucune table, aucun écran modifiés.

## Plan de test

- [ ] `test_turn.py` — sonde (mot sourd → tour), bruit faible sans sonde, final sans tour → candidat immédiat.
- [ ] Banc à vraie voix, les deux boucles, voix ElevenLabs.

### Isolation

- [x] Non applicable (aucune donnée).

## Dépendances

- SF-257-06 fusionnée.
