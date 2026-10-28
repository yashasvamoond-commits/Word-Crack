/*
 * WordCraft Android host bridge (top frame of assets/host.html).
 *
 * WordCraft runs in a same-origin iframe with ?host=parent and speaks its documented host
 * protocol (see apps/wordcraft-web/src/host.rs upstream):
 *
 *   child -> parent: wordcraft:ready {version}
 *                    wordcraft:save {name, saveAs, bytes(ArrayBuffer)}
 *                    wordcraft:dirty {dirty}
 *                    wordcraft:failed {error}
 *   parent -> child: wordcraft:open {name, bytes(ArrayBuffer)}
 *
 * This script is the host:
 *
 *   1. A document launched with an Android VIEW intent is staged natively (BlobBridge). After
 *      wordcraft:ready the bytes are pulled in 256 KiB chunks and posted as wordcraft:open, so
 *      the document opens directly - no home screen and no second file picker.
 *   2. wordcraft:save blobs (Save, Save As and every export) are streamed to BlobBridge, which
 *      writes Downloads/WordCraft, overwriting the session's same-named file.
 *   3. Print still uses WordCraft's hidden blob: iframe inside the child; a MutationObserver on
 *      the same-origin child document catches it and hands the PDF to the system viewer.
 *   4. Page errors are forwarded to BlobBridge.logError (logcat + rate-limited toast).
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
  var APP_ORIGIN = window.location.origin;

  var iframe = null;
  var appReady = false;
  var openSent = false;
  var childObserved = false;

  function reportError(where, error) {
    var detail = error && error.message ? error.message : String(error);
    console.error('WordCraft ' + where + ' failed:', error);
    try {
      bridge.logError(where + ': ' + detail);
    } catch (ignored) {
      // The native interface is unavailable; the console entry is all we have.
    }
  }

  // ------------------------------------------------------------- blob -> native

  function streamBlobToNative(name, blob, kind) {
    var id = bridge.begin(name, blob.size, blob.type || '', kind);
    if (!id) {
      return Promise.reject(new Error('native transfer rejected'));
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
  }

  // ----------------------------------------------------- VIEW intent -> wordcraft:open

  function pullPendingFile() {
    var infoJson = bridge.pendingFileInfo();
    if (!infoJson) {
      return null;
    }
    var info = JSON.parse(infoJson);
    var parts = [];
    var offset = 0;

    function pull() {
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
      return pull();
    }

    return pull().then(function () {
      var total = 0;
      parts.forEach(function (part) { total += part.length; });
      var combined = new Uint8Array(total);
      var written = 0;
      parts.forEach(function (part) {
        combined.set(part, written);
        written += part.length;
      });
      // The native copy is consumed once it has been handed to the app.
      bridge.clearPendingFile();
      return { name: info.name, bytes: combined };
    });
  }

  function tryOpenPendingFile() {
    if (!appReady || openSent || !iframe || !iframe.contentWindow) {
      return;
    }
    var file;
    try {
      file = pullPendingFile();
    } catch (error) {
      reportError('shared-document', error);
      return;
    }
    if (!file) {
      return;
    }
    openSent = true;
    try {
      // Transfer the buffer: the child takes ownership, no copy stays in this frame.
      iframe.contentWindow.postMessage(
        { type: 'wordcraft:open', name: file.name, bytes: file.bytes.buffer },
        APP_ORIGIN,
        [file.bytes.buffer]
      );
    } catch (error) {
      openSent = false;
      reportError('shared-document', error);
    }
  }

  // The VIEW-intent file is staged before the page loads in the cold-start path, but keep
  // polling for a few seconds so a late onNewIntent staging is not missed either.
  function pollForPendingFile() {
    var attempts = 0;
    var timer = setInterval(function () {
      if (openSent || ++attempts > 40) {
        clearInterval(timer);
        return;
      }
      tryOpenPendingFile();
    }, 250);
  }

  // ------------------------------------------------------------- wordcraft:save

  function handleSave(data) {
    if (!data || !data.name || !data.bytes) {
      reportError('save', new Error('malformed wordcraft:save message'));
      return;
    }
    var blob = new Blob([data.bytes], { type: 'application/octet-stream' });
    streamBlobToNative(data.name, blob, KIND_DOWNLOAD).catch(function (error) {
      reportError('save', error);
    });
  }

  // ------------------------------------------------------------------ print

  function observeChildFrame() {
    if (childObserved || !iframe) {
      return;
    }
    var childWindow = iframe.contentWindow;
    var childDocument = iframe.contentDocument;
    if (!childWindow || !childDocument || !childDocument.documentElement) {
      return;
    }
    childObserved = true;
    // WebView has no print support; WordCraft's own window.print() call must be a no-op here.
    childWindow.print = function () {};

    var handled = Object.create(null);
    var observer = new MutationObserver(function (mutations) {
      mutations.forEach(function (mutation) {
        var nodes = Array.prototype.slice.call(mutation.addedNodes || []);
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
          // Fetch inside the child realm: the blob: URL belongs to its blob URL store.
          childWindow.fetch(node.src)
            .then(function (response) { return response.blob(); })
            .then(function (blob) {
              return streamBlobToNative(
                'wordcraft-print.pdf',
                new Blob([blob], { type: 'application/pdf' }),
                KIND_VIEW
              );
            })
            .catch(function (error) {
              reportError('print', error);
            });
        });
      });
    });
    observer.observe(childDocument.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['src']
    });
  }

  // ------------------------------------------------------------- wire-up

  function bindIframe() {
    iframe = document.getElementById('app');
    if (!iframe) {
      return;
    }
    var loading = document.getElementById('loading');
    iframe.addEventListener('load', function () {
      if (loading) {
        loading.style.display = 'none';
      }
      observeChildFrame();
    });
    // In case the load event already fired before this script ran, keep retrying until the
    // same-origin child document is observable (at most ~30 s).
    var attempts = 0;
    var timer = setInterval(function () {
      if (childObserved || ++attempts > 150) {
        clearInterval(timer);
        return;
      }
      if (iframe.contentDocument && iframe.contentDocument.readyState === 'complete') {
        if (loading) {
          loading.style.display = 'none';
        }
        observeChildFrame();
      }
    }, 200);
  }

  window.addEventListener('message', function (event) {
    if (event.origin !== APP_ORIGIN || !iframe || event.source !== iframe.contentWindow) {
      return;
    }
    var data = event.data;
    if (!data || typeof data !== 'object') {
      return;
    }
    switch (data.type) {
      case 'wordcraft:ready':
        appReady = true;
        tryOpenPendingFile();
        break;
      case 'wordcraft:save':
        handleSave(data);
        break;
      case 'wordcraft:failed':
        reportError('wordcraft', new Error(data.error || 'the app failed to start'));
        break;
      case 'wordcraft:dirty':
        // Dirty state is only relevant to a host that guards navigation; nothing to do.
        break;
      default:
        // Unknown message types are ignored forward-compatibly.
        break;
    }
  });

  window.addEventListener('error', function (event) {
    reportError('page', event.message || ('error at ' + event.filename + ':' + event.lineno));
  });
  window.addEventListener('unhandledrejection', function (event) {
    var reason = event.reason && (event.reason.message || event.reason.name) || event.reason;
    reportError('promise', reason || 'unhandled rejection');
  });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () {
      bindIframe();
      pollForPendingFile();
    }, { once: true });
  } else {
    bindIframe();
    pollForPendingFile();
  }
})();
