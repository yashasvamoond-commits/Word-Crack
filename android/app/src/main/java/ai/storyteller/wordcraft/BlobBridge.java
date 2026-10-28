package ai.storyteller.wordcraft;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Functions exposed to {@code web-bridge.js} as {@code window.AndroidBridge}.
 *
 * <p>{@code blob:} URLs only exist inside the WebView renderer, so the page fetches a blob and
 * streams it here in base64 chunks: {@link #begin} opens a temp file, {@link #append} writes each
 * chunk and {@link #finish} either publishes the file to the Downloads folder or opens it in
 * another app (the print path) through a {@link FileProvider} intent.
 *
 * <p>The same chunked transport in reverse serves documents launched with a VIEW intent:
 * {@link #pendingFileInfo}, {@link #readPendingChunk} and {@link #clearPendingFile} let the page
 * reconstruct the {@code File} object and feed it to the app's own file-picker code.
 */
class BlobBridge {

    private static final int KIND_DOWNLOAD = 0;
    private static final int KIND_VIEW = 1;
    private static final String DOWNLOAD_SUBDIR =
            Environment.DIRECTORY_DOWNLOADS + File.separator + "WordCraft";

    private static final class Transfer {
        File file;
        FileOutputStream out;
        String name;
        String mime;
        int kind;
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Transfer> transfers = new HashMap<>();

    // Rate limit for error toasts so a repeating JS error cannot spam the screen.
    private String lastErrorText;
    private long lastErrorAt;

    private File pendingFile;
    private String pendingName;
    private String pendingMime;

    BlobBridge(Context context) {
        this.appContext = context.getApplicationContext();
    }

    // ------------------------------------------------------------------ blob -> native

    /** Opens a temp file for an incoming blob. Returns its transfer id, or null on failure. */
    @JavascriptInterface
    public synchronized String begin(String name, long size, String mime, int kind) {
        String id = UUID.randomUUID().toString();
        Transfer transfer = new Transfer();
        transfer.file = new File(appContext.getCacheDir(), "blob-" + id + ".part");
        transfer.name = sanitizeName(name);
        transfer.mime = (mime == null || mime.isEmpty()) ? guessMime(transfer.name) : mime;
        transfer.kind = (kind == KIND_VIEW) ? KIND_VIEW : KIND_DOWNLOAD;
        try {
            transfer.out = new FileOutputStream(transfer.file);
        } catch (IOException e) {
            return null;
        }
        transfers.put(id, transfer);
        return id;
    }

    /** Appends one base64 chunk. Returns false (and the JS side cancels) on a bad write. */
    @JavascriptInterface
    public synchronized boolean append(String id, String base64Chunk) {
        Transfer transfer = transfers.get(id);
        if (transfer == null) {
            return false;
        }
        try {
            transfer.out.write(Base64.decode(base64Chunk, Base64.DEFAULT));
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Closes the transfer and publishes or opens the completed file. */
    @JavascriptInterface
    public synchronized void finish(String id) {
        final Transfer transfer = transfers.remove(id);
        if (transfer == null) {
            return;
        }
        try {
            transfer.out.close();
        } catch (IOException ignored) {
            // The publish/open step reports its own errors.
        }
        if (transfer.kind == KIND_VIEW) {
            mainHandler.post(() -> openExternal(transfer));
        } else {
            publishDownload(transfer);
        }
    }

    /** Aborts a transfer and removes its temp file. */
    @JavascriptInterface
    public synchronized void cancel(String id, String reason) {
        Transfer transfer = transfers.remove(id);
        if (transfer == null) {
            return;
        }
        try {
            transfer.out.close();
        } catch (IOException ignored) {
            // Nothing else to clean up but the file itself.
        }
        //noinspection ResultOfMethodCallIgnored
        transfer.file.delete();
    }

    /** Errors forwarded from web-bridge.js: logcat plus a rate-limited toast for the user. */
    @JavascriptInterface
    public synchronized void logError(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        Log.e("WordCraft", "web: " + message);
        long now = System.currentTimeMillis();
        if (message.equals(lastErrorText) && now - lastErrorAt < 5_000) {
            return;
        }
        lastErrorText = message;
        lastErrorAt = now;
        String clipped = message.length() > 180 ? message.substring(0, 180) + "…" : message;
        mainHandler.post(() -> Toast.makeText(appContext, clipped, Toast.LENGTH_LONG).show());
    }

    private void publishDownload(Transfer transfer) {
        // MediaStore Downloads is preferred (API 29+). On any failure, or on API 26-28 without
        // the storage permission, fall back to app-specific storage rather than losing the file.
        String location = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (publishViaMediaStore(transfer)) {
                location = "Downloads/WordCraft";
            }
        } else if (hasStoragePermission()) {
            File written = publishToPublicDownloads(transfer);
            if (written != null) {
                location = "Downloads/WordCraft";
            }
        }
        if (location == null) {
            File written = publishToAppStorage(transfer);
            if (written != null) {
                location = written.getAbsolutePath();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        transfer.file.delete();
        final String message;
        if (location != null) {
            if ("Downloads/WordCraft".equals(location)) {
                message = appContext.getString(R.string.saved_to_downloads, transfer.name);
            } else {
                message = appContext.getString(R.string.saved_to_folder, transfer.name, location);
            }
        } else {
            message = appContext.getString(R.string.save_failed);
        }
        final String finalMessage = message;
        mainHandler.post(() -> Toast.makeText(appContext, finalMessage, Toast.LENGTH_LONG).show());
    }

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** API 26-28 only: a direct write into the shared Downloads folder (needs the permission). */
    @SuppressWarnings("deprecation")
    private File publishToPublicDownloads(Transfer transfer) {
        File directory = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "WordCraft");
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }
        File target = uniqueFile(new File(directory, transfer.name));
        try (FileOutputStream out = new FileOutputStream(target)) {
            copy(transfer.file, out);
            return target;
        } catch (IOException e) {
            return null;
        }
    }

    /** Fallback writer into app-specific external (or internal) storage, always permitted. */
    private File publishToAppStorage(Transfer transfer) {
        File base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) {
            base = new File(appContext.getFilesDir(), Environment.DIRECTORY_DOWNLOADS);
        }
        File directory = new File(base, "WordCraft");
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }
        File target = uniqueFile(new File(directory, transfer.name));
        try (FileOutputStream out = new FileOutputStream(target)) {
            copy(transfer.file, out);
            return target;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean publishViaMediaStore(Transfer transfer) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, transfer.name);
        values.put(MediaStore.Downloads.MIME_TYPE, transfer.mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, DOWNLOAD_SUBDIR);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = appContext.getContentResolver().insert(collection, values);
        if (item == null) {
            return false;
        }
        try (OutputStream out = appContext.getContentResolver().openOutputStream(item)) {
            if (out == null) {
                appContext.getContentResolver().delete(item, null, null);
                return false;
            }
            copy(transfer.file, out);
        } catch (IOException e) {
            appContext.getContentResolver().delete(item, null, null);
            return false;
        }
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        appContext.getContentResolver().update(item, values, null, null);
        return true;
    }

    private void openExternal(Transfer transfer) {
        // Rename inside the cache dir so the receiving app sees the real filename/extension.
        File viewDir = new File(appContext.getCacheDir(), "view");
        if (!viewDir.exists() && !viewDir.mkdirs()) {
            Toast.makeText(appContext, R.string.open_failed, Toast.LENGTH_LONG).show();
            return;
        }
        File target = uniqueFile(new File(viewDir, transfer.name));
        try (FileOutputStream out = new FileOutputStream(target)) {
            copy(transfer.file, out);
        } catch (IOException e) {
            Toast.makeText(appContext, R.string.open_failed, Toast.LENGTH_LONG).show();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        transfer.file.delete();
        Uri uri = FileProvider.getUriForFile(
                appContext, appContext.getPackageName() + ".fileprovider", target);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, transfer.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            appContext.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(appContext, R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------ VIEW intent -> blob

    /** Stores the bytes of a document launched through a VIEW intent for the page to pull. */
    synchronized void setPendingFile(File file, String name, String mime) {
        clearPendingFileInternal();
        pendingFile = file;
        pendingName = name;
        pendingMime = mime == null ? guessMime(name) : mime;
    }

    /** JSON {@code {name,size,mime}} for the pending VIEW-intent file, or null. */
    @JavascriptInterface
    public synchronized String pendingFileInfo() {
        if (pendingFile == null) {
            return null;
        }
        return "{\"name\":\"" + jsonEscape(pendingName) + "\","
                + "\"size\":" + pendingFile.length() + ","
                + "\"mime\":\"" + jsonEscape(pendingMime) + "\"}";
    }

    /** Base64 chunk of the pending file starting at {@code offset}; null when exhausted. */
    @JavascriptInterface
    public synchronized String readPendingChunk(long offset, int maxBytes) {
        if (pendingFile == null || offset < 0 || maxBytes <= 0) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(pendingFile)) {
            long skipped = 0;
            while (skipped < offset) {
                long s = in.skip(offset - skipped);
                if (s <= 0) {
                    return null;
                }
                skipped += s;
            }
            byte[] buffer = new byte[Math.min(maxBytes, 256 * 1024)];
            int read = in.read(buffer);
            if (read <= 0) {
                return null;
            }
            byte[] exact = new byte[read];
            System.arraycopy(buffer, 0, exact, 0, read);
            return Base64.encodeToString(exact, Base64.NO_WRAP);
        } catch (IOException e) {
            return null;
        }
    }

    /** The page consumed the pending file; drop its local copy. */
    @JavascriptInterface
    public synchronized void clearPendingFile() {
        clearPendingFileInternal();
    }

    private void clearPendingFileInternal() {
        if (pendingFile != null) {
            //noinspection ResultOfMethodCallIgnored
            pendingFile.delete();
            pendingFile = null;
            pendingName = null;
            pendingMime = null;
        }
    }

    // ------------------------------------------------------------------- helpers

    private static void copy(File source, OutputStream out) throws IOException {
        try (FileInputStream in = new FileInputStream(source)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static File uniqueFile(File desired) {
        if (!desired.exists()) {
            return desired;
        }
        String parent = desired.getParent();
        String raw = desired.getName();
        int dot = raw.lastIndexOf('.');
        String stem = dot > 0 ? raw.substring(0, dot) : raw;
        String ext = dot > 0 ? raw.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            File candidate = new File(parent, stem + " (" + i + ")" + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(parent, stem + "-" + System.nanoTime() + ext);
    }

    private static String sanitizeName(String name) {
        if (name == null) {
            return "wordcraft-file";
        }
        String cleaned = name.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        cleaned = cleaned.replaceAll("[\\x00-\\x1f]", "").trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
            return "wordcraft-file";
        }
        return cleaned;
    }

    private static String guessMime(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.US);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime != null ? mime : "application/octet-stream";
    }

    private static String jsonEscape(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }
}
