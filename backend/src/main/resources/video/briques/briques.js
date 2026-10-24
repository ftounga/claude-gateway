/*
 * Briques des cours vidéo — animations (F-215 / SF-215-03). Exige gsap.min.js chargé avant ce fichier.
 *
 *   const B = Briques.timeline("root");   // timeline EN PAUSE (enregistrée dans window.__timelines)
 *   window.__timelines = window.__timelines || {}; window.__timelines["root"] = B.tl;   // À ÉCRIRE dans la page : le lint ne lit pas ce fichier
 *   B.pop("ico1", 2.3); B.draw("w1", 4.0); B.move("pk", [[300,400],[900,400]], 4.2, .8);
 *   B.end(95.0);
 *
 * Tous les instants sont en secondes sur la timeline racine — repris des .mots.json de voix_off (Briques.motA).
 * Point de départ, pas un gabarit : utilise B.tl (la timeline GSAP) pour tout ce qui manque.
 */
(function (global) {
  "use strict";

  function $(target) {
    if (typeof target !== "string") {
      return target;
    }
    return /^[#.\[]/.test(target) ? target : "#" + target;
  }

  function timeline(id) {
    var tl = global.gsap.timeline({ paused: true });
    global.__timelines = global.__timelines || {};
    global.__timelines[id || "root"] = tl;

    var api = {
      tl: tl,
      /** Apparition douce (glisse de 24 px vers le haut par défaut). */
      show: function (target, at, d, from) {
        tl.fromTo($(target), Object.assign({ opacity: 0, y: 24 }, from || {}),
          { opacity: 1, y: 0, x: 0, scale: 1, duration: d || 0.5, ease: "power2.out" }, at);
        return api;
      },
      /** Apparition « pop » (icône, badge). */
      pop: function (target, at) {
        tl.fromTo($(target), { opacity: 0, scale: 0.6 }, { opacity: 1, scale: 1, duration: 0.45, ease: "back.out(2)" }, at);
        return api;
      },
      hide: function (target, at, d) {
        tl.to($(target), { opacity: 0, duration: d || 0.4 }, at);
        return api;
      },
      /** Atténue sans retirer (mettre en avant autre chose). */
      dim: function (target, at, value) {
        tl.to($(target), { opacity: value === undefined ? 0.35 : value, duration: 0.3 }, at);
        return api;
      },
      /** Flèche qui se dessine : <path pathLength="1"> (stroke-dashoffset 1 → 0). */
      draw: function (target, at, d) {
        tl.set($(target), { strokeDasharray: "1 1", strokeDashoffset: 1, opacity: 1 }, 0);
        tl.to($(target), { strokeDashoffset: 0, duration: d || 0.7, ease: "power1.inOut" }, at);
        return api;
      },
      /** Paquet qui circule par des points [[x,y],…] (centre du paquet), seg secondes par segment. */
      move: function (target, points, at, seg) {
        var s = seg === undefined ? 0.8 : seg;
        tl.set($(target), { x: points[0][0], y: points[0][1] }, 0);
        tl.to($(target), { opacity: 1, duration: 0.25 }, at);
        points.slice(1).forEach(function (p, i) {
          tl.to($(target), { x: p[0], y: p[1], duration: s, ease: "power1.inOut" }, at + 0.15 + i * s);
        });
        return api;
      },
      /** Texte tapé (requête DNS, commande) : élément .typed. */
      typeIn: function (target, at, d) {
        var el = document.querySelector($(target));
        el.style.width = "auto";
        var w = el.getBoundingClientRect().width;
        el.style.width = "0px";
        tl.to(el, { width: w, duration: d || 2, ease: "none" }, at);
        return api;
      },
      /** Pulsation (« c'est lui »). */
      pulse: function (target, at) {
        tl.to($(target), { scale: 1.12, yoyo: true, repeat: 1, duration: 0.3 }, at);
        return api;
      },
      /** Badge/ligne qui passe au vert ou au rouge. */
      color: function (target, at, props) {
        tl.to($(target), Object.assign({ duration: 0.3 }, props), at);
        return api;
      },
      /** Libellés de chapitre de l'en-tête : ids dans l'ordre, débuts des chapitres. */
      chapters: function (ids, starts) {
        ids.forEach(function (c, i) {
          api.show(c, starts[i] + 0.05, 0.5, { y: 12 });
          if (i < ids.length - 1) {
            api.hide(c, starts[i + 1] - 0.05, 0.3);
          }
        });
        return api;
      },
      /** Barre de progression (élément <i> dans .prog) sur toute la durée. */
      progress: function (target, total) {
        tl.fromTo($(target), { scaleX: 0 }, { scaleX: 1, duration: total, ease: "none" }, 0);
        return api;
      },
      /**
       * Sous-titres calés : phrases [[instant, "html avec <b>gras</b>"], …] ; chacune reste jusqu'à la suivante
       * (ou jusqu'à fin). Le texte est enveloppé dans un <span> : un conteneur flex mangerait les espaces du <b>.
       */
      captions: function (boxId, phrases, fin) {
        var box = document.querySelector($(boxId));
        phrases.forEach(function (p, i) {
          var div = document.createElement("div");
          div.id = (box.id || "cap") + "-" + i;
          div.innerHTML = "<span>" + p[1] + "</span>";
          box.appendChild(div);
          var next = phrases[i + 1] ? phrases[i + 1][0] : fin;
          tl.fromTo(div, { opacity: 0, y: 8 }, { opacity: 1, y: 0, duration: 0.25 }, p[0]);
          tl.to(div, { opacity: 0, duration: 0.2 }, Math.max(p[0] + 0.3, next - 0.2));
        });
        return api;
      },
      /** Fixe la durée totale de la composition (dernier instant de la timeline). */
      end: function (total) {
        tl.set({}, {}, total);
        return api;
      }
    };
    return api;
  }

  function norm(s) {
    return String(s).toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/[^a-z0-9]/g, "");
  }

  /**
   * L'instant (début + decalage) de la n-ième occurrence d'un mot dans un .mots.json de voix_off
   * ({mots:[{mot,debut,fin}]} ou le tableau seul), décalé du début du chapitre si fourni. undefined si absent.
   */
  function motA(mots, mot, n, decalage, debutChapitre) {
    var list = Array.isArray(mots) ? mots : (mots && mots.mots) || [];
    var want = norm(mot);
    var seen = 0;
    for (var i = 0; i < list.length; i++) {
      if (norm(list[i].mot) === want && ++seen === (n || 1)) {
        return (debutChapitre || 0) + list[i].debut + (decalage || 0);
      }
    }
    return undefined;
  }

  global.Briques = { timeline: timeline, motA: motA };
})(window);
