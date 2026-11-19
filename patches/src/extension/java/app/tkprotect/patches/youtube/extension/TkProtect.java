package app.tkprotect.patches.youtube.extension;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * TKProtect add-on for YouTube Morphe.
 *
 * <p>Adds a small "TK" button to the player overlay. Tap opens the TKProtect web
 * panel in a WebView; long press opens a dialog where the panel URL can be changed.
 * The URL is stored in SharedPreferences, so changing the server needs no rebuild.
 *
 * <p>Morphe's classes are reached by reflection because they live in the patched
 * app and are not available when this file is compiled into the extension dex.
 */
public final class TkProtect {
    private static final String TAG = "TkProtect";

    private static final String ADD_ON_API = "app.morphe.extension.youtube.addon.AddOnApi";
    private static final String PLAYER_OVERLAY_BUTTON =
            "app.morphe.extension.youtube.videoplayer.PlayerOverlayButton";

    private static final String PREFS_NAME = "tkprotect";
    private static final String KEY_URL = "panel_url";
    private static final String DEFAULT_URL = "http://192.168.1.100/youtube-morphe/index.php";

    private static Method addButtonMethod;

    private TkProtect() {
    }

    /** Injection point: called from AddOnManager.registerAddOns(). */
    public static void register() {
        try {
            Class.forName(ADD_ON_API)
                    .getMethod("addPlayerOverlayButtonsListener", Consumer.class)
                    .invoke(null, new Consumer<View>() {
                        @Override
                        public void accept(View sourceButton) {
                            onPlayerOverlayButtons(sourceButton);
                        }
                    });
        } catch (ReflectiveOperationException | RuntimeException ex) {
            Log.w(TAG, "Could not register TKProtect overlay button listener", ex);
        }
    }

    private static void onPlayerOverlayButtons(View sourceButton) {
        try {
            if (addButtonMethod == null) {
                addButtonMethod = Class.forName(PLAYER_OVERLAY_BUTTON).getMethod(
                        "addButtonWithTextOverlay",
                        View.class, View.OnClickListener.class, View.OnLongClickListener.class);
            }
            Object created = addButtonMethod.invoke(
                    null, sourceButton, CLICK_LISTENER, LONG_CLICK_LISTENER);
            if (created instanceof TextView) {
                ((TextView) created).setText("TK");
                ((TextView) created).setContentDescription("TKProtect panel");
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            Log.w(TAG, "Could not add TKProtect overlay button", ex);
        }
    }

    private static final View.OnClickListener CLICK_LISTENER = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            showPanel(view.getContext());
        }
    };

    private static final View.OnLongClickListener LONG_CLICK_LISTENER = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View view) {
            showSettings(view.getContext());
            return true;
        }
    };

    private static void showPanel(Context context) {
        final Activity activity = unwrapActivity(context);
        if (activity == null || activity.isFinishing()) {
            return;
        }
        try {
            WebView web = new WebView(activity);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            web.setWebViewClient(new WebViewClient());

            FrameLayout container = new FrameLayout(activity);
            container.addView(web, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dp(activity, 620)));

            String url = getUrl(activity);
            web.loadUrl(url);

            new AlertDialog.Builder(activity)
                    .setTitle("TKProtect")
                    .setView(container)
                    .setPositiveButton("Закрыть", null)
                    .show();
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not open TKProtect panel", ex);
        }
    }

    private static void showSettings(Context context) {
        final Activity activity = unwrapActivity(context);
        if (activity == null || activity.isFinishing()) {
            return;
        }
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(getUrl(activity));
        new AlertDialog.Builder(activity)
                .setTitle("TKProtect URL")
                .setMessage("Адрес страницы модуля (index.php), доступный с этого устройства")
                .setView(input)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) {
                        saveUrl(activity, value);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static String getUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_URL, DEFAULT_URL);
    }

    private static void saveUrl(Context context, String url) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_URL, url)
                .apply();
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    private static Activity unwrapActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }
}
