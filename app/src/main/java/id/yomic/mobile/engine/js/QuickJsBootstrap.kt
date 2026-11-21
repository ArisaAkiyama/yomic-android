package id.yomic.mobile.engine.js

/**
 * Pure JS bootstrap prelude evaluated once per QuickJS runtime instance.
 * Bridges Yomic's extension contract (`Html.parse`, `fetch`, `atob`, `btoa`, `log`, `console`)
 * over primitive JNI host functions.
 */
object QuickJsBootstrap {
    val PRELUDE: String = """
        (function() {
            function __wrapEl(id) {
                if (typeof id !== 'number' || id < 0) return null;
                return {
                    text: function() { return __dom_text(id); },
                    attr: function(name) { return __dom_attr(id, String(name || '')); },
                    absUrl: function(attrName) { return __dom_absUrl(id, String(attrName || '')); },
                    outerHtml: function() { return __dom_html(id, true); },
                    innerHtml: function() { return __dom_html(id, false); },
                    get tagName() { return __dom_tagName(id); },
                    get parentElement() { return __wrapEl(__dom_parent(id)); },
                    get nextElementSibling() { return __wrapEl(__dom_nextSibling(id)); },
                    querySelector: function(sel) { return __wrapEl(__dom_qs(id, String(sel || ''))); },
                    querySelectorAll: function(sel) {
                        var raw = __dom_qsa(id, String(sel || ''));
                        if (!raw) return [];
                        var ids = JSON.parse(raw);
                        var out = new Array(ids.length);
                        for (var i = 0; i < ids.length; i++) out[i] = __wrapEl(ids[i]);
                        return out;
                    }
                };
            }

            globalThis.Html = {
                parse: function(html, baseUri) {
                    var docId = __dom_parse(String(html == null ? '' : html), String(baseUri == null ? '' : baseUri));
                    return __wrapEl(docId);
                }
            };

            globalThis.fetch = function(url, options) {
                var optsJson = options ? JSON.stringify(options) : '';
                var raw = __http_fetch(String(url == null ? '' : url), optsJson);
                var parsed = raw ? JSON.parse(raw) : { status: 500, body: '' };
                parsed.text = function() { return this.body || ''; };
                return parsed;
            };

            globalThis.atob = function(b64) {
                return __b64_decode(String(b64 == null ? '' : b64));
            };

            globalThis.btoa = function(str) {
                return __b64_encode(String(str == null ? '' : str));
            };

            globalThis.log = function(msg) {
                __yomic_log(msg == null ? 'null' : String(msg));
            };

            if (typeof globalThis.console === 'undefined') {
                globalThis.console = {
                    log: function() { __yomic_log(Array.prototype.slice.call(arguments).join(' ')); },
                    warn: function() { __yomic_log('[WARN] ' + Array.prototype.slice.call(arguments).join(' ')); },
                    error: function() { __yomic_log('[ERR] ' + Array.prototype.slice.call(arguments).join(' ')); }
                };
            }

            globalThis.__hasMethod = function(methodName) {
                return typeof source === 'object' && source !== null && typeof source[methodName] === 'function';
            };

            globalThis.__readMetadataJson = function() {
                if (typeof source !== 'object' || source === null) {
                    return JSON.stringify({ valid: false });
                }
                return JSON.stringify({
                    valid: true,
                    id: (typeof source.id === 'number') ? source.id : null,
                    name: String(source.name || ''),
                    baseUrl: String(source.baseUrl || ''),
                    apiUrl: String(source.apiUrl || ''),
                    language: String(source.language || 'id'),
                    version: String(source.version || '1.0.0'),
                    description: String(source.description || ''),
                    author: String(source.author || 'Unknown'),
                    iconUrl: String(source.iconUrl || ''),
                    iconBackground: String(source.iconBackground || '#1e293b'),
                    iconForeground: String(source.iconForeground || '#38bdf8'),
                    isNsfw: source.isNsfw === true || source.isNsfw === 1 || source.nsfw === 1 || String(source.isNsfw).toLowerCase() === 'true',
                    isHasMorePages: source.isHasMorePages !== false,
                    requiresProxy: source.requiresProxy === true,
                    isCloudflareProtected: source.isCloudflareProtected === true,
                    chapterReadySelector: String(source.chapterReadySelector || ''),
                    pageWebViewExtractScript: (typeof source.pageWebViewExtractScript === 'string' && source.pageWebViewExtractScript.length > 0) ? source.pageWebViewExtractScript : null,
                    userAgent: (typeof source.userAgent === 'string' && source.userAgent.length > 0) ? source.userAgent : null,
                    genres: Array.isArray(source.genres) ? source.genres.map(String) : [],
                    formats: Array.isArray(source.formats) ? source.formats.map(String) : [],
                    supportsMangaList: typeof source.getMangaList === 'function',
                    supportsLatestChapters: typeof source.getLatestChapters === 'function'
                });
            };

            globalThis.__callMethodJson = function(methodName, argsJson, selectedLang) {
                if (typeof source !== 'object' || source === null) {
                    return JSON.stringify({ ok: false, error: 'Missing source object' });
                }
                if (selectedLang) {
                    source.selectedLanguage = selectedLang;
                }
                source.lastRequiresVerification = false;
                if (typeof source[methodName] !== 'function') {
                    return JSON.stringify({ ok: false, missing: true });
                }
                try {
                    var args = argsJson ? JSON.parse(argsJson) : [];
                    var res = source[methodName].apply(source, args);
                    return JSON.stringify({
                        ok: true,
                        result: res === undefined ? null : res,
                        requiresVerification: Boolean(source.lastRequiresVerification)
                    });
                } catch (e) {
                    return JSON.stringify({
                        ok: false,
                        error: (e && e.message) ? String(e.message) : String(e)
                    });
                }
            };
        })();
    """.trimIndent()
}
