package com.otpfetch.app;

import android.widget.Button;

/**
 * Button loading-state helper: while an action is processing the button
 * shows a loader text and is disabled so it cannot be clicked twice.
 * Call {@code setBusy} before the background work and {@code setIdle} when
 * the operation completes (success or failure).
 */
public final class UiBusy {
    private UiBusy() {}

    public static void setBusy(Button b, String loadingText) {
        if (b == null) return;
        b.setTag(b.getText() == null ? "" : b.getText().toString());
        b.setEnabled(false);
        b.setAlpha(0.6f);
        if (loadingText != null) b.setText(loadingText);
    }

    public static void setIdle(Button b) {
        if (b == null) return;
        Object tag = b.getTag();
        if (tag instanceof String && !((String) tag).isEmpty()) {
            b.setText((String) tag);
        }
        b.setTag(null);
        b.setEnabled(true);
        b.setAlpha(1f);
    }

    public static void setIdle(Button b, String restoreText) {
        if (b == null) return;
        b.setTag(null);
        if (restoreText != null) b.setText(restoreText);
        b.setEnabled(true);
        b.setAlpha(1f);
    }
}
