package ai.storyteller.wordcraft;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hosts the pre-built WordCraft WebAssembly site ({@code assets/web}) in a WebView.
 *
 * <ul>
 *   <li>Save/Export anchor downloads are streamed to Downloads/WordCraft via {@link BlobBridge}.</li>
 *   <li>Print PDFs open in the system viewer (WebView has no {@code window.print()} support).</li>
 *   <li>Open/Insert-picture file pickers map to ACTION_OPEN_DOCUMENT.</li>
 *   <li>Documents opened from other apps (VIEW intent) are queued for the app's own picker.</li>
 * </ul>
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "WordCraft";

    /**
     * The bundled site is served from {@code assets/web} through {@link WebViewAssetLoader} on a
     * virtual HTTPS origin. A real origin keeps ES-module loading, {@code fetch()} and blob URLs
     * working exactly like a hosted page (a plain file:// URL breaks module CORS in WebView).
     */
    private static final String START_URL =
            "https://appassets.androidplatform.net/assets/web/index.html";
    private static final String BRIDGE_SCRIPT = "web-bridge.js";

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private BlobBridge bridge;
    private String bridgeScript;
    private ValueCallback<Uri[]> filePathCallback;

    // Android 8.0/8.1 only: grants the shared-Downloads write used by the legacy save path.
    private final ActivityResultLauncher<String> storagePermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted ->
                    Log.i(TAG, "WRITE_EXTERNAL_STORAGE granted=" + granted));

    private final ActivityResultLauncher<Intent> documentPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                ValueCallback<Uri[]> callback = filePathCallback;
                filePathCallback = null;
                if (callback == null) {
                    return;
                }
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    callback.onReceiveValue(null);
                    return;
                }
                Intent data = result.getData();
                List<Uri> uris = new ArrayList<>();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        uris.add(data.getClipData().getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                for (Uri uri : uris) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) {
                        // Some providers do not offer persistable access; the one-shot grant still
                        // covers reading the document during this session.
                    }
                }
                callback.onReceiveValue(uris.toArray(new Uri[0]));
            });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        bridge = new BlobBridge(this);
        bridgeScript = readAsset(BRIDGE_SCRIPT);
        if (getIntent() != null && Intent.ACTION_VIEW.equals(getIntent().getAction())) {
            queueViewIntent(getIntent());
        }

        // /assets/* URLs resolve against the APK's android_asset directory.
        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        // WordCraft keeps preferences in localStorage and may use IndexedDB.
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // Some older file managers return file:// URIs from the picker; allow reading those even
        // though the app itself only loads the virtual HTTPS origin below.
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new WordCraftWebViewClient());
        webView.setWebChromeClient(new WordCraftChromeClient());
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            // Blob URLs are intercepted in web-bridge.js before they reach here; this only
            // handles ordinary http(s) downloads, if any ever appear.
            if (url != null && url.startsWith("blob:")) {
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                webView.evaluateJavascript(
                        "window.__wordcraftSaveBlob && window.__wordcraftSaveBlob("
                                + jsString(name) + "," + jsString(url) + ");", null);
                return;
            }
            enqueueSystemDownload(url, contentDisposition, mimeType);
        });
        webView.addJavascriptInterface(bridge, "AndroidBridge");
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        requestLegacyStoragePermission();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            // Rebuild the page so the freshly queued file reaches the app's first picker click.
            queueViewIntent(intent);
            webView.loadUrl(START_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void requestLegacyStoragePermission() {
        // MediaStore needs no permission on API 29+; only the API 26-28 public-folder write does.
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P
                && ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    private void queueViewIntent(Intent intent) {
        Uri uri = intent.getData();
        if (uri == null) {
            return;
        }
        String name = queryDisplayName(uri);
        String mime = getContentResolver().getType(uri);
        File destination = new File(getCacheDir(), "pending-" + System.nanoTime());
        try (InputStream in = getContentResolver().openInputStream(uri);
                OutputStream out = new FileOutputStream(destination)) {
            if (in == null) {
                throw new java.io.IOException("could not open " + uri);
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            Log.e(TAG, "could not stage VIEW intent file", e);
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
            //noinspection ResultOfMethodCallIgnored
            destination.delete();
            return;
        }
        bridge.setPendingFile(destination, name, mime);
    }

    private String queryDisplayName(Uri uri) {
        String result = null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    result = cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
            // Fall through to the URI-based name below.
        }
        if (result == null || result.isEmpty()) {
            result = uri.getLastPathSegment();
        }
        return (result == null || result.isEmpty()) ? "document.docx" : result;
    }

    private void enqueueSystemDownload(String url, String contentDisposition, String mimeType) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "WordCraft" + File.separator + name);
            request.setTitle(name);
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            manager.enqueue(request);
        } catch (Exception e) {
            Log.e(TAG, "system download failed", e);
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
        }
    }

    private String readAsset(String name) {
        // Manual read loop: InputStream.readAllBytes() is only available on Android 13+, but the
        // app supports API 26.
        try (InputStream in = getAssets().open(name);
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            throw new IllegalStateException("missing bundled asset " + name, e);
        }
    }

    private static String jsString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private final class WordCraftWebViewClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return assetLoader.shouldInterceptRequest(request.getUrl());
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            injectBridge(view);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            injectBridge(view);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                android.webkit.WebResourceError error) {
            Log.e(TAG, "resource error " + error.getErrorCode() + " for " + request.getUrl());
        }

        private void injectBridge(WebView view) {
            if (bridgeScript != null) {
                view.evaluateJavascript(bridgeScript, null);
            }
        }
    }

    private final class WordCraftChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;
            Intent intent = buildChooserIntent(params);
            if (intent == null) {
                filePathCallback = null;
                callback.onReceiveValue(null);
                Toast.makeText(MainActivity.this, R.string.no_file_picker, Toast.LENGTH_LONG).show();
                return false;
            }
            try {
                documentPicker.launch(intent);
            } catch (Exception e) {
                Log.e(TAG, "no file picker available", e);
                filePathCallback = null;
                callback.onReceiveValue(null);
                Toast.makeText(MainActivity.this, R.string.no_file_picker, Toast.LENGTH_LONG).show();
                return false;
            }
            return true;
        }

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
            Log.println(priorityFor(consoleMessage.messageLevel()), TAG,
                    consoleMessage.message() + " (" + consoleMessage.sourceId()
                            + ":" + consoleMessage.lineNumber() + ")");
            return true;
        }

        private int priorityFor(android.webkit.ConsoleMessage.MessageLevel level) {
            if (level == null) {
                return Log.INFO;
            }
            return switch (level) {
                case ERROR -> Log.ERROR;
                case WARNING -> Log.WARN;
                case DEBUG -> Log.DEBUG;
                default -> Log.INFO;
            };
        }

        /**
         * Builds the safest available picker intent. WebView's own
         * {@link FileChooserParams#createIntent()} already encodes the accept types, the
         * single/multiple mode and the File System Access permission mode for the platform's
         * WebView version. Devices without a DocumentsUI provider (some tablets and custom ROMs)
         * get an ACTION_GET_CONTENT fallback, which a much wider range of apps can handle.
         */
        private Intent buildChooserIntent(FileChooserParams params) {
            Intent primary = null;
            try {
                primary = params.createIntent();
                primary.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (Intent.ACTION_OPEN_DOCUMENT.equals(primary.getAction())) {
                    primary.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                }
            } catch (Exception e) {
                Log.w(TAG, "WebView createIntent failed, using GET_CONTENT", e);
            }
            if (primary != null && primary.resolveActivity(getPackageManager()) != null) {
                return primary;
            }

            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            Set<String> mimes = acceptMimeTypes(params.getAcceptTypes());
            if (mimes.isEmpty()) {
                fallback.setType("*/*");
            } else if (mimes.size() == 1) {
                fallback.setType(mimes.iterator().next());
            } else {
                fallback.setType("*/*");
                fallback.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
            }
            if (fallback.resolveActivity(getPackageManager()) != null) {
                return fallback;
            }
            return null;
        }

        private Set<String> acceptMimeTypes(String[] acceptTypes) {
            Set<String> mimes = new LinkedHashSet<>();
            if (acceptTypes == null) {
                return mimes;
            }
            for (String accept : acceptTypes) {
                if (accept == null || accept.isEmpty()) {
                    continue;
                }
                if (accept.startsWith(".")) {
                    String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                            accept.substring(1).toLowerCase(Locale.US));
                    if (mime != null) {
                        mimes.add(mime);
                    }
                } else if (accept.contains("/")) {
                    mimes.add(accept);
                }
            }
            return mimes;
        }
    }
}
