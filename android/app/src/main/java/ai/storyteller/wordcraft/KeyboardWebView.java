package ai.storyteller.wordcraft;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;

/**
 * A WebView that keeps the on-screen keyboard available even when a hardware keyboard is paired.
 *
 * <p>By default Android suppresses the soft keyboard while a physical (e.g. Bluetooth) keyboard
 * is connected, so tapping an editable field inside the WebView only accepted physical key
 * events. The framework calls {@link #onCreateInputConnection} whenever an editable element in
 * the page (egui's hidden IME input included) gains focus, which is the one point where an input
 * connection is guaranteed to exist - the precondition for {@link InputMethodManager} to show
 * the IME. Asking politely (SHOW_IMPLICIT) first and escalating to SHOW_FORCED only when the
 * system refuses covers the hardware-keyboard gate without changing behaviour on ordinary
 * phones. The forced keyboard is released again on focus loss and activity destroy.
 */
public class KeyboardWebView extends WebView {

    public KeyboardWebView(Context context) {
        super(context);
    }

    public KeyboardWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public KeyboardWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection connection = super.onCreateInputConnection(outAttrs);
        if (connection != null) {
            // Post so the InputMethodManager has finished binding this connection first; calling
            // showSoftInput synchronously can be ignored as "no served view" on some devices.
            post(this::requestSoftKeyboard);
        }
        return connection;
    }

    private void requestSoftKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) {
            return;
        }
        if (!imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)) {
            // Hardware keyboard present: the implicit request was gated, force the IME open.
            imm.showSoftInput(this, InputMethodManager.SHOW_FORCED);
        }
    }

    /** Releases a FORCED-shown IME so it can never linger after the view loses focus. */
    public void hideSoftKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getWindowToken() != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        if (!focused) {
            hideSoftKeyboard();
        }
    }
}
