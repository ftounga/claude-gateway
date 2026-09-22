package fr.claudegateway.pages;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Le rendu des diagrammes Mermaid dans une page</b> (F-142 / SF-142-01).
 *
 * <p>Une page F-109 est un document HTML servi en origine opaque dans une {@code iframe} bac-à-sable, sous
 * la CSP de {@link PageContentPolicy}. Cette CSP autorise <b>déjà</b> {@code 'self'} — et c'est de là que
 * vient désormais la bibliothèque (F-142 / SF-142-05), servie par {@link PageLibraryController} : un CDN
 * public est injoignable chez un client derrière un proxy, et l'adresse épinglée à l'origine répondait de
 * surcroît <b>404</b>. La CSP autorise aussi le style en ligne : un diagramme Mermaid peut donc être <b>rendu côté navigateur</b>,
 * sans ouvrir la moindre brèche — aucune sortie réseau ({@code connect-src 'none'}) n'est requise pour le
 * rendu lui-même, et Mermaid est initialisé en {@code securityLevel:'strict'} (il assainit le SVG, refuse
 * le HTML arbitraire dans les libellés et les gestionnaires {@code click}).</p>
 *
 * <h2>Ce que fait ce composant</h2>
 *
 * <p>Au moment de <b>servir</b> une page (jamais au stockage : le code Mermaid reste pristine et éditable),
 * si le HTML porte un bloc Mermaid — {@code <pre class="mermaid">…</pre>} ou un bloc clôturé
 * <code>```mermaid … ```</code> — on injecte, une seule fois, un petit <b>runtime client-side</b> : le
 * chargeur de la bibliothèque (version épinglée, {@code cdnjs}), une initialisation stricte, et un rendu
 * <b>par diagramme</b> avec <b>repli gracieux</b> — un bloc invalide affiche son code et un message, sans
 * jamais casser la page ni les autres diagrammes.</p>
 *
 * <h2>Non-régression stricte</h2>
 *
 * <p>Une page <b>sans</b> bloc Mermaid est rendue <b>octet pour octet identique</b> à ce qui est stocké :
 * {@link #render(byte[])} renvoie alors le tableau reçu, inchangé.</p>
 */
public final class PageMermaidRuntime {

    /**
     * Version épinglée de la bibliothèque. <b>11.15.0</b> : elle existe (la 11.4.1 d'origine répondait
     * <b>404</b> sur cdnjs) et elle porte {@code architecture-beta}, dont dépend le repli « icônes cloud »
     * de SF-142-03.
     */
    public static final String MERMAID_VERSION = "11.15.0";

    /**
     * L'adresse du chargeur : <b>la gateway elle-même</b> (F-142 / SF-142-05), jamais un CDN public.
     *
     * <p>Chez un client, un CDN public est injoignable — poste verrouillé, proxy d'entreprise — et c'est
     * le cas d'usage réel, pas une exception. Le chemin est servi par {@link PageLibraryController}, sur
     * la même origine que la page : la CSP l'autorise déjà par {@code 'self'}, inchangée.</p>
     */
    public static final String SCRIPT_URL = "/api/pages/lib/mermaid-" + MERMAID_VERSION + ".min.js";

    /** Le marqueur d'idempotence : présent ⇒ le runtime est déjà là, on ne touche à rien. */
    static final String MARKER = "<!--cg-mermaid-->";

    /** Un bloc clôturé façon Markdown, à convertir en {@code <pre class="mermaid">}. */
    private static final Pattern FENCE = Pattern.compile("```mermaid[ \\t]*\\r?\\n(.*?)```", Pattern.DOTALL);

    /** Un bloc déjà porté par une classe {@code mermaid} (guillemets simples ou doubles). */
    private static final Pattern MERMAID_CLASS = Pattern.compile("class\\s*=\\s*[\"']mermaid[\"']");

    private PageMermaidRuntime() {
    }

    /**
     * Rend le HTML servi d'une page : injecte le runtime Mermaid si un bloc est présent, sinon renvoie le
     * tableau reçu <b>inchangé</b> (byte-identité garantie pour les pages sans diagramme).
     */
    public static byte[] render(byte[] html) {
        if (html == null || html.length == 0) {
            return html;
        }
        String source = new String(html, StandardCharsets.UTF_8);
        if (!containsMermaid(source)) {
            return html;
        }
        String rendered = inject(source);
        return rendered.equals(source) ? html : rendered.getBytes(StandardCharsets.UTF_8);
    }

    /** La même transformation sur une chaîne (variante de commodité pour les tests et l'appel direct). */
    public static String render(String html) {
        if (html == null || html.isEmpty() || !containsMermaid(html)) {
            return html;
        }
        return inject(html);
    }

    /** Vrai si le HTML porte au moins un bloc Mermaid (classe ou bloc clôturé). */
    static boolean containsMermaid(String html) {
        return html != null && (MERMAID_CLASS.matcher(html).find() || html.contains("```mermaid"));
    }

    /**
     * Convertit les blocs clôturés, puis pose le runtime avant le dernier {@code </body>} (à défaut, en
     * fin de document). Idempotent : un HTML déjà porteur du marqueur repart tel quel.
     */
    private static String inject(String html) {
        if (html.contains(MARKER)) {
            return html;
        }
        String converted = convertFences(html);
        String runtime = runtime();
        int body = lastIndexOfIgnoreCase(converted, "</body>");
        if (body >= 0) {
            return converted.substring(0, body) + runtime + converted.substring(body);
        }
        return converted + "\n" + runtime;
    }

    /** Chaque bloc <code>```mermaid … ```</code> devient {@code <pre class="mermaid">…</pre>}, code échappé. */
    private static String convertFences(String html) {
        Matcher matcher = FENCE.matcher(html);
        if (!matcher.find()) {
            return html;
        }
        StringBuilder out = new StringBuilder(html.length() + 64);
        int last = 0;
        matcher.reset();
        while (matcher.find()) {
            out.append(html, last, matcher.start());
            String code = matcher.group(1).replaceAll("\\r?\\n?$", "");
            out.append("<pre class=\"mermaid\">").append(escapeHtml(code)).append("</pre>");
            last = matcher.end();
        }
        out.append(html, last, html.length());
        return out.toString();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static int lastIndexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(java.util.Locale.ROOT).lastIndexOf(needle.toLowerCase(java.util.Locale.ROOT));
    }

    /** Le bloc injecté : marqueur, style, chargeur, et le rendu client-side avec repli gracieux. */
    private static String runtime() {
        return MARKER + "\n" + STYLE + "\n<script src=\"" + SCRIPT_URL + "\"></script>\n<script>"
                + SCRIPT + "</script>\n";
    }

    /** Le style du conteneur de diagramme et de l'encart de repli (clair et sombre). */
    private static final String STYLE = """
            <style>
            .cg-mermaid{overflow:auto;margin:16px 0}
            .cg-mermaid svg{max-width:100%;height:auto}
            .cg-mermaid-fallback{border:1px solid #cbd5e1;border-radius:8px;padding:12px;margin:16px 0;background:#f8fafc}
            .cg-mermaid-fallback p{margin:0 0 8px;color:#b45309;font:600 13px/1.4 system-ui,sans-serif}
            .cg-mermaid-fallback pre{margin:0;overflow:auto;font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;white-space:pre;color:#0f172a}
            @media (prefers-color-scheme: dark){
            .cg-mermaid-fallback{border-color:#334155;background:#0f172a}
            .cg-mermaid-fallback p{color:#fbbf24}
            .cg-mermaid-fallback pre{color:#e2e8f0}
            }
            </style>""";

    /**
     * Le rendu client-side. Chaque bloc est rendu isolément : un diagramme invalide (ou la bibliothèque
     * absente, hors ligne) se replie sur son code + un message, sans casser la page. Init en
     * {@code securityLevel:"strict"} — l'assainissement du SVG par Mermaid est conservé.
     */
    private static final String SCRIPT = """
            (function(){
              var SEL="pre.mermaid, div.mermaid, .mermaid";
              function ready(fn){ if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",fn);}else{fn();} }
              function replace(el, node){ if(el.replaceWith){ el.replaceWith(node); } else if(el.parentNode){ el.parentNode.replaceChild(node, el); } }
              function fallback(el, code, msg){
                var wrap=document.createElement("div");
                wrap.className="cg-mermaid-fallback";
                var p=document.createElement("p");
                p.textContent=msg;
                var pre=document.createElement("pre");
                pre.textContent=code;
                wrap.appendChild(p);
                wrap.appendChild(pre);
                replace(el, wrap);
              }
              ready(function(){
                var nodes=Array.prototype.slice.call(document.querySelectorAll(SEL));
                if(!nodes.length){ return; }
                var items=nodes.map(function(el){ return { el: el, code: (el.getAttribute("data-cg-src")||el.textContent||"").trim() }; });
                // Le build v11 n'expose PAS window.mermaid : il pose son objet dans un namespace
                // esbuild. On accepte les deux noms — se tromper de nom donne exactement le meme
                // symptome qu'une bibliotheque absente, et c'est l'un des deux defauts corriges ici.
                var lib = window.mermaid
                  || (window.__esbuild_esm_mermaid_nm && window.__esbuild_esm_mermaid_nm.mermaid)
                  || (window.mermaidAPI ? window.mermaidAPI : null);
                if(!lib || typeof lib.render !== "function"){
                  items.forEach(function(it){ fallback(it.el, it.code, "Diagramme non rendu : la bibliotheque n'a pas pu etre chargee (hors ligne ?)."); });
                  return;
                }
                var dark=window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
                try { lib.initialize({ startOnLoad:false, securityLevel:"strict", theme: dark?"dark":"default" }); } catch(e){}
                items.forEach(function(it, i){
                  var id="cg-mmd-"+i+"-"+Math.floor(Math.random()*1000000000);
                  try {
                    var out=lib.render(id, it.code);
                    Promise.resolve(out).then(function(res){
                      var box=document.createElement("div");
                      box.className="cg-mermaid";
                      box.innerHTML=(res && res.svg) ? res.svg : String(res);
                      replace(it.el, box);
                    }).catch(function(err){ fallback(it.el, it.code, "Diagramme invalide : voici son code."); });
                  } catch(err){ fallback(it.el, it.code, "Diagramme invalide : voici son code."); }
                });
              });
            })();""";
}
