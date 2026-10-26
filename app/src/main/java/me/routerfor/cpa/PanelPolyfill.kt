package me.routerfor.cpa

/**
 * The management panel bundle is built against a modern browser baseline and calls
 * ES2022 APIs (Object.hasOwn, Array/String.prototype.at, String.prototype.replaceAll,
 * crypto.randomUUID). Android 8-10 devices and older system WebViews ship Chromium 83
 * and below, where those calls throw at module-evaluation time and the SPA renders
 * nothing but a white screen. minSdk is 26, so the panel has to survive on those
 * WebViews instead of assuming a current one.
 *
 * The shim is injected server-side: [MainActivity]'s WebViewClient intercepts
 * /management.html, rewrites the document and puts this script right after <head> so it
 * is guaranteed to run before the panel's own module script.
 */
object PanelPolyfill {

    const val INJECTION_MARKER = "<!--cpa-polyfill-->"

    val SCRIPT: String = """
(function () {
  function def(target, name, value) {
    try {
      Object.defineProperty(target, name, { configurable: true, writable: true, value: value });
    } catch (e) {
      target[name] = value;
    }
  }

  if (typeof Object.hasOwn !== 'function') {
    def(Object, 'hasOwn', function (obj, key) {
      if (obj === null || obj === undefined) {
        throw new TypeError('Cannot convert undefined or null to object');
      }
      return Object.prototype.hasOwnProperty.call(Object(obj), key);
    });
  }

  if (typeof String.prototype.replaceAll !== 'function') {
    def(String.prototype, 'replaceAll', function (search, replacement) {
      if (search instanceof RegExp) {
        if (!search.global) {
          throw new TypeError('replaceAll must be called with a global RegExp');
        }
        return this.replace(search, replacement);
      }
      return this.split(String(search)).join(String(replacement));
    });
  }

  function at(index) {
    var len = this.length >>> 0;
    var n = Math.trunc ? Math.trunc(index) || 0 : (index | 0);
    if (n < 0) {
      n += len;
    }
    if (n < 0 || n >= len) {
      return undefined;
    }
    return this[n];
  }

  if (typeof Array.prototype.at !== 'function') {
    def(Array.prototype, 'at', at);
  }
  if (typeof String.prototype.at !== 'function') {
    def(String.prototype, 'at', function (index) {
      return at.call(String(this), index);
    });
  }

  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID !== 'function') {
    var randomUUID = function () {
      var bytes = new Uint8Array(16);
      if (typeof crypto.getRandomValues === 'function') {
        crypto.getRandomValues(bytes);
      } else {
        for (var i = 0; i < 16; i++) {
          bytes[i] = Math.floor(Math.random() * 256);
        }
      }
      bytes[6] = (bytes[6] & 15) | 64;
      bytes[8] = (bytes[8] & 63) | 128;
      var hex = '';
      for (var j = 0; j < 16; j++) {
        hex += (bytes[j] + 256).toString(16).slice(1);
      }
      return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' +
        hex.slice(16, 20) + '-' + hex.slice(20);
    };
    try {
      def(crypto, 'randomUUID', randomUUID);
    } catch (e) {
      /* some engines expose crypto as a frozen interface; the panel's own fallback covers it */
    }
  }

  if (typeof queueMicrotask !== 'function') {
    def(window, 'queueMicrotask', function (fn) {
      Promise.resolve().then(fn);
    });
  }

  window.__cpaPolyfilled = true;
})();
""".trimIndent()

    /** Inserts [SCRIPT] as the first thing inside <head>; returns the body unchanged if it cannot. */
    fun inject(html: String): String {
        if (html.contains(INJECTION_MARKER)) return html
        val block = INJECTION_MARKER + "\n<script>\n" + SCRIPT + "\n</script>\n"
        val head = html.indexOf("<head>", ignoreCase = true)
        if (head >= 0) {
            val cut = head + "<head>".length
            return html.substring(0, cut) + "\n" + block + html.substring(cut)
        }
        val htmlTag = html.indexOf("<html", ignoreCase = true)
        if (htmlTag >= 0) {
            val cut = html.indexOf('>', htmlTag)
            if (cut > 0) return html.substring(0, cut + 1) + "\n" + block + html.substring(cut + 1)
        }
        return block + html
    }
}
