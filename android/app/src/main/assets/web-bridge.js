/*
 * WordCraft Android WebView bridge.
 *
 * Injected on every page load by MainActivity. The WordCraft web app is an unchanged WASM build
 * designed for a desktop browser, so this script adapts browser mechanisms to Android:
 *
 *   1. Save/Export  - recent Android WebViews expose window.showSaveFilePicker, but its promise
 *                     never settles (no File System Access UI), which would hang WordCraft's save
 *                     forever. We shadow it with undefined so the app takes its anchor-download
 *                     fallback, intercept the hidden <a download> click, and stream the blob to
 *                     AndroidBridge in 256 KiB chunks for Downloads/WordCraft.
 *   2. Print        - the app loads a PDF in a hidden iframe and calls print() on it. WebView has
 *                     no print support, so the same PDF blob is handed to the system PDF viewer
 *                     through a FileProvider VIEW intent (the viewer offers Android's print).
 *   3. Open-in-app  - documents launched with a VIEW intent are staged natively, reconstructed as
 *                     a File and fed to the app's next <input type=file>.click() instead of
 *                     opening a second picker.
 *
 * All transfer chunks stay well under the Binder transaction size limit (256 KiB raw bytes).
 */
(function () {
  'use strict';
  if (window.__wordcraftBridgeInstalled) {
    return;
  }
  var bridge = window.AndroidBridge;
  if (!bridge) {
    console.error('WordCraft: AndroidBridge interface missing');
    return;
  }
  window.__wordcraftBridgeInstalled = true;

  var CHUNK_BYTES = 256 * 1024;
  var KIND_DOWNLOAD = 0;
  var KIND_VIEW = 1;

  function reportError(where, error) {
    var detail = error && error.message ? error.message : String(error);
    console.error('WordCraft ' + where + ' failed:', error);
    try {
      bridge.logError(where + ': ' + detail);
    } catch (ignored) {
      // The native interface is unavailable; the console entry is all we have.
    }
  }

  // ------------------------------------------------------------------ save picker shadow

  // WordCraft feature-detects showSaveFilePicker and prefers it. On Android WebView the function
  // exists (recent versions) but the returned promise never resolves or rejects, leaving the save
  // ticket open forever. Shadow it so the app's own anchor-download fallback runs instead.
  function shadowBrokenSavePicker() {
    try {
      Object.defineProperty(window, 'showSaveFilePicker', {
        value: undefined,
        configurable: true,
        writable: true
      });
    } catch (error) {
      try {
        window.showSaveFilePicker = undefined;
      } catch (ignored) {
        reportError('save-picker-shadow', error);
      }
    }
  }

  // Run immediately: the WASM module only checks the property on a Save tap, but defining it
  // before any page script runs leaves no window where the broken API is visible.
  shadowBrokenSavePicker();

  // ------------------------------------------------------------------ blob -> native

  // Fetches a blob: URL and streams it to the native side.
  function sendBlob(name, blobUrl, kind) {
    return fetch(blobUrl).then(function (response) {
      return response.blob();
    }).then(function (blob) {
      var id = bridge.begin(name, blob.size, blob.type || '', kind);
      if (!id) {
        throw new Error('native transfer rejected');
      }
      var offset = 0;
      return new Promise(function (resolve, reject) {
        function pump() {
          if (offset >= blob.size) {
            bridge.finish(id);
            resolve();
            return;
          }
          var slice = blob.slice(offset, Math.min(offset + CHUNK_BYTES, blob.size));
          var reader = new FileReader();
          reader.onload = function () {
            var dataUrl = reader.result;
            var comma = dataUrl.indexOf(',');
            var ok = bridge.append(id, comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl);
            if (!ok) {
              bridge.cancel(id, 'append-failed');
              reject(new Error('native append failed'));
              return;
            }
            offset += CHUNK_BYTES;
            pump();
          };
          reader.onerror = function () {
            bridge.cancel(id, 'read-failed');
            reject(reader.error || new Error('blob read failed'));
          };
          reader.readAsDataURL(slice);
        }
        pump();
      });
    });
  }

  // Exposed for MainActivity's DownloadListener fallback (ordinary blob URLs that reach it).
  window.__wordcraftSaveBlob = function (name, blobUrl) {
    return sendBlob(name || 'wordcraft-file', blobUrl, KIND_DOWNLOAD)
      .catch(function (error) {
        reportError('save', error);
      });
  };

  function installAnchorInterceptor() {
    // Capture phase: run before the click reaches the anchor itself. WordCraft (and rfd's save
    // dialog) build <a download href="blob:..."> nodes and call .click() on them.
    document.addEventListener('click', function (event) {
      var anchor = event.target && event.target.closest
        ? event.target.closest('a[download]')
        : null;
      if (!anchor || !anchor.href || anchor.href.indexOf('blob:') !== 0) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      var url = anchor.href;
      var name = anchor.download || 'wordcraft-file';
      sendBlob(name, url, KIND_DOWNLOAD).catch(function (error) {
        reportError('save', error);
      });
      // The app revokes itself after 10 s; keep the URL alive until our fetch is done plus margin.
      setTimeout(function () {
        try {
          URL.revokeObjectURL(url);
        } catch (ignored) {
          // Already revoked by the app.
        }
      }, 60000);
    }, true);

    // Second safety net: clicks dispatched programmatically still produce DOM events above, but
    // some overlay libraries call preventDefault themselves. Intercept the method directly too.
    var originalAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      if (this.href && this.href.indexOf('blob:') === 0 && this.hasAttribute('download')) {
        var url = this.href;
        var name = this.download || 'wordcraft-file';
        sendBlob(name, url, KIND_DOWNLOAD).catch(function (error) {
          reportError('save', error);
        });
        return;
      }
      return originalAnchorClick.apply(this, arguments);
    };
  }

  function installPrintObserver() {
    // window.print() itself is a no-op in WebView; the iframe observer does the real work.
    window.print = function () {};
    var handled = Object.create(null);
    var observer = new MutationObserver(function (mutations) {
      mutations.forEach(function (mutation) {
        var nodes = [];
        if (mutation.addedNodes) {
          for (var i = 0; i < mutation.addedNodes.length; i++) {
            nodes.push(mutation.addedNodes[i]);
          }
        }
        if (mutation.target && mutation.target.nodeName === 'IFRAME') {
          nodes.push(mutation.target);
        }
        nodes.forEach(function (node) {
          if (node.nodeName !== 'IFRAME' || !node.src || node.src.indexOf('blob:') !== 0) {
            return;
          }
          if (handled[node.src]) {
            return;
          }
          handled[node.src] = true;
          sendBlob('wordcraft-print.pdf', node.src, KIND_VIEW).catch(function (error) {
            reportError('print', error);
          });
        });
      });
    });
    observer.observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['src']
    });
  }

  // Pulls a document staged from a VIEW intent and queues it for the app's next file picker.
  function installPendingFile() {
    var attempts = 0;
    function poll() {
      var infoJson = null;
      try {
        infoJson = bridge.pendingFileInfo();
      } catch (error) {
        infoJson = null;
      }
      if (infoJson) {
        consumePendingFile(infoJson);
        return;
      }
      if (++attempts < 20) {
        setTimeout(poll, 250);
      }
    }

    function consumePendingFile(infoJson) {
      var info;
      try {
        info = JSON.parse(infoJson);
      } catch (error) {
        reportError('shared-document', error);
        return;
      }
      var parts = [];
      var offset = 0;

      function pullNext() {
        var base64 = bridge.readPendingChunk(offset, CHUNK_BYTES);
        if (!base64) {
          return Promise.resolve();
        }
        var binary = atob(base64);
        var bytes = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i++) {
          bytes[i] = binary.charCodeAt(i);
        }
        parts.push(bytes);
        offset += bytes.length;
        if (offset >= info.size) {
          return Promise.resolve();
        }
        return pullNext();
      }

      pullNext().then(function () {
        var file = new File(parts, info.name, { type: info.mime || '' });
        window.__wordcraftPendingFile = file;
        bridge.clearPendingFile();
      }).catch(function (error) {
        reportError('shared-document', error);
      });
    }

    // rfd's WASM backend clicks a hidden <input type=file>. Serve the queued File to that click
    // instead of letting the system picker open.
    var originalClick = HTMLInputElement.prototype.click;
    HTMLInputElement.prototype.click = function () {
      if (this.type === 'file' && window.__wordcraftPendingFile) {
        var file = window.__wordcraftPendingFile;
        window.__wordcraftPendingFile = null;
        try {
          var dataTransfer = new DataTransfer();
          dataTransfer.items.add(file);
          this.files = dataTransfer.files;
          this.dispatchEvent(new Event('change', { bubbles: true }));
          return;
        } catch (error) {
          reportError('shared-document', error);
        }
      }
      return originalClick.apply(this, arguments);
    };

    poll();
  }

  function installErrorForwarding() {
    window.addEventListener('error', function (event) {
      reportError('page', event.message || ('error at ' + event.filename + ':' + event.lineno));
    });
    window.addEventListener('unhandledrejection', function (event) {
      var reason = event.reason && (event.reason.message || event.reason.name) || event.reason;
      reportError('promise', reason || 'unhandled rejection');
    });
  }

  function install() {
    try {
      installAnchorInterceptor();
      installPrintObserver();
      installPendingFile();
      installErrorForwarding();
    } catch (error) {
      reportError('bridge-install', error);
    }
  }

  if (document.documentElement) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', install, { once: true });
    } else {
      install();
    }
  } else {
    document.addEventListener('DOMContentLoaded', install, { once: true });
  }
})();
