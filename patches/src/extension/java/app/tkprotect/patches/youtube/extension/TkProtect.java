package app.tkprotect.patches.youtube.extension;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Self-contained TKProtect add-on for YouTube Morphe.
 *
 * <p>The whole moderation panel lives inside the app: an HTML/CSS/JS UI is shown in a WebView,
 * and a JavaScript bridge performs TKCore and YouTube Innertube requests on background threads.
 * No external web server is needed — each user enters their own TKCore API key in the settings.
 *
 * <p>Morphe's classes are reached by reflection because they live in the patched app and are
 * not available when this file is compiled into the extension dex.
 */
public final class TkProtect {
    private static final String TAG = "TkProtect";

    private static final String ADD_ON_API = "app.morphe.extension.youtube.addon.AddOnApi";
    private static final String PLAYER_OVERLAY_BUTTON =
            "app.morphe.extension.youtube.videoplayer.PlayerOverlayButton";
    private static final String VIDEO_INFORMATION =
            "app.morphe.extension.youtube.patches.VideoInformation";
    /**
     * Morphe's shared Litho filter extension. It holds the runtime path search tree that every
     * Morphe filter registers its patterns into. TKProtect adds its own pattern to that tree so
     * flagged comments disappear from YouTube's own comment section, not just from the panel.
     */
    private static final String LITHO_FILTER_PATCH =
            "app.morphe.extension.shared.patches.components.LithoFilterPatch";
    /**
     * Path patterns of comment elements. {@code comment_thread.eml} is the element Morphe's
     * keyword filter parses to read a comment's text; {@code |comment.e} is the per-comment
     * fragment Morphe's comment filter uses. Their buffers contain the comment text, which is
     * what we match flagged texts against.
     */
    private static final String[] NATIVE_COMMENT_PATH_PATTERNS = {
            "comment_thread.eml",
            "|comment.e"
    };

    private static final String PREFS_NAME = "tkprotect";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_TK_BASE = "tk_base";
    private static final String KEY_MODE = "tk_mode";
    private static final String KEY_VERSION = "tk_version";
    private static final String KEY_INNERTUBE_KEY = "innertube_key";
    private static final String KEY_CLIENT_VERSION = "client_version";
    private static final String KEY_USER_MODE = "user_mode"; // viewer | streamer
    private static final String KEY_GOOGLE_CLIENT_ID = "google_client_id";
    private static final String KEY_GOOGLE_CLIENT_SECRET = "google_client_secret";
    private static final String KEY_GOOGLE_ACCESS_TOKEN = "google_access_token";
    private static final String KEY_GOOGLE_REFRESH_TOKEN = "google_refresh_token";
    private static final String KEY_GOOGLE_TOKEN_EXPIRY = "google_token_expiry";
    private static final String KEY_STREAMER_LIVE_CHAT_ID = "streamer_live_chat_id";
    private static final String KEY_STREAMER_VIDEO_ID = "streamer_video_id";
    private static final String KEY_STREAMER_ACTION = "streamer_action"; // auto_delete | manual
    private static final String KEY_STREAMER_BAN_MODE = "streamer_ban_mode"; // off | timeout | permanent
    private static final String KEY_STREAMER_BAN_SECONDS = "streamer_ban_seconds";
    private static final String KEY_STREAMER_MANUAL_BIND = "streamer_manual_bind"; // 0 | 1
    private static final String KEY_STREAMER_MANUAL_LIVE_CHAT_ID = "streamer_manual_live_chat_id";
    private static final String KEY_NATIVE_HIDE = "native_hide"; // 0 | 1

    private static final String DEF_TK_BASE =
            "https://thekarly.su/wp-content/plugins/tkcore/easyapi.php";
    private static final String DEF_MODE = "0";
    // TKCore keys without elevated permissions reject version 2.0 with "Insufficient permissions".
    // Version 1.0 is the shape used by the original PHP panel, so it is the safe default.
    private static final String DEF_VERSION = "1.0";
    private static final String DEF_INNERTUBE_KEY =
            "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String DEF_CLIENT_VERSION = "2.20250312.04.00";
    private static final String DEF_USER_MODE = "viewer";
    private static final String YT_SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl";
    private static final String GOOGLE_DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code";
    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String YT_API_BASE = "https://www.googleapis.com/youtube/v3";
    private static final String YOUTUBE_ORIGIN = "https://www.youtube.com";
    private static final String YT_WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
    private static final String[] INITIAL_DATA_MARKERS = {
            "var ytInitialData =", "ytInitialData =",
            "window[\"ytInitialData\"] =", "id=\"yt-initial-data\""
    };

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, String> CHAT_CONTINUATIONS = new HashMap<>();
    private static final Map<String, String> COMMENT_CONTINUATIONS = new HashMap<>();
    private static final Map<String, CommentsState> COMMENT_STATES = new HashMap<>();
    private static final Map<String, Verdict> VERDICTS = new LinkedHashMap<>();
    private static final Map<String, Long> FAILED_UNTIL = new HashMap<>();
    private static final Map<String, Boolean> DELETED_MESSAGES = new LinkedHashMap<>();
    private static final long FAILED_TTL_MS = 30_000L;
    private static final int MAX_VERDICTS = 2000;

    /**
     * Texts that TKCore marked as violations. The Litho callback compares these against the
     * UTF-8 bytes of every comment element to hide it in YouTube's own comment section.
     * Kept in insertion order so the oldest entries are evicted first.
     */
    private static final Object FLAGGED_LOCK = new Object();
    private static final LinkedHashMap<String, Boolean> FLAGGED_TEXTS = new LinkedHashMap<>();
    private static volatile String[] flaggedSnapshot = new String[0];
    private static final int MAX_FLAGGED_TEXTS = 1000;
    /** Shorter texts are too likely to match an unrelated comment by chance. */
    private static final int MIN_NATIVE_HIDE_LENGTH = 6;

    private static volatile boolean nativeHideEnabled = true;
    private static volatile boolean nativeFilterInstalled;
    private static volatile Field lithoBufferField;

    private static Method addButtonMethod;
    private static WebView activeWeb;
    private static volatile String currentVideoId = "";
    private static Context appContext;
    private static volatile String workingVersion = "";

    private TkProtect() {
    }

    /** Injection point: called from AddOnManager.registerAddOns(). */
    public static void register() {
        registerVideoIdListener();
        registerOverlayButtonListener();
        installNativeCommentFilter();
    }

    /**
     * Subscribes to the current video id published by Morphe's videoIdPatch.
     * This is what makes TKProtect work for a normal viewer: the video being watched is detected
     * automatically, so nobody has to copy an 11-character id by hand.
     */
    private static void registerVideoIdListener() {
        try {
            Class.forName(ADD_ON_API)
                    .getMethod("addVideoIdListener", Consumer.class)
                    .invoke(null, new Consumer<String>() {
                        @Override
                        public void accept(String videoId) {
                            String id = videoId == null ? "" : videoId;
                            if (!id.equals(currentVideoId)) {
                                currentVideoId = id;
                                clearFlaggedTexts();
                                notifyVideoChanged(id);
                            }
                        }
                    });
        } catch (ReflectiveOperationException | RuntimeException ex) {
            Log.w(TAG, "Could not register video id listener", ex);
        }
    }

    private static void registerOverlayButtonListener() {
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

    // ---------------------------------------------------------------------------------------
    // Native comment hiding (Morphe Litho filter)
    // ---------------------------------------------------------------------------------------

    /**
     * Hides flagged comments in YouTube's own comment section by registering a pattern and
     * callback into Morphe's Litho path search tree. Everything is done by reflection because
     * the Morphe classes only exist in the patched app, not when this file is compiled.
     *
     * <p>If the class is missing (the user did not apply a patch that includes the Litho filter)
     * or anything else fails, this logs and returns: TKProtect keeps working with panel-only
     * hiding, and the app never crashes.
     */
    private static void installNativeCommentFilter() {
        if (nativeFilterInstalled) {
            return;
        }
        try {
            Class<?> patchClass = Class.forName(LITHO_FILTER_PATCH);

            Field treeField = null;
            try {
                treeField = patchClass.getDeclaredField("pathSearchTree");
            } catch (NoSuchFieldException ignored) {
                // Fall back to any static trie search whose name mentions the path.
                for (Field candidate : patchClass.getDeclaredFields()) {
                    if (candidate.getType().getName().endsWith("StringTrieSearch")
                            && candidate.getName().toLowerCase(Locale.ROOT).contains("path")) {
                        treeField = candidate;
                        break;
                    }
                }
            }
            if (treeField == null) {
                Log.w(TAG, "Native comment filter: path search tree not found");
                return;
            }
            treeField.setAccessible(true);
            final Object tree = treeField.get(null);
            if (tree == null) {
                Log.w(TAG, "Native comment filter: path search tree is null");
                return;
            }

            // The callback interface is a nested generic interface, so take it from the
            // addPattern method instead of naming it (keeps working across obfuscation-free
            // renames of the outer class).
            Method addPattern = null;
            Class<?> callbackInterface = null;
            for (Method method : tree.getClass().getMethods()) {
                if ("addPattern".equals(method.getName()) && method.getParameterCount() == 2) {
                    Class<?> candidate = method.getParameterTypes()[1];
                    if (candidate.isInterface()) {
                        addPattern = method;
                        callbackInterface = candidate;
                        break;
                    }
                }
            }
            if (addPattern == null || callbackInterface == null) {
                Log.w(TAG, "Native comment filter: addPattern not found");
                return;
            }

            Object callback = Proxy.newProxyInstance(
                    callbackInterface.getClassLoader(),
                    new Class<?>[]{callbackInterface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if (method.getDeclaringClass() == Object.class) {
                                switch (method.getName()) {
                                    case "hashCode":
                                        return System.identityHashCode(proxy);
                                    case "equals":
                                        return proxy == (args == null || args.length == 0 ? null : args[0]);
                                    case "toString":
                                        return "TkProtectLithoCallback";
                                    default:
                                        return null;
                                }
                            }
                            if ("patternMatched".equals(method.getName())
                                    && args != null && args.length >= 4) {
                                try {
                                    return shouldHideLithoElement(args[3]);
                                } catch (Throwable ex) {
                                    // Never break the layout thread: fail open.
                                    return Boolean.FALSE;
                                }
                            }
                            return Boolean.FALSE;
                        }
                    });

            for (String pattern : NATIVE_COMMENT_PATH_PATTERNS) {
                addPattern.invoke(tree, pattern, callback);
            }
            nativeFilterInstalled = true;
            Log.i(TAG, "Native comment filter installed");
        } catch (Throwable ex) {
            Log.w(TAG, "Could not install native comment filter (panel-only hiding stays active)", ex);
        }
    }

    /**
     * @return True if the element must be hidden. {@code callbackParameter} is Morphe's
     *         {@code LithoFilterParameters}, whose {@code buffer} holds the element proto bytes.
     */
    private static Boolean shouldHideLithoElement(Object callbackParameter) {
        if (!nativeHideEnabled || callbackParameter == null) {
            return Boolean.FALSE;
        }
        final String[] flagged = flaggedSnapshot;
        if (flagged.length == 0) {
            return Boolean.FALSE;
        }
        byte[] buffer = lithoBufferOf(callbackParameter);
        if (buffer == null || buffer.length == 0) {
            return Boolean.FALSE;
        }
        // The comment text is stored as a UTF-8 length-delimited string inside the proto, so it
        // appears contiguously and a plain contains() is enough.
        String ascii = new String(buffer, StandardCharsets.UTF_8);
        for (String text : flagged) {
            if (ascii.contains(text)) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    private static byte[] lithoBufferOf(Object callbackParameter) {
        try {
            Field field = lithoBufferField;
            if (field == null) {
                Class<?> clazz = callbackParameter.getClass();
                try {
                    field = clazz.getDeclaredField("buffer");
                } catch (NoSuchFieldException ignored) {
                    for (Field candidate : clazz.getDeclaredFields()) {
                        if (candidate.getType() == byte[].class) {
                            field = candidate;
                            break;
                        }
                    }
                }
                if (field == null) {
                    return null;
                }
                field.setAccessible(true);
                lithoBufferField = field;
            }
            Object value = field.get(callbackParameter);
            return value instanceof byte[] ? (byte[]) value : null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    /** Remembers a violating text so the Litho callback can hide the matching comment. */
    private static void rememberFlaggedText(String text) {
        if (text == null) {
            return;
        }
        String value = text.trim();
        if (value.length() < MIN_NATIVE_HIDE_LENGTH) {
            return;
        }
        synchronized (FLAGGED_LOCK) {
            if (FLAGGED_TEXTS.containsKey(value)) {
                return;
            }
            FLAGGED_TEXTS.put(value, Boolean.TRUE);
            while (FLAGGED_TEXTS.size() > MAX_FLAGGED_TEXTS) {
                Iterator<String> it = FLAGGED_TEXTS.keySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                it.next();
                it.remove();
            }
            flaggedSnapshot = FLAGGED_TEXTS.keySet().toArray(new String[0]);
        }
    }

    /** Drops the flagged texts when the video changes, so they never leak into another video. */
    private static void clearFlaggedTexts() {
        synchronized (FLAGGED_LOCK) {
            if (FLAGGED_TEXTS.isEmpty()) {
                return;
            }
            FLAGGED_TEXTS.clear();
            flaggedSnapshot = new String[0];
        }
    }

    private static void reloadNativeHidePref(Context context) {
        Context ctx = context != null ? context : appContext;
        if (ctx == null) {
            return;
        }
        try {
            nativeHideEnabled = !"0".equals(pref(ctx, KEY_NATIVE_HIDE, "1"));
        } catch (RuntimeException ex) {
            nativeHideEnabled = true;
        }
    }

    private static void notifyVideoChanged(final String videoId) {
        final WebView web = activeWeb;
        if (web == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("video", videoId);
                    callJs(web, "tkpOnVideo", payload);
                } catch (Exception ignored) {
                    // ignored
                }
            }
        });
    }

    /**
     * Returns the id of the video currently open in the player.
     * The listener is the primary source; VideoInformation is a fallback for the case where the
     * panel is opened before the first id event arrives.
     */
    private static String queryVideoId() {
        String id = currentVideoId;
        if (!isValidVideoId(id)) {
            id = "";
        }
        if (!id.isEmpty()) {
            return id;
        }
        try {
            Class<?> info = Class.forName(VIDEO_INFORMATION);
            String[] names = {"getStartingOrPlayingVideoId", "getVideoId"};
            for (String name : names) {
                try {
                    Object value = info.getMethod(name).invoke(null);
                    if (value instanceof String && isValidVideoId((String) value)) {
                        currentVideoId = (String) value;
                        return currentVideoId;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // try the next getter
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            Log.w(TAG, "Could not query current video id", ex);
        }
        return "";
    }

    private static boolean isValidVideoId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{11}");
    }

    private static void onPlayerOverlayButtons(View sourceButton) {
        try {
            Context buttonContext = sourceButton.getContext();
            if (buttonContext != null && buttonContext.getApplicationContext() != null) {
                appContext = buttonContext.getApplicationContext();
                reloadNativeHidePref(appContext);
            }
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

    // ---------------------------------------------------------------------------------------
    // Panel UI
    // ---------------------------------------------------------------------------------------

    private static void showPanel(Context context) {
        final Activity activity = unwrapActivity(context);
        if (activity == null || activity.isFinishing()) {
            return;
        }
        try {
            final WebView web = new WebView(activity);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            web.setFocusable(true);
            web.setFocusableInTouchMode(true);
            web.setBackgroundColor(0xFF101317);
            web.addJavascriptInterface(new Bridge(activity, web), "TK");
            activeWeb = web;

            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("TKProtect")
                    .setView(web)
                    .setPositiveButton("Закрыть", null)
                    .setOnDismissListener(d -> {
                        if (activeWeb == web) {
                            activeWeb = null;
                        }
                        web.destroy();
                    })
                    .create();
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                dialog.getWindow().setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            web.requestFocus();

            web.loadDataWithBaseURL(null, panelHtml(), "text/html", "UTF-8", null);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not open TKProtect panel", ex);
        }
    }

    private static void showSettings(Context context) {
        final Activity activity = unwrapActivity(context);
        if (activity == null || activity.isFinishing()) {
            return;
        }
        try {
            ScrollView scroll = new ScrollView(activity);
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(activity, 20);
            box.setPadding(pad, dp(activity, 8), pad, 0);

            final Spinner userModeInput = modeField(activity, box, pref(activity, KEY_USER_MODE, DEF_USER_MODE));

            final EditText keyInput = field(activity, box, "TKCore API key", pref(activity, KEY_API_KEY, ""));
            final EditText modeInput = field(activity, box, "Режим (phrase2)", pref(activity, KEY_MODE, DEF_MODE));
            final Spinner versionInput = versionField(activity, box, pref(activity, KEY_VERSION, DEF_VERSION));

            final CheckBox nativeHideInput = new CheckBox(activity);
            nativeHideInput.setText("Скрывать помеченные комментарии в самом YouTube");
            nativeHideInput.setChecked(!"0".equals(pref(activity, KEY_NATIVE_HIDE, "1")));
            nativeHideInput.setPadding(0, dp(activity, 10), 0, 0);
            box.addView(nativeHideInput);

            TextView hint = new TextView(activity);
            String savedKey = pref(activity, KEY_API_KEY, "");
            hint.setText(savedKey.isEmpty()
                    ? "КЛЮЧ НЕ СОХРАНЁН. Вставьте ваш TKCore API key в поле выше и нажмите «Сохранить»."
                    : "Ключ сохранён: " + maskKey(savedKey));
            hint.setTextColor(savedKey.isEmpty() ? 0xFFFF6B6B : 0xFF7BD88F);
            hint.setPadding(0, dp(activity, 8), 0, 0);
            box.addView(hint);

            TextView note = new TextView(activity);
            note.setText("Ключ из web/config.php патчем НЕ используется — скопируйте тот же ключ сюда. "
                    + "Обычно подходит 1.0. «Авто» пробует обе версии.");
            note.setPadding(0, dp(activity, 6), 0, 0);
            box.addView(note);

            Button testButton = new Button(activity);
            testButton.setText("Проверить ключ TKCore");
            final TextView testResult = new TextView(activity);
            testResult.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
            testButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    final String key = keyInput.getText().toString().trim();
                    String base = pref(activity, KEY_TK_BASE, DEF_TK_BASE);
                    if (base == null || base.trim().isEmpty()) {
                        base = DEF_TK_BASE;
                    }
                    final String resolvedBase = base;
                    final String mode = modeInput.getText().toString().trim();
                    final String version = selectedVersion(versionInput);
                    testResult.setText("Запрос к TKCore...");
                    EXECUTOR.execute(new Runnable() {
                        @Override
                        public void run() {
                            final String result = testKey(key, resolvedBase, mode, version);
                            MAIN.post(new Runnable() {
                                @Override
                                public void run() {
                                    testResult.setText(result);
                                }
                            });
                        }
                    });
                }
            });
            box.addView(testButton);
            box.addView(testResult);

            // --- Streamer section ---
            final LinearLayout streamerBox = new LinearLayout(activity);
            streamerBox.setOrientation(LinearLayout.VERTICAL);
            streamerBox.setPadding(0, dp(activity, 12), 0, 0);
            TextView streamerTitle = new TextView(activity);
            streamerTitle.setText("Режим стримера (YouTube API)");
            streamerTitle.setPadding(0, dp(activity, 8), 0, 0);
            streamerBox.addView(streamerTitle);

            TextView streamerInfo = new TextView(activity);
            streamerInfo.setText("Для авто-удаления нарушений из live-чата нужен OAuth-доступ: "
                    + "создайте проект в Google Cloud Console, включите YouTube Data API v3, "
                    + "создайте OAuth-клиент типа «TVs and Limited Input devices» и вставьте Client ID. "
                    + "Токены хранятся только на этом устройстве.");
            streamerInfo.setPadding(0, dp(activity, 4), 0, 0);
            streamerBox.addView(streamerInfo);

            final EditText googleClientIdInput = field(activity, streamerBox, "Google Client ID",
                    pref(activity, KEY_GOOGLE_CLIENT_ID, ""));
            final EditText googleClientSecretInput = field(activity, streamerBox,
                    "Google Client Secret (если есть)", pref(activity, KEY_GOOGLE_CLIENT_SECRET, ""));
            final TextView googleStatus = new TextView(activity);
            googleStatus.setPadding(0, dp(activity, 6), 0, dp(activity, 4));
            updateGoogleStatus(activity, googleStatus);
            streamerBox.addView(googleStatus);

            Button googleLoginButton = new Button(activity);
            googleLoginButton.setText("Войти через Google (код)");
            googleLoginButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    savePref(activity, KEY_GOOGLE_CLIENT_ID, googleClientIdInput.getText().toString().trim());
                    savePref(activity, KEY_GOOGLE_CLIENT_SECRET, googleClientSecretInput.getText().toString().trim());
                    startDeviceLogin(activity,
                            googleClientIdInput.getText().toString().trim(),
                            googleClientSecretInput.getText().toString().trim(),
                            googleStatus);
                }
            });
            streamerBox.addView(googleLoginButton);

            Button googleLogoutButton = new Button(activity);
            googleLogoutButton.setText("Выйти из Google (удалить токены)");
            googleLogoutButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    clearGoogleTokens(activity);
                    savePref(activity, KEY_STREAMER_LIVE_CHAT_ID, "");
                    savePref(activity, KEY_STREAMER_VIDEO_ID, "");
                    updateGoogleStatus(activity, googleStatus);
                }
            });
            streamerBox.addView(googleLogoutButton);

            Button findStreamButton = new Button(activity);
            findStreamButton.setText("Найти мой активный стрим");
            final TextView streamStatus = new TextView(activity);
            streamStatus.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
            String boundChat = pref(activity, KEY_STREAMER_LIVE_CHAT_ID, "");
            String boundVideo = pref(activity, KEY_STREAMER_VIDEO_ID, "");
            streamStatus.setText(boundChat.isEmpty() ? "Активный стрим не привязан"
                    : "Привязан liveChatId: " + boundChat + (boundVideo.isEmpty() ? "" : "\nvideoId: " + boundVideo));
            findStreamButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    streamStatus.setText("Поиск активного стрима...");
                    EXECUTOR.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                final String liveChatId = findActiveLiveChatId(activity);
                                MAIN.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (liveChatId.isEmpty()) {
                                            streamStatus.setText("Активных стримов не найдено. "
                                                    + "Запустите эфир и попробуйте снова.");
                                        } else {
                                            String vid = pref(activity, KEY_STREAMER_VIDEO_ID, "");
                                            streamStatus.setText("Привязан liveChatId: " + liveChatId
                                                    + (vid.isEmpty() ? "" : "\nvideoId: " + vid));
                                        }
                                        updateGoogleStatus(activity, googleStatus);
                                    }
                                });
                            } catch (final Exception ex) {
                                Log.w(TAG, "Find active stream failed", ex);
                                MAIN.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        streamStatus.setText("Ошибка: " + ex.getMessage());
                                    }
                                });
                            }
                        }
                    });
                }
            });
            streamerBox.addView(findStreamButton);
            streamerBox.addView(streamStatus);

            final Spinner streamerActionInput = labeledSpinner(activity, streamerBox, "Действие при нарушении",
                    new String[]{"Авто-удалять (рекомендуется)", "Только помечать (ручное подтверждение)"},
                    "manual".equals(pref(activity, KEY_STREAMER_ACTION, "auto_delete")) ? 1 : 0);
            final Spinner streamerBanInput = labeledSpinner(activity, streamerBox, "Бан/таймаут нарушителей",
                    new String[]{"Выкл (по умолчанию)", "Таймаут", "Бан навсегда"},
                    banModeIndex(pref(activity, KEY_STREAMER_BAN_MODE, "off")));
            final EditText streamerBanSecondsInput = field(activity, streamerBox,
                    "Длительность таймаута (сек)", pref(activity, KEY_STREAMER_BAN_SECONDS, "300"));

            final Spinner manualBindInput = labeledSpinner(activity, streamerBox, "Привязка стрима",
                    new String[]{"Авто-поиск активного стрима", "Вручную (liveChatId)"},
                    "1".equals(pref(activity, KEY_STREAMER_MANUAL_BIND, "0")) ? 1 : 0);
            final EditText manualLiveChatIdInput = field(activity, streamerBox,
                    "liveChatId (вручную)", pref(activity, KEY_STREAMER_MANUAL_LIVE_CHAT_ID, ""));
            TextView manualBindNote = new TextView(activity);
            manualBindNote.setText("Ручная привязка по умолчанию выключена. Используйте, если авто-поиск не находит стрим. "
                    + "Нужен liveChatId активного эфира (можно получить из API/инструментов стримера).");
            manualBindNote.setPadding(0, dp(activity, 4), 0, 0);
            streamerBox.addView(manualBindNote);

            box.addView(streamerBox);

            final EditText innertubeInput =
                    field(activity, box, "YouTube API key (Innertube)", pref(activity, KEY_INNERTUBE_KEY, DEF_INNERTUBE_KEY));
            final EditText clientInput =
                    field(activity, box, "Версия клиента YouTube", pref(activity, KEY_CLIENT_VERSION, DEF_CLIENT_VERSION));

            scroll.addView(box);

            final Runnable toggleStreamer = new Runnable() {
                @Override
                public void run() {
                    streamerBox.setVisibility(selectedUserMode(userModeInput).equals("streamer")
                            ? View.VISIBLE : View.GONE);
                }
            };
            toggleStreamer.run();
            userModeInput.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    toggleStreamer.run();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });

            new AlertDialog.Builder(activity)
                    .setTitle("TKProtect — настройки")
                    .setView(scroll)
                    .setPositiveButton("Сохранить", (dialog, which) -> {
                        savePref(activity, KEY_USER_MODE, selectedUserMode(userModeInput));
                        savePref(activity, KEY_API_KEY, keyInput.getText().toString().trim());
                        savePref(activity, KEY_MODE, modeInput.getText().toString().trim());
                        savePref(activity, KEY_VERSION, selectedVersion(versionInput));
                        savePref(activity, KEY_NATIVE_HIDE, nativeHideInput.isChecked() ? "1" : "0");
                        reloadNativeHidePref(activity);
                        savePref(activity, KEY_GOOGLE_CLIENT_ID, googleClientIdInput.getText().toString().trim());
                        savePref(activity, KEY_GOOGLE_CLIENT_SECRET, googleClientSecretInput.getText().toString().trim());
                        savePref(activity, KEY_STREAMER_ACTION, streamerActionInput.getSelectedItemPosition() == 1 ? "manual" : "auto_delete");
                        savePref(activity, KEY_STREAMER_BAN_MODE, banModeValue(streamerBanInput.getSelectedItemPosition()));
                        savePref(activity, KEY_STREAMER_BAN_SECONDS, streamerBanSecondsInput.getText().toString().trim().isEmpty()
                                ? "300" : streamerBanSecondsInput.getText().toString().trim());
                        savePref(activity, KEY_STREAMER_MANUAL_BIND, manualBindInput.getSelectedItemPosition() == 1 ? "1" : "0");
                        savePref(activity, KEY_STREAMER_MANUAL_LIVE_CHAT_ID, manualLiveChatIdInput.getText().toString().trim());
                        savePref(activity, KEY_INNERTUBE_KEY, innertubeInput.getText().toString().trim());
                        savePref(activity, KEY_CLIENT_VERSION, clientInput.getText().toString().trim());
                        workingVersion = "";
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not open TKProtect settings", ex);
        }
    }

    private static EditText field(Context context, LinearLayout parent, String label, String value) {
        TextView text = new TextView(context);
        text.setText(label);
        text.setPadding(0, dp(context, 10), 0, 0);
        parent.addView(text);
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setText(value);
        parent.addView(input);
        return input;
    }

    private static Spinner versionField(Context context, LinearLayout parent, String value) {
        TextView text = new TextView(context);
        text.setText("Версия API");
        text.setPadding(0, dp(context, 10), 0, 0);
        parent.addView(text);
        Spinner spinner = new Spinner(context);
        String[] labels = {"1.0 (рекомендуется)", "2.0", "Авто (подбор)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int selected = 0;
        if ("2.0".equals(value)) {
            selected = 1;
        } else if ("auto".equalsIgnoreCase(value)) {
            selected = 2;
        }
        spinner.setSelection(selected);
        parent.addView(spinner);
        return spinner;
    }

    private static String selectedVersion(Spinner spinner) {
        int position = spinner.getSelectedItemPosition();
        if (position == 1) {
            return "2.0";
        }
        if (position == 2) {
            return "auto";
        }
        return "1.0";
    }

    private static Spinner modeField(Context context, LinearLayout parent, String value) {
        TextView text = new TextView(context);
        text.setText("Режим");
        text.setPadding(0, dp(context, 10), 0, 0);
        parent.addView(text);
        Spinner spinner = new Spinner(context);
        String[] labels = {"Зритель (локальное скрытие)", "Стример (авто-удаление через YouTube API)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection("streamer".equals(value) ? 1 : 0);
        parent.addView(spinner);
        return spinner;
    }

    private static String selectedUserMode(Spinner spinner) {
        return spinner.getSelectedItemPosition() == 1 ? "streamer" : "viewer";
    }

    private static void updateGoogleStatus(Context context, TextView statusView) {
        if (!hasGoogleTokens(context)) {
            statusView.setText("Google: не авторизован");
            statusView.setTextColor(0xFFFF6B6B);
            return;
        }
        String chatId = effectiveLiveChatId(context);
        String base = "Google: авторизован";
        if (!chatId.isEmpty()) {
            base += " · liveChatId привязан";
        }
        statusView.setText(base);
        statusView.setTextColor(0xFF7BD88F);
    }

    private static Spinner labeledSpinner(Context context, LinearLayout parent, String label,
                                          String[] labels, int selectedIndex) {
        TextView text = new TextView(context);
        text.setText(label);
        text.setPadding(0, dp(context, 10), 0, 0);
        parent.addView(text);
        Spinner spinner = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        if (selectedIndex < 0) selectedIndex = 0;
        if (selectedIndex >= labels.length) selectedIndex = labels.length - 1;
        spinner.setSelection(selectedIndex);
        parent.addView(spinner);
        return spinner;
    }

    private static int banModeIndex(String value) {
        if ("timeout".equals(value)) return 1;
        if ("permanent".equals(value)) return 2;
        return 0;
    }

    private static String banModeValue(int index) {
        if (index == 1) return "timeout";
        if (index == 2) return "permanent";
        return "off";
    }

    // ---------------------------------------------------------------------------------------
    // JavaScript bridge
    // ---------------------------------------------------------------------------------------

    private static final class Bridge {
        private final Context context;
        private final WebView web;

        Bridge(Context context, WebView web) {
            this.context = context;
            this.web = web;
        }

        @JavascriptInterface
        public void bootstrap() {
            run("bootstrap", new JSONObject(), new Job() {
                @Override
                public JSONObject execute(JSONObject args) throws Exception {
                    JSONObject out = new JSONObject();
                    putStatus(out);
                    return out;
                }
            });
        }

        private void putStatus(JSONObject out) throws Exception {
            String key = pref(context, KEY_API_KEY, "");
            out.put("ready", !key.isEmpty());
            out.put("keyMask", maskKey(key));
            out.put("keyLen", key.length());
            out.put("apiVersion", activeVersion(context));
            out.put("userMode", pref(context, KEY_USER_MODE, DEF_USER_MODE));
            out.put("streamerLinked", hasGoogleTokens(context));
            out.put("liveChatId", effectiveLiveChatId(context));
            out.put("streamerAction", pref(context, KEY_STREAMER_ACTION, "auto_delete"));
            out.put("banMode", streamerBanMode(context));
        }

        /** Returns the video that is currently playing, so the panel can bind automatically. */
        @JavascriptInterface
        public String getVideoId() {
            return queryVideoId();
        }

        /** Lets the user override the detected id from the panel. */
        @JavascriptInterface
        public void setVideoId(String id) {
            if (isValidVideoId(id)) {
                currentVideoId = id;
            }
        }

        @JavascriptInterface
        public void check(final String text) {
            run("check", new JSONObject(), new Job() {
                @Override
                public JSONObject execute(JSONObject args) throws Exception {
                    return verdictJson(checkText(context, text));
                }
            });
        }

        @JavascriptInterface
        public void chat(final String videoId, final boolean reset) {
            run("chat", new JSONObject(), new Job() {
                @Override
                public JSONObject execute(JSONObject args) throws Exception {
                    if (!videoId.matches("[A-Za-z0-9_-]{11}")) {
                        JSONObject out = new JSONObject();
                        out.put("error", "invalid_video_id");
                        return out;
                    }
                    if (reset) {
                        CHAT_CONTINUATIONS.remove(videoId);
                    }
                    String continuation = CHAT_CONTINUATIONS.get(videoId);
                    if (continuation == null) {
                        continuation = "";
                    }
                    JSONObject page = chatPage(context, videoId, continuation);
                    String next = page.optString("continuation", "");
                    if (!next.isEmpty() && !next.equals(continuation)) {
                        CHAT_CONTINUATIONS.put(videoId, next);
                    }
                    JSONArray messages = page.optJSONArray("messages");
                    JSONArray outMessages = new JSONArray();
                    for (int i = 0; i < messages.length(); i++) {
                        JSONObject item = messages.getJSONObject(i);
                        String text = item.optString("text", "");
                        Verdict verdict = verdictForText(context, text);
                        item.put("verdict", verdictJson(verdict));
                        item.put("hidden", verdict.bad);
                        maybeAutoModerateChatMessage(context, item, verdict);
                        outMessages.put(item);
                    }
                    JSONObject out = new JSONObject();
                    out.put("video", videoInfo(context, videoId));
                    out.put("messages", outMessages);
                    out.put("error", page.optString("error", ""));
                    putStatus(out);
                    return out;
                }
            });
        }

        @JavascriptInterface
        public void comments(final String videoId, final boolean reset) {
            run("comments", new JSONObject(), new Job() {
                @Override
                public JSONObject execute(JSONObject args) throws Exception {
                    if (!videoId.matches("[A-Za-z0-9_-]{11}")) {
                        JSONObject out = new JSONObject();
                        out.put("error", "invalid_video_id");
                        return out;
                    }
                    if (reset) {
                        COMMENT_CONTINUATIONS.remove(videoId);
                        COMMENT_STATES.remove(videoId);
                    }
                    String continuation = COMMENT_CONTINUATIONS.get(videoId);
                    if (continuation == null) {
                        continuation = "";
                    }
                    JSONObject page = commentsPage(context, videoId, continuation);
                    String next = page.optString("continuation", "");
                    JSONArray source = page.optJSONArray("comments");
                    // YouTube often returns only a continuation token in the first "next" reply;
                    // follow it immediately so the comments tab is not empty on first open.
                    if ((source == null || source.length() == 0) && !next.isEmpty()) {
                        JSONObject follow = commentsPage(context, videoId, next);
                        JSONArray followComments = follow.optJSONArray("comments");
                        if (followComments != null && followComments.length() > 0) {
                            page = follow;
                            source = followComments;
                            String followNext = follow.optString("continuation", "");
                            if (!followNext.isEmpty()) {
                                next = followNext;
                            }
                        }
                    }
                    if (!next.isEmpty() && !next.equals(continuation)) {
                        COMMENT_CONTINUATIONS.put(videoId, next);
                    }
                    if (source == null) {
                        source = new JSONArray();
                    }
                    JSONArray outComments = new JSONArray();
                    for (int i = 0; i < source.length(); i++) {
                        JSONObject item = source.getJSONObject(i);
                        String text = item.optString("text", "");
                        Verdict verdict = verdictForText(context, text);
                        item.put("verdict", verdictJson(verdict));
                        item.put("hidden", verdict.bad);
                        outComments.put(item);
                    }
                    JSONObject out = new JSONObject();
                    out.put("video", videoInfo(context, videoId));
                    out.put("comments", outComments);
                    out.put("error", page.optString("error", ""));
                    out.put("hasContinuation", !next.isEmpty());
                    putStatus(out);
                    return out;
                }
            });
        }

        @JavascriptInterface
        public void moderate(final String messageId, final String channelId) {
            run("moderate", new JSONObject(), new Job() {
                @Override
                public JSONObject execute(JSONObject args) throws Exception {
                    JSONObject result = performStreamerModeration(context, null, messageId, channelId, false);
                    result.put("id", messageId == null ? "" : messageId);
                    return result;
                }
            });
        }

        private void run(final String callback, JSONObject args, final Job job) {
            final JSONObject fArgs = args;
            EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        final JSONObject result = job.execute(fArgs);
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                call("tkpOn" + cap(callback), result);
                            }
                        });
                    } catch (final Exception ex) {
                        Log.w(TAG, "Bridge job failed: " + callback, ex);
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                JSONObject out = new JSONObject();
                                try {
                                    out.put("error", String.valueOf(ex.getMessage()));
                                } catch (Exception ignored) {
                                    // ignored
                                }
                                call("tkpOn" + cap(callback), out);
                            }
                        });
                    }
                }
            });
        }

        private void call(String name, JSONObject payload) {
            if (activeWeb == web) {
                callJs(web, name, payload);
            }
        }
    }

    private interface Job {
        JSONObject execute(JSONObject args) throws Exception;
    }

    private static String cap(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String maskKey(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        if (key.length() <= 4) {
            return "****";
        }
        return "****" + key.substring(key.length() - 4);
    }

    private static void callJs(WebView web, String name, JSONObject payload) {
        try {
            web.evaluateJavascript(
                    "if(window." + name + ")window." + name + "(JSON.parse(" + JSONObject.quote(payload.toString()) + "));",
                    null);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not deliver JS callback", ex);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Verdicts (TKCore)
    // ---------------------------------------------------------------------------------------

    private static final class Verdict {
        final String type;
        final String label;
        final boolean bad;

        Verdict(String type, String label, boolean bad) {
            this.type = type;
            this.label = label;
            this.bad = bad;
        }
    }

    private static JSONObject verdictJson(Verdict verdict) throws Exception {
        JSONObject out = new JSONObject();
        out.put("type", verdict.type);
        out.put("label", verdict.label);
        out.put("bad", verdict.bad);
        return out;
    }

    private static String verdictLabel(String type) {
        switch (type) {
            case "allowed":
                return "Нормальное сообщение";
            case "whitelist":
                return "Ссылка в белом списке";
            case "scanner":
                return "Обнаружена ссылка";
            case "obscene":
                return "Нецензурная лексика";
            case "phishing":
                return "Фишинговая ссылка";
            case "caps_flood":
            case "caps":
            case "flood":
            case "spam":
                return "Капс / флуд";
            case "scam":
                return "Скам";
            case "blacklist":
                return "Ссылка в чёрном списке";
            case "pii":
                return "Персональные данные";
            case "error":
                return "Ошибка проверки";
            case "pending":
                return "Ещё не проверено";
            case "unknown":
                return "Неизвестный ответ (не скрываем)";
            default:
                return "Неизвестный ответ: " + type;
        }
    }

    private static boolean isBadVerdict(String type) {
        return !("allowed".equals(type) || "whitelist".equals(type) || "scanner".equals(type)
                || "error".equals(type) || "unknown".equals(type) || "pending".equals(type));
    }

    private static Verdict normalizeVerdict(String raw) {
        String type = raw == null ? "unknown" : raw.trim();
        // A malformed reply must never turn into a "bad" verdict: that would hide a normal
        // comment and print NaN/undefined in the panel. Fail open instead.
        if (type.isEmpty() || "NaN".equalsIgnoreCase(type) || "null".equalsIgnoreCase(type)
                || "undefined".equalsIgnoreCase(type) || type.contains("NaN")) {
            type = "unknown";
        }
        // Numeric verdict codes (may arrive as "3" or "3.0").
        String numeric = type;
        if (numeric.endsWith(".0")) {
            numeric = numeric.substring(0, numeric.length() - 2);
        }
        switch (numeric) {
            case "-1":
                type = "error";
                break;
            case "0":
                type = "allowed";
                break;
            case "1":
                type = "obscene";
                break;
            case "2":
                type = "phishing";
                break;
            case "3":
                type = "caps_flood";
                break;
            case "4":
                type = "pii";
                break;
            case "5":
                type = "scam";
                break;
            default:
                // Keep named types as-is (e.g. blacklist, whitelist, scanner, pending, caps_flood).
                type = type;
                break;
        }
        return new Verdict(type, verdictLabel(type), isBadVerdict(type));
    }

    private static String md5(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return out.toString();
    }

    private static Verdict verdictForText(Context context, String text) {
        if (text == null || text.trim().isEmpty()) {
            return new Verdict("allowed", verdictLabel("allowed"), false);
        }
        String key = null;
        try {
            key = md5(text);
            Verdict cached = VERDICTS.get(key);
            if (cached != null) {
                if (cached.bad) {
                    rememberFlaggedText(text);
                }
                return cached;
            }
            // Network/parse failures are fail-open (not hidden). Remember them briefly so the
            // same message is not re-checked on every 5s poll while the request is unstable.
            Long until = FAILED_UNTIL.get(key);
            if (until != null) {
                if (until > System.currentTimeMillis()) {
                    return new Verdict("error", "Ошибка проверки (ожидание повтора)", false);
                }
                FAILED_UNTIL.remove(key);
            }
        } catch (Exception ignored) {
            // ignored
        }
        Verdict verdict = checkText(context, text);
        if (key != null && verdict != null) {
            if (!"error".equals(verdict.type)) {
                rememberVerdict(key, verdict);
                FAILED_UNTIL.remove(key);
            } else {
                FAILED_UNTIL.put(key, System.currentTimeMillis() + FAILED_TTL_MS);
                if (FAILED_UNTIL.size() > 400) {
                    FAILED_UNTIL.clear();
                }
            }
        }
        if (verdict != null && verdict.bad) {
            rememberFlaggedText(text);
        }
        return verdict;
    }

    private static void rememberVerdict(String key, Verdict verdict) {
        if (VERDICTS.size() >= MAX_VERDICTS) {
            Iterator<String> it = VERDICTS.keySet().iterator();
            int removed = 0;
            while (it.hasNext() && removed < 200) {
                it.next();
                it.remove();
                removed++;
            }
        }
        VERDICTS.put(key, verdict);
    }

    /**
     * Checks text through TKCore. If the configured API version is rejected (for example because
     * the key has no permission for 2.0), retry once with the other version and remember the one
     * that works. This keeps old saved settings from silently disabling all hiding.
     */
    private static Verdict checkText(Context context, String text) {
        String apiKey = pref(context, KEY_API_KEY, "");
        if (apiKey.isEmpty()) {
            return new Verdict("error", "Нет API key — откройте настройки", false);
        }
        String configured = pref(context, KEY_VERSION, DEF_VERSION);
        boolean auto = "auto".equalsIgnoreCase(configured) || configured.isEmpty();
        if (auto) {
            configured = "1.0";
        }
        // With an explicitly chosen version, always use it; the remembered version is only
        // meaningful for "auto" mode. Otherwise a temporary fallback could stick and make the
        // panel show a version the user did not select.
        String preferred = auto && !workingVersion.isEmpty() ? workingVersion : configured;
        Verdict verdict = checkTextVersioned(context, text, preferred, apiKey);
        if (!"error".equals(verdict.type)) {
            if (auto) {
                workingVersion = preferred;
            }
            return verdict;
        }
        String alternate = "1.0".equals(preferred) ? "2.0" : "1.0";
        Verdict retry = checkTextVersioned(context, text, alternate, apiKey);
        if (!"error".equals(retry.type) && auto) {
            workingVersion = alternate;
        }
        return retry;
    }

    private static Verdict checkTextVersioned(Context context, String text, String version, String apiKey) {
        try {
            String base = pref(context, KEY_TK_BASE, DEF_TK_BASE);
            String query = "api_key=" + enc(apiKey)
                    + "&type=botmoderator"
                    + "&phrase=" + enc(text)
                    + "&phrase2=" + enc(pref(context, KEY_MODE, DEF_MODE))
                    + "&version=" + enc(version);
            HttpResponse response = request(base + "?" + query, "GET", null);
            JSONObject parsed = new JSONObject(response.body);
            String raw = parsed.has("type") ? String.valueOf(parsed.opt("type"))
                    : (parsed.has("error") ? "error" : "unknown");
            Verdict verdict = normalizeVerdict(raw);
            if ("error".equals(verdict.type) && parsed.has("error")) {
                return new Verdict("error",
                        "TKCore (version " + version + "): " + parsed.optString("error"), false);
            }
            return verdict;
        } catch (Exception ex) {
            Log.w(TAG, "TKCore check failed for version " + version, ex);
            return new Verdict("error",
                    "Ошибка проверки (version " + version + "): " + ex.getMessage(), false);
        }
    }

    /** The version used for checks, for display in the panel. */
    private static String activeVersion(Context context) {
        String configured = pref(context, KEY_VERSION, DEF_VERSION);
        if ("auto".equalsIgnoreCase(configured) || configured.isEmpty()) {
            return workingVersion.isEmpty() ? "авто" : workingVersion;
        }
        // An explicitly chosen version must always be shown, even if a temporary probe
        // remembered a different one (that value is only meaningful for "auto").
        return configured;
    }

    /** Sends real requests with the key currently typed into the settings dialog. */
    private static String testKey(String key, String base, String mode, String version) {
        if (key == null || key.isEmpty()) {
            return "Ключ не введён — вставьте ключ в поле выше";
        }
        String typed = version.isEmpty() ? DEF_VERSION : version;
        if ("auto".equalsIgnoreCase(typed)) {
            String v1 = requestKey(base, key, mode, "1.0");
            String v2 = requestKey(base, key, mode, "2.0");
            if (looksLikeVerdict(v1)) {
                workingVersion = "1.0";
            } else if (looksLikeVerdict(v2)) {
                workingVersion = "2.0";
            }
            return "version 1.0: " + v1 + "\nversion 2.0: " + v2;
        }
        String first = requestKey(base, key, mode, typed);
        if (looksLikeVerdict(first)) {
            workingVersion = typed;
            return "Ответ TKCore (version " + typed + "): " + first;
        }
        String alternate = "1.0".equals(typed) ? "2.0" : "1.0";
        String second = requestKey(base, key, mode, alternate);
        if (looksLikeVerdict(second)) {
            workingVersion = alternate;
        }
        return "version " + typed + ": " + first + "\nversion " + alternate + ": " + second;
    }

    private static String requestKey(String base, String key, String mode, String version) {
        try {
            String url = (base.isEmpty() ? DEF_TK_BASE : base) + "?api_key=" + enc(key)
                    + "&type=botmoderator"
                    + "&phrase=" + enc("test")
                    + "&phrase2=" + enc(mode.isEmpty() ? DEF_MODE : mode)
                    + "&version=" + enc(version);
            HttpResponse response = request(url, "GET", null);
            String body = response.body == null ? "" : response.body;
            if (body.length() > 300) {
                body = body.substring(0, 300) + "...";
            }
            return body.isEmpty() ? "пустой ответ" : body;
        } catch (Exception ex) {
            return "Ошибка запроса: " + ex.getMessage();
        }
    }

    private static boolean looksLikeVerdict(String body) {
        try {
            JSONObject parsed = new JSONObject(body);
            String type = parsed.has("type") ? String.valueOf(parsed.opt("type")) : "";
            return !type.isEmpty() && !"error".equals(type);
        } catch (Exception ignored) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------
    // YouTube Innertube
    // ---------------------------------------------------------------------------------------

    private static JSONObject innertube(Context context, String endpoint, JSONObject extra) throws Exception {
        JSONObject client = new JSONObject();
        client.put("clientName", "WEB");
        client.put("clientVersion", pref(context, KEY_CLIENT_VERSION, DEF_CLIENT_VERSION));
        client.put("hl", "ru");
        client.put("gl", "RU");
        JSONObject contextObj = new JSONObject();
        contextObj.put("client", client);
        JSONObject body = new JSONObject();
        body.put("context", contextObj);
        Iterator<String> extraKeys = extra.keys();
        while (extraKeys.hasNext()) {
            String key = extraKeys.next();
            body.put(key, extra.opt(key));
        }
        String key = pref(context, KEY_INNERTUBE_KEY, DEF_INNERTUBE_KEY);
        String url = "https://www.youtube.com/youtubei/v1/" + endpoint
                + "?key=" + enc(key) + "&prettyPrint=false";
        HttpResponse response = request(url, "POST", body.toString());
        return new JSONObject(response.body);
    }

    /** Depth-first collection of every value stored under a key. */
    private static List<Object> walk(Object node, final String key) {
        List<Object> found = new ArrayList<>();
        List<Object> stack = new ArrayList<>();
        stack.add(node);
        while (!stack.isEmpty()) {
            Object current = stack.remove(stack.size() - 1);
            if (current instanceof JSONObject) {
                JSONObject object = (JSONObject) current;
                Iterator<String> names = object.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    Object value = object.opt(name);
                    if (key.equals(name)) {
                        found.add(value);
                    }
                    stack.add(value);
                }
            } else if (current instanceof JSONArray) {
                JSONArray array = (JSONArray) current;
                for (int i = array.length() - 1; i >= 0; i--) {
                    stack.add(array.opt(i));
                }
            }
        }
        return found;
    }

    private static Object first(Object node, String key) {
        List<Object> found = walk(node, key);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String textOf(Object value) {
        // Some Innertube responses contain non-finite numbers or the literal "NaN"; they must
        // never reach the panel as visible text.
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                return "";
            }
        }
        if (value instanceof String || value instanceof Number) {
            String text = String.valueOf(value);
            return ("NaN".equals(text) || "undefined".equals(text)) ? "" : text;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has("simpleText")) {
                return String.valueOf(object.opt("simpleText"));
            }
            if (object.has("runs")) {
                JSONArray runs = object.optJSONArray("runs");
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < runs.length(); i++) {
                    JSONObject run = runs.optJSONObject(i);
                    if (run != null && run.has("text")) {
                        out.append(run.opt("text"));
                    }
                }
                return out.toString();
            }
            if (object.has("content")) {
                return textOf(object.opt("content"));
            }
        }
        return "";
    }

    private static JSONObject videoInfo(Context context, String videoId) {
        try {
            JSONObject response = innertube(context, "player", new JSONObject().put("videoId", videoId));
            Object details = first(response, "videoDetails");
            if (details instanceof JSONObject) {
                JSONObject info = new JSONObject();
                JSONObject d = (JSONObject) details;
                info.put("id", d.optString("videoId", videoId));
                info.put("title", textOf(d.opt("title")));
                info.put("channelId", d.optString("channelId", ""));
                return info;
            }
        } catch (Exception ex) {
            Log.w(TAG, "Could not load video info", ex);
        }
        return new JSONObject();
    }

    private static String continuationOf(Object node, String key) {
        for (Object value : walk(node, key)) {
            if (value instanceof JSONObject) {
                String token = ((JSONObject) value).optString("continuation", "");
                if (!token.isEmpty()) {
                    return token;
                }
            }
        }
        return "";
    }

    private static String chatContinuation(Object node) {
        Object pool = node;
        Object renderer = first(node, "liveChatRenderer");
        Object live = first(node, "liveChatContinuation");
        if (renderer != null) {
            pool = renderer;
        } else if (live != null) {
            pool = live;
        }
        String token = continuationOf(pool, "invalidationContinuationData");
        if (token.isEmpty()) {
            token = continuationOf(pool, "timedContinuationData");
        }
        if (token.isEmpty()) {
            token = continuationOf(pool, "reloadContinuationData");
        }
        if (token.isEmpty()) {
            token = continuationOf(node, "invalidationContinuationData");
            if (token.isEmpty()) token = continuationOf(node, "timedContinuationData");
            if (token.isEmpty()) token = continuationOf(node, "reloadContinuationData");
        }
        return token;
    }

    private static JSONArray extractChat(Object node) {
        JSONArray out = new JSONArray();
        List<Object> seen = new ArrayList<>();
        for (Object value : walk(node, "liveChatTextMessageRenderer")) {
            addChatItem(out, seen, value);
        }
        for (Object value : walk(node, "liveChatPaidMessageRenderer")) {
            addChatItem(out, seen, value);
        }
        return out;
    }

    private static void addChatItem(JSONArray out, List<Object> seen, Object value) {
        if (!(value instanceof JSONObject)) {
            return;
        }
        JSONObject item = (JSONObject) value;
        String id = item.optString("id", "");
        String author = textOf(item.opt("authorName"));
        String message = textOf(item.opt("message"));
        if (message.isEmpty()) {
            message = textOf(item.opt("header"));
        }
        String dedupe = id.isEmpty() ? author + "|" + message : id;
        if (seen.contains(dedupe)) {
            return;
        }
        seen.add(dedupe);
        try {
            JSONObject row = new JSONObject();
            row.put("id", id);
            row.put("text", message);
            row.put("author", author);
            row.put("channelId", item.optString("authorExternalChannelId", ""));
            row.put("publishedAt", timestamp(item.optString("timestampUsec", "")));
            out.put(row);
        } catch (Exception ignored) {
            // ignored
        }
    }

    private static String timestamp(String usec) {
        try {
            if (usec.isEmpty()) {
                return "";
            }
            long seconds = Long.parseLong(usec.substring(0, 10));
            return Instant.ofEpochSecond(seconds).toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private static JSONObject chatPage(Context context, String videoId, String continuation) throws Exception {
        JSONObject response;
        String next;
        if (continuation.isEmpty()) {
            response = innertube(context, "next", new JSONObject().put("videoId", videoId));
            next = chatContinuation(response);
            if (next.isEmpty()) {
                JSONObject out = new JSONObject();
                out.put("messages", new JSONArray());
                out.put("continuation", "");
                out.put("error", "no_active_live_chat");
                return out;
            }
        } else {
            response = innertube(context, "live_chat/get_live_chat",
                    new JSONObject().put("continuation", continuation));
            next = chatContinuation(response);
            if (next.isEmpty()) {
                next = continuation;
            }
        }
        JSONObject out = new JSONObject();
        out.put("messages", extractChat(response));
        out.put("continuation", next);
        out.put("error", "");
        return out;
    }

    /** Parses top-level comments from frameworkUpdates.entityBatchUpdate.mutations. */
    private static JSONArray parseEntityComments(JSONObject response) {
        JSONArray out = new JSONArray();
        JSONObject batch = objectPath(response, "frameworkUpdates", "entityBatchUpdate");
        JSONArray mutations = batch == null ? null : batch.optJSONArray("mutations");
        Set<String> seen = new HashSet<>();
        for (int i = 0; mutations != null && i < mutations.length(); i++) {
            JSONObject properties = objectPath(mutations.optJSONObject(i),
                    "payload", "commentEntityPayload", "properties");
            if (properties == null || properties.optInt("replyLevel") != 0) {
                continue;
            }
            String id = properties.optString("commentId", "");
            JSONObject content = properties.optJSONObject("content");
            String text = content == null ? "" : textOf(content.opt("content"));
            if (text.isEmpty() && id.isEmpty()) {
                continue;
            }
            if (!seen.add(id.isEmpty() ? text : id)) {
                continue;
            }
            try {
                JSONObject row = new JSONObject();
                row.put("id", id);
                row.put("text", text);
                row.put("author", firstText(properties, "displayName", "authorText", "author"));
                row.put("publishedAt", firstText(properties, "publishedTimeText", "publishedTime"));
                out.put(row);
            } catch (Exception ignored) {
                // ignored
            }
        }
        return out;
    }

    private static JSONArray extractComments(Object node) {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        // Modern YouTube responses often use commentViewModel/commentEntityPayload instead of
        // the legacy commentRenderer, so collect from all known shapes and dedupe by id/text.
        String[] rendererKeys = {"commentRenderer", "commentViewModel", "commentEntityPayload",
                "commentThreadRenderer"};
        for (String rendererKey : rendererKeys) {
            for (Object value : walk(node, rendererKey)) {
                if (!(value instanceof JSONObject)) {
                    continue;
                }
                JSONObject item = (JSONObject) value;
                String id = firstText(item, "commentId", "commentIdentifier", "id");
                String text = firstText(item, "contentText", "content", "text", "contentText");
                String author = firstText(item, "authorText", "authorName", "author");
                String published = firstText(item, "publishedTimeText", "publishedTime", "dateText");
                if (text.isEmpty() && id.isEmpty()) {
                    continue;
                }
                String dedupe = id.isEmpty() ? author + "|" + text : id;
                if (seen.contains(dedupe)) {
                    continue;
                }
                seen.add(dedupe);
                try {
                    JSONObject row = new JSONObject();
                    row.put("id", id);
                    row.put("text", text);
                    row.put("author", author);
                    row.put("publishedAt", published);
                    out.put(row);
                } catch (Exception ignored) {
                    // ignored
                }
            }
        }
        return out;
    }

    /** Returns the first non-empty text found under any of the given keys in a subtree. */
    private static String firstText(Object node, String... keys) {
        for (String key : keys) {
            for (Object value : walk(node, key)) {
                String text = textOf(value);
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static String commentsContinuation(Object node) {
        Object pool = node;
        Object append = first(node, "appendContinuationItemsAction");
        Object section = first(node, "commentSectionRenderer");
        if (append != null) {
            pool = append;
        } else if (section != null) {
            pool = section;
        }
        String token = findContinuationToken(pool);
        if (token.isEmpty()) {
            token = findContinuationToken(node);
        }
        return token;
    }

    private static String findContinuationToken(Object node) {
        String last = "";
        // continuationItemRenderer -> continuationEndpoint -> continuationCommand/reloadContinuationData
        for (Object value : walk(node, "continuationItemRenderer")) {
            if (!(value instanceof JSONObject)) {
                continue;
            }
            Object endpoint = ((JSONObject) value).opt("continuationEndpoint");
            String token = tokenFromEndpoint(endpoint);
            if (!token.isEmpty()) {
                last = token;
            }
        }
        if (!last.isEmpty()) {
            return last;
        }
        // Fallback: any continuationCommand token (e.g. in other renderer shapes)
        for (Object value : walk(node, "continuationCommand")) {
            if (value instanceof JSONObject) {
                String token = ((JSONObject) value).optString("token", "");
                if (!token.isEmpty()) {
                    last = token;
                }
            }
        }
        return last;
    }

    private static String tokenFromEndpoint(Object endpoint) {
        if (!(endpoint instanceof JSONObject)) {
            return "";
        }
        JSONObject ep = (JSONObject) endpoint;
        Object command = ep.opt("continuationCommand");
        if (command instanceof JSONObject) {
            String token = ((JSONObject) command).optString("token", "");
            if (!token.isEmpty()) {
                return token;
            }
        }
        Object reload = ep.opt("reloadContinuationData");
        if (reload instanceof JSONObject) {
            String token = ((JSONObject) reload).optString("continuation", "");
            if (!token.isEmpty()) {
                return token;
            }
        }
        return "";
    }

    /** Watch-page configuration needed to request comment pages from Innertube. */
    private static final class CommentsState {
        final String apiKey;
        final String clientVersion;
        final JSONObject context;
        final String visitorData;
        final String continuation;

        CommentsState(String apiKey, String clientVersion, JSONObject context,
                      String visitorData, String continuation) {
            this.apiKey = apiKey;
            this.clientVersion = clientVersion;
            this.context = context;
            this.visitorData = visitorData;
            this.continuation = continuation;
        }
    }

    /**
     * Loads the watch page and extracts the Innertube client context plus the comments-section
     * continuation token. Morphe's Niconico comments use the same flow; it is the only reliable
     * way to get comments from current YouTube clients.
     */
    private static CommentsState fetchCommentsState(Context context, String videoId) throws Exception {
        String watchUrl = YOUTUBE_ORIGIN + "/watch?v=" + enc(videoId)
                + "&hl=en&bpctr=9999999999&has_verified=1";
        HttpResponse page = requestYouTube(watchUrl, "GET", null, null, null, null);
        String html = page.body;

        JSONObject config = parseYoutubeConfig(html);
        String apiKey = config.optString("INNERTUBE_API_KEY", "");
        JSONObject innertubeContext = config.optJSONObject("INNERTUBE_CONTEXT");
        String clientVersion = "";
        if (innertubeContext != null) {
            JSONObject client = innertubeContext.optJSONObject("client");
            if (client != null) {
                clientVersion = client.optString("clientVersion", "");
            }
        }
        if (clientVersion.isEmpty()) {
            clientVersion = config.optString("INNERTUBE_CLIENT_VERSION", "");
        }
        String visitorData = config.optString("VISITOR_DATA", "");
        if (visitorData.isEmpty() && innertubeContext != null) {
            JSONObject client = innertubeContext.optJSONObject("client");
            if (client != null) {
                visitorData = client.optString("visitorData", "");
            }
        }
        if (apiKey.isEmpty()) {
            apiKey = pref(context, KEY_INNERTUBE_KEY, DEF_INNERTUBE_KEY);
        }
        if (clientVersion.isEmpty()) {
            clientVersion = pref(context, KEY_CLIENT_VERSION, DEF_CLIENT_VERSION);
        }
        if (innertubeContext == null) {
            innertubeContext = new JSONObject();
            JSONObject client = new JSONObject();
            client.put("clientName", "WEB");
            client.put("clientVersion", clientVersion);
            if (!visitorData.isEmpty()) {
                client.put("visitorData", visitorData);
            }
            innertubeContext.put("client", client);
            innertubeContext.put("request", new JSONObject().put("useSsl", true));
        }
        JSONObject initialData = parseMarkedObject(html, INITIAL_DATA_MARKERS);
        String continuation = initialData == null ? "" : findCommentsContinuation(initialData);
        return new CommentsState(apiKey, clientVersion, innertubeContext, visitorData, continuation);
    }

    /** Posts to an Innertube endpoint with the watch-page context and client headers. */
    private static JSONObject postInnertube(CommentsState state, String path, JSONObject body)
            throws Exception {
        String endpoint = YOUTUBE_ORIGIN + "/youtubei/v1/" + path + "?key="
                + enc(state.apiKey) + "&prettyPrint=false";
        body.put("context", state.context);
        HttpResponse response = requestYouTube(endpoint, "POST", body.toString(),
                state.clientVersion, state.visitorData, "application/json");
        String raw = response.body;
        int brace = raw.indexOf('{');
        if (brace > 0) {
            raw = raw.substring(brace);
        }
        return new JSONObject(raw);
    }

    /** HTTP helper with a browser user agent and the YouTube client headers. */
    private static HttpResponse requestYouTube(String url, String method, String body,
                                               String clientVersion, String visitorData,
                                               String contentType) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", YT_WEB_USER_AGENT);
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type",
                        contentType == null ? "application/json" : contentType);
                connection.setRequestProperty("Origin", YOUTUBE_ORIGIN);
                connection.setRequestProperty("X-Youtube-Client-Name", "1");
                if (clientVersion != null && !clientVersion.isEmpty()) {
                    connection.setRequestProperty("X-Youtube-Client-Version", clientVersion);
                }
                if (visitorData != null && !visitorData.isEmpty()) {
                    connection.setRequestProperty("X-Goog-Visitor-Id", visitorData);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                OutputStream stream = connection.getOutputStream();
                try {
                    stream.write(bytes);
                } finally {
                    stream.close();
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                return new HttpResponse(code, "");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            stream.close();
            return new HttpResponse(code, out.toString("UTF-8"));
        } finally {
            connection.disconnect();
        }
    }

    /** Merges every ytcfg.set(...) object found in a watch page. */
    private static JSONObject parseYoutubeConfig(String html) throws Exception {
        JSONObject result = new JSONObject();
        int searchIndex = 0;
        while (searchIndex < html.length()) {
            int markerIndex = html.indexOf("ytcfg.set(", searchIndex);
            if (markerIndex < 0) {
                break;
            }
            JSONObject value = parseObjectAt(html, markerIndex + "ytcfg.set(".length());
            if (value != null) {
                Iterator<String> keys = value.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    result.put(key, value.opt(key));
                }
            }
            searchIndex = markerIndex + "ytcfg.set(".length();
        }
        return result;
    }

    private static JSONObject parseMarkedObject(String text, String... markers) {
        for (String marker : markers) {
            int markerIndex = text.indexOf(marker);
            if (markerIndex >= 0) {
                JSONObject value = parseObjectAt(text, markerIndex + marker.length());
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /** Parses the first balanced JSON object starting at or after startIndex. */
    private static JSONObject parseObjectAt(String text, int startIndex) {
        int start = text.indexOf('{', startIndex);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char value = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                }
                continue;
            }
            if (value == '"') {
                inString = true;
            } else if (value == '{') {
                depth++;
            } else if (value == '}') {
                depth--;
                if (depth == 0) {
                    try {
                        return new JSONObject(text.substring(start, index + 1));
                    } catch (Exception ex) {
                        Log.w(TAG, "Could not parse embedded JSON", ex);
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /** Navigates a nested object by keys, returning null if any step is missing. */
    private static JSONObject objectPath(Object node, String... keys) {
        Object current = node;
        for (String key : keys) {
            if (!(current instanceof JSONObject)) {
                return null;
            }
            current = ((JSONObject) current).opt(key);
        }
        return current instanceof JSONObject ? (JSONObject) current : null;
    }

    /** Finds the comments-section continuation token in ytInitialData. */
    private static String findCommentsContinuation(JSONObject initialData) {
        JSONObject results = objectPath(initialData,
                "contents", "twoColumnWatchNextResults", "results", "results");
        JSONArray contents = results == null ? null : results.optJSONArray("contents");
        if (contents == null) {
            return "";
        }
        for (int i = 0; i < contents.length(); i++) {
            JSONObject section = objectPath(contents.optJSONObject(i), "itemSectionRenderer");
            if (section != null && "comment-item-section".equals(section.optString("sectionIdentifier"))) {
                return lastContinuationToken(section.optJSONArray("contents"));
            }
        }
        return "";
    }

    /** Returns the token of the last continuationItemRenderer in an array. */
    private static String lastContinuationToken(JSONArray items) {
        if (items == null || items.length() == 0) {
            return "";
        }
        JSONObject command = objectPath(items.optJSONObject(items.length() - 1),
                "continuationItemRenderer", "continuationEndpoint", "continuationCommand");
        return command == null ? "" : command.optString("token", "");
    }

    /** Extracts the next comments-page token from an Innertube "next" response. */
    private static String commentsContinuationFromNext(JSONObject response) {
        String token = "";
        JSONArray endpoints = response.optJSONArray("onResponseReceivedEndpoints");
        for (int i = 0; endpoints != null && i < endpoints.length(); i++) {
            JSONObject endpoint = endpoints.optJSONObject(i);
            JSONObject command = objectPath(endpoint, "appendContinuationItemsAction");
            if (command == null) {
                command = objectPath(endpoint, "reloadContinuationItemsCommand");
            }
            String found = lastContinuationToken(command == null ? null
                    : command.optJSONArray("continuationItems"));
            if (!found.isEmpty()) {
                token = found;
            }
        }
        if (token.isEmpty()) {
            token = commentsContinuation(response);
        }
        return token;
    }

    private static JSONObject commentsPage(Context context, String videoId, String continuation)
            throws Exception {
        CommentsState state = COMMENT_STATES.get(videoId);
        if (state == null) {
            state = fetchCommentsState(context, videoId);
            COMMENT_STATES.put(videoId, state);
        }
        String token = continuation.isEmpty() ? state.continuation : continuation;
        if (token.isEmpty()) {
            JSONObject out = new JSONObject();
            out.put("comments", new JSONArray());
            out.put("continuation", "");
            out.put("hasContinuation", false);
            out.put("error", "no_comments_continuation");
            return out;
        }
        JSONObject response = postInnertube(state, "next",
                new JSONObject().put("continuation", token));
        String next = commentsContinuationFromNext(response);
        JSONArray comments = parseEntityComments(response);
        if (comments.length() == 0) {
            comments = extractComments(response);
            if (comments.length() == 0) {
                String dump = response.toString();
                Log.w(TAG, "No comments parsed. Response snippet: "
                        + dump.substring(0, Math.min(500, dump.length())));
            }
        }
        JSONObject out = new JSONObject();
        out.put("comments", comments);
        out.put("continuation", next);
        out.put("hasContinuation", !next.isEmpty());
        out.put("error", "");
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // Streamer mode (Google OAuth device flow + YouTube Data API moderation)
    // ---------------------------------------------------------------------------------------

    private static boolean isStreamerMode(Context context) {
        return "streamer".equals(pref(context, KEY_USER_MODE, DEF_USER_MODE));
    }

    private static boolean hasGoogleTokens(Context context) {
        return !pref(context, KEY_GOOGLE_REFRESH_TOKEN, "").isEmpty()
                || !pref(context, KEY_GOOGLE_ACCESS_TOKEN, "").isEmpty();
    }

    private static String googleClientId(Context context) {
        return pref(context, KEY_GOOGLE_CLIENT_ID, "").trim();
    }

    private static String googleClientSecret(Context context) {
        return pref(context, KEY_GOOGLE_CLIENT_SECRET, "").trim();
    }

    private static void clearGoogleTokens(Context context) {
        savePref(context, KEY_GOOGLE_ACCESS_TOKEN, "");
        savePref(context, KEY_GOOGLE_REFRESH_TOKEN, "");
        savePref(context, KEY_GOOGLE_TOKEN_EXPIRY, "0");
    }

    private static String ensureGoogleAccessToken(Context context) throws Exception {
        String access = pref(context, KEY_GOOGLE_ACCESS_TOKEN, "");
        long expiry = 0;
        try {
            expiry = Long.parseLong(pref(context, KEY_GOOGLE_TOKEN_EXPIRY, "0"));
        } catch (NumberFormatException ignored) {
            expiry = 0;
        }
        if (!access.isEmpty() && System.currentTimeMillis() < expiry - 60000) {
            return access;
        }
        String refresh = pref(context, KEY_GOOGLE_REFRESH_TOKEN, "");
        if (refresh.isEmpty()) {
            throw new IllegalStateException("Нет авторизации Google — войдите в настройках стримера");
        }
        String clientId = googleClientId(context);
        if (clientId.isEmpty()) {
            throw new IllegalStateException("Не указан Google Client ID");
        }
        String body = "client_id=" + enc(clientId)
                + "&refresh_token=" + enc(refresh)
                + "&grant_type=refresh_token";
        String secret = googleClientSecret(context);
        if (!secret.isEmpty()) {
            body += "&client_secret=" + enc(secret);
        }
        HttpResponse response = requestRaw(GOOGLE_TOKEN_URL, "POST", body,
                "application/x-www-form-urlencoded", null);
        JSONObject json = new JSONObject(response.body);
        if (response.code < 200 || response.code >= 400 || json.has("error")) {
            String err = json.optString("error_description", json.optString("error", response.body));
            throw new IllegalStateException("Не удалось обновить токен Google: " + err);
        }
        access = json.optString("access_token", "");
        int expiresIn = json.optInt("expires_in", 3600);
        if (access.isEmpty()) {
            throw new IllegalStateException("Google не вернул access_token");
        }
        savePref(context, KEY_GOOGLE_ACCESS_TOKEN, access);
        savePref(context, KEY_GOOGLE_TOKEN_EXPIRY,
                String.valueOf(System.currentTimeMillis() + (long) expiresIn * 1000L));
        if (json.has("refresh_token")) {
            savePref(context, KEY_GOOGLE_REFRESH_TOKEN, json.optString("refresh_token", refresh));
        }
        return access;
    }

    private static void startDeviceLogin(final Activity activity, final String clientId,
                                         final String clientSecret, final TextView statusView) {
        if (clientId.isEmpty()) {
            statusView.setText("Укажите Google Client ID");
            return;
        }
        statusView.setText("Запрос кода авторизации...");
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = "client_id=" + enc(clientId) + "&scope=" + enc(YT_SCOPE);
                    if (!clientSecret.isEmpty()) {
                        body += "&client_secret=" + enc(clientSecret);
                    }
                    HttpResponse response = requestRaw(GOOGLE_DEVICE_CODE_URL, "POST", body,
                            "application/x-www-form-urlencoded", null);
                    final JSONObject json = new JSONObject(response.body);
                    if (response.code < 200 || response.code >= 400 || json.has("error")) {
                        final String err = json.optString("error_description",
                                json.optString("error", response.body));
                        MAIN.post(new Runnable() {
                            @Override public void run() {
                                statusView.setText("Ошибка авторизации: " + err);
                            }
                        });
                        return;
                    }
                    final String deviceCode = json.optString("device_code", "");
                    final String userCode = json.optString("user_code", "");
                    final String verifyUrl = json.optString("verification_url",
                            json.optString("verification_uri", "https://www.google.com/device"));
                    int interval = json.optInt("interval", 5);
                    int expiresIn = json.optInt("expires_in", 1800);
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            statusView.setText("Код: " + userCode + "\nОткройте: " + verifyUrl
                                    + "\nОжидание подтверждения...");
                            new AlertDialog.Builder(activity)
                                    .setTitle("Вход Google для стримера")
                                    .setMessage("1) Откройте: " + verifyUrl
                                            + "\n2) Введите код: " + userCode
                                            + "\n\nПосле подтверждения окно настроек покажет статус.")
                                    .setPositiveButton("OK", null)
                                    .show();
                        }
                    });
                    pollDeviceToken(activity, clientId, clientSecret, deviceCode, interval, expiresIn, statusView);
                } catch (final Exception ex) {
                    Log.w(TAG, "Device login start failed", ex);
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            statusView.setText("Ошибка: " + ex.getMessage());
                        }
                    });
                }
            }
        });
    }

    private static void pollDeviceToken(Activity activity, String clientId, String clientSecret,
                                        String deviceCode, int intervalSec, int expiresIn,
                                        final TextView statusView) {
        long deadline = System.currentTimeMillis() + (long) expiresIn * 1000L;
        int interval = Math.max(intervalSec, 5);
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep((long) interval * 1000L);
            } catch (InterruptedException ex) {
                return;
            }
            try {
                String body = "client_id=" + enc(clientId)
                        + "&device_code=" + enc(deviceCode)
                        + "&grant_type=urn:ietf:params:oauth:grant-type:device_code";
                if (!clientSecret.isEmpty()) {
                    body += "&client_secret=" + enc(clientSecret);
                }
                HttpResponse response = requestRaw(GOOGLE_TOKEN_URL, "POST", body,
                        "application/x-www-form-urlencoded", null);
                JSONObject json = new JSONObject(response.body);
                if (response.code >= 200 && response.code < 300 && json.has("access_token")) {
                    String access = json.optString("access_token", "");
                    String refresh = json.optString("refresh_token", "");
                    int tokenExpires = json.optInt("expires_in", 3600);
                    Context ctx = activity.getApplicationContext();
                    savePref(ctx, KEY_GOOGLE_CLIENT_ID, clientId);
                    savePref(ctx, KEY_GOOGLE_CLIENT_SECRET, clientSecret);
                    savePref(ctx, KEY_GOOGLE_ACCESS_TOKEN, access);
                    if (!refresh.isEmpty()) {
                        savePref(ctx, KEY_GOOGLE_REFRESH_TOKEN, refresh);
                    }
                    savePref(ctx, KEY_GOOGLE_TOKEN_EXPIRY,
                            String.valueOf(System.currentTimeMillis() + (long) tokenExpires * 1000L));
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            statusView.setText("Google авторизация выполнена. Теперь можно найти активный стрим.");
                        }
                    });
                    return;
                }
                String error = json.optString("error", "");
                if ("authorization_pending".equals(error)) {
                    continue;
                } else if ("slow_down".equals(error)) {
                    interval += 5;
                    continue;
                } else if ("access_denied".equals(error) || "expired_token".equals(error)) {
                    final String msg = json.optString("error_description", error);
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            statusView.setText("Авторизация не завершена: " + msg);
                        }
                    });
                    return;
                } else if (!error.isEmpty()) {
                    final String msg = json.optString("error_description", error);
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            statusView.setText("Ошибка авторизации: " + msg);
                        }
                    });
                    return;
                }
            } catch (Exception ex) {
                Log.w(TAG, "Device token poll error", ex);
            }
        }
        MAIN.post(new Runnable() {
            @Override public void run() {
                statusView.setText("Время ожидания авторизации истекло. Попробуйте снова.");
            }
        });
    }

    private static String findActiveLiveChatId(Context context) throws Exception {
        String token = ensureGoogleAccessToken(context);
        String url = YT_API_BASE + "/liveBroadcasts?part=snippet,status&broadcastStatus=active&mine=true&maxResults=5";
        HttpResponse response = requestRaw(url, "GET", null, null, "Bearer " + token);
        if (response.code == 401) {
            savePref(context, KEY_GOOGLE_ACCESS_TOKEN, "");
            token = ensureGoogleAccessToken(context);
            response = requestRaw(url, "GET", null, null, "Bearer " + token);
        }
        JSONObject json = new JSONObject(response.body);
        if (response.code < 200 || response.code >= 400) {
            String err = json.optJSONObject("error") != null
                    ? json.optJSONObject("error").optString("message", response.body)
                    : response.body;
            throw new IllegalStateException("YouTube API: " + err);
        }
        JSONArray items = json.optJSONArray("items");
        if (items == null || items.length() == 0) {
            return "";
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            JSONObject snippet = item.optJSONObject("snippet");
            if (snippet != null) {
                String liveChatId = snippet.optString("liveChatId", "");
                String videoId = item.optString("id", snippet.optString("videoId", ""));
                if (!liveChatId.isEmpty()) {
                    savePref(context, KEY_STREAMER_LIVE_CHAT_ID, liveChatId);
                    if (!videoId.isEmpty()) {
                        savePref(context, KEY_STREAMER_VIDEO_ID, videoId);
                    }
                    return liveChatId;
                }
            }
        }
        return "";
    }

    private static boolean streamerManualAction(Context context) {
        return "manual".equals(pref(context, KEY_STREAMER_ACTION, "auto_delete"));
    }

    private static String streamerBanMode(Context context) {
        String mode = pref(context, KEY_STREAMER_BAN_MODE, "off");
        if ("timeout".equals(mode) || "permanent".equals(mode)) {
            return mode;
        }
        return "off";
    }

    private static int streamerBanSeconds(Context context) {
        try {
            int value = Integer.parseInt(pref(context, KEY_STREAMER_BAN_SECONDS, "300"));
            return value > 0 ? value : 300;
        } catch (NumberFormatException ignored) {
            return 300;
        }
    }

    private static String effectiveLiveChatId(Context context) {
        if ("1".equals(pref(context, KEY_STREAMER_MANUAL_BIND, "0"))) {
            String manual = pref(context, KEY_STREAMER_MANUAL_LIVE_CHAT_ID, "").trim();
            if (!manual.isEmpty()) {
                return manual;
            }
        }
        return pref(context, KEY_STREAMER_LIVE_CHAT_ID, "").trim();
    }

    private static void maybeAutoModerateChatMessage(Context context, JSONObject item, Verdict verdict) {
        if (!verdict.bad) {
            return;
        }
        if (!isStreamerMode(context) || !hasGoogleTokens(context)) {
            return;
        }
        String messageId = item.optString("id", "");
        if (messageId.isEmpty()) {
            return;
        }
        if (DELETED_MESSAGES.containsKey(messageId)) {
            try { item.put("deletedByStreamer", true); } catch (Exception ignored) {}
            return;
        }
        if (streamerManualAction(context)) {
            try { item.put("actionPending", true); } catch (Exception ignored) {}
            return;
        }
        performStreamerModeration(context, item, messageId, item.optString("channelId", ""), true);
    }

    private static JSONObject performStreamerModeration(Context context, JSONObject item,
                                                        String messageId, String channelId,
                                                        boolean fromAuto) {
        JSONObject result = new JSONObject();
        try {
            if (messageId == null || messageId.isEmpty()) {
                result.put("ok", false);
                result.put("error", "no_message_id");
                return result;
            }
            if (DELETED_MESSAGES.containsKey(messageId)) {
                result.put("ok", true);
                result.put("deleted", true);
                result.put("already", true);
                if (item != null) item.put("deletedByStreamer", true);
                return result;
            }
            String deleteError = deleteLiveChatMessage(context, messageId);
            boolean deleted = deleteError == null;
            result.put("deleted", deleted);
            if (deleted) {
                rememberDeleted(messageId);
                if (item != null) item.put("deletedByStreamer", true);
            } else {
                result.put("deleteError", deleteError);
                if (item != null) item.put("deleteError", deleteError);
            }
            String banMode = streamerBanMode(context);
            if (!"off".equals(banMode) && channelId != null && !channelId.isEmpty()) {
                String banError = banChatAuthor(context, channelId, banMode, streamerBanSeconds(context));
                boolean banned = banError == null;
                result.put("banned", banned);
                result.put("banMode", banMode);
                if (banned) {
                    if (item != null) item.put("bannedByStreamer", true);
                } else {
                    result.put("banError", banError);
                    if (item != null) item.put("banError", banError);
                }
            }
            result.put("ok", deleted || result.optBoolean("banned", false));
            if (item != null) item.put("actionPending", false);
        } catch (Exception ex) {
            try {
                result.put("ok", false);
                result.put("error", String.valueOf(ex.getMessage()));
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static String deleteLiveChatMessage(Context context, String messageId) {
        try {
            String token = ensureGoogleAccessToken(context);
            String url = YT_API_BASE + "/liveChat/messages?id=" + enc(messageId);
            HttpResponse response = requestRaw(url, "DELETE", null, null, "Bearer " + token);
            if (response.code == 204 || response.code == 200) {
                return null;
            }
            if (response.code == 401) {
                savePref(context, KEY_GOOGLE_ACCESS_TOKEN, "");
                token = ensureGoogleAccessToken(context);
                response = requestRaw(url, "DELETE", null, null, "Bearer " + token);
                if (response.code == 204 || response.code == 200) {
                    return null;
                }
            }
            if (response.code == 404 || response.code == 403) {
                // not found/already gone/not allowed - avoid retry loops
                rememberDeleted(messageId);
                return "HTTP " + response.code;
            }
            return "HTTP " + response.code + ": " + compactError(response.body);
        } catch (Exception ex) {
            Log.w(TAG, "Delete failed for " + messageId, ex);
            return String.valueOf(ex.getMessage());
        }
    }

    private static String banChatAuthor(Context context, String channelId, String mode, int seconds) {
        try {
            String token = ensureGoogleAccessToken(context);
            JSONObject channel = new JSONObject().put("channelId", channelId);
            JSONObject details = new JSONObject();
            details.put("type", "permanent".equals(mode) ? "permanent" : "temporary");
            if (!"permanent".equals(mode)) {
                details.put("banDurationSeconds", Math.max(60, seconds));
            }
            JSONObject snippet = new JSONObject();
            snippet.put("bannedUserDetails", channel);
            snippet.put("banDurationSeconds", details.optInt("banDurationSeconds", 0));
            snippet.put("type", details.optString("type"));
            // liveChatId is optional if in live chat context? but better to include if known
            String liveChatId = effectiveLiveChatId(context);
            if (!liveChatId.isEmpty()) {
                snippet.put("liveChatId", liveChatId);
            }
            JSONObject body = new JSONObject();
            body.put("snippet", snippet);
            String url = YT_API_BASE + "/liveChat/bans?part=snippet";
            HttpResponse response = requestRaw(url, "POST", body.toString(),
                    "application/json; charset=utf-8", "Bearer " + token);
            if (response.code >= 200 && response.code < 300) {
                return null;
            }
            if (response.code == 401) {
                savePref(context, KEY_GOOGLE_ACCESS_TOKEN, "");
                token = ensureGoogleAccessToken(context);
                response = requestRaw(url, "POST", body.toString(),
                        "application/json; charset=utf-8", "Bearer " + token);
                if (response.code >= 200 && response.code < 300) {
                    return null;
                }
            }
            return "HTTP " + response.code + ": " + compactError(response.body);
        } catch (Exception ex) {
            Log.w(TAG, "Ban failed for " + channelId, ex);
            return String.valueOf(ex.getMessage());
        }
    }

    private static String compactError(String body) {
        if (body == null || body.isEmpty()) return "";
        try {
            JSONObject json = new JSONObject(body);
            JSONObject err = json.optJSONObject("error");
            if (err != null) {
                String msg = err.optString("message", "");
                if (!msg.isEmpty()) return msg;
            }
        } catch (Exception ignored) {}
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    private static void rememberDeleted(String messageId) {
        if (DELETED_MESSAGES.size() > 500) {
            Iterator<Map.Entry<String, Boolean>> it = DELETED_MESSAGES.entrySet().iterator();
            int removed = 0;
            while (it.hasNext() && removed < 200) {
                it.next();
                it.remove();
                removed++;
            }
        }
        DELETED_MESSAGES.put(messageId, true);
    }

    // ---------------------------------------------------------------------------------------
    // HTTP and preferences
    // ---------------------------------------------------------------------------------------

    private static final class HttpResponse {
        final int code;
        final String body;

        HttpResponse(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    private static HttpResponse request(String url, String method, String body) throws Exception {
        HttpResponse response = requestRaw(url, method, body,
                body == null ? null : "application/json; charset=utf-8", null);
        if (response.code < 200 || response.code >= 400) {
            throw new IllegalStateException("HTTP " + response.code + ": " + response.body);
        }
        return response;
    }

    private static HttpResponse requestRaw(String url, String method, String body,
                                           String contentType, String authorization) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", "TKProtect/1.0");
            connection.setRequestProperty("Accept", "application/json");
            if (authorization != null && !authorization.isEmpty()) {
                connection.setRequestProperty("Authorization", authorization);
            }
            if (body != null) {
                connection.setDoOutput(true);
                if (contentType != null && !contentType.isEmpty()) {
                    connection.setRequestProperty("Content-Type", contentType);
                }
                OutputStream stream = connection.getOutputStream();
                try {
                    stream.write(body.getBytes(StandardCharsets.UTF_8));
                } finally {
                    stream.close();
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                return new HttpResponse(code, "");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            stream.close();
            return new HttpResponse(code, out.toString("UTF-8"));
        } finally {
            connection.disconnect();
        }
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static Context storageContext(Context context) {
        return appContext != null ? appContext : context.getApplicationContext();
    }

    private static String pref(Context context, String key, String fallback) {
        return storageContext(context)
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(key, fallback);
    }

    private static void savePref(Context context, String key, String value) {
        storageContext(context)
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(key, value)
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

    // ---------------------------------------------------------------------------------------
    // Embedded panel markup
    // ---------------------------------------------------------------------------------------

    private static String panelHtml() {
        return String.join("\n",
                "<!DOCTYPE html><html lang='ru'><head><meta charset='utf-8'>",
                "<meta name='viewport' content='width=device-width,initial-scale=1'>",
                "<title>TKProtect</title>",
                "<style>",
                "*{box-sizing:border-box}body{margin:0;background:#101317;color:#e8edf2;",
                "font:14px/1.45 system-ui,sans-serif;padding:14px}",
                "h1{font-size:18px;margin:0 0 4px}h2{font-size:15px;margin:16px 0 8px}",
                ".muted,.empty{color:#8a96a3;font-size:12px}.bad{color:#ff6b6b}",
                "input,textarea,button{font:inherit;border-radius:8px;border:1px solid #2b3644}",
                "input,textarea{background:#161c24;color:#e8edf2;padding:8px;width:100%}",
                "button{background:#2f6fed;color:#fff;border:0;padding:8px 14px;cursor:pointer}",
                ".row{display:flex;gap:8px;margin:8px 0}.row input{flex:1}",
                ".tabs{display:flex;gap:6px;margin:12px 0}.tabs button{flex:1;background:#1b2430}",
                ".tabs button.on{background:#2f6fed}.item{background:#161c24;border-radius:8px;",
                "padding:8px 10px;margin:6px 0;border-left:3px solid #2f6fed}",
                ".item.hidden{opacity:.55;border-left-color:#ff6b6b}",
                ".meta{color:#8a96a3;font-size:11px;display:flex;gap:8px;flex-wrap:wrap}",
                ".text{margin-top:4px;white-space:pre-wrap;word-break:break-word}",
                ".masked{color:#ff6b6b;font-style:italic}.tag{color:#ffd166}",
                ".stats{color:#8a96a3;font-size:12px;margin:8px 0}",
                ".log{color:#8a96a3;font-size:11px;max-height:90px;overflow:auto}",
                "</style></head><body>",
                "<h1>TKProtect</h1>",
                "<div class=muted id=status>Загрузка...</div>",
                "<div class=row><input id=video maxlength=11 placeholder='ID видео (из плеера автоматически)'>",
                "<button id=connect>Обновить</button></div>",
                "<div class=tabs><button data-tab=chat class=on>Чат</button>",
                "<button data-tab=comments>Комментарии</button>",
                "<button data-tab=tool>TKProtect</button></div>",
                "<div id=view-chat><h2>Сообщения чата</h2><div id=chat></div></div>",
                "<div id=view-comments hidden><h2>Комментарии</h2><div id=comments></div></div>",
                "<div id=view-tool hidden><h2>Проверка текста</h2>",
                "<textarea id=check rows=3 placeholder='Введите текст'></textarea>",
                "<div class=row><button id=checkBtn>Проверить</button></div>",
                "<div id=checkResult class=muted></div>",
                "<div class=stats>Проверено: <b id=nChecked>0</b> · Скрыто: <b id=nHidden>0</b></div>",
                "<label class=muted><input type=checkbox id=force style='width:auto'> Показывать скрытые</label></div>",
                "<h2>Журнал</h2><div class=log id=log></div>",
                "<script>",
                "let tab='chat',videoId='',chat=[],comments=[],ready=false,resetChat=true,resetComments=true,streamerAction='auto_delete',userMode='viewer',streamerLinked=false;",
                "const $=id=>document.getElementById(id);",
                "function log(m){const l=$('log');l.innerHTML='<div>'+new Date().toLocaleTimeString()+' — '+m+'</div>'+l.innerHTML}",
                "function esc(s){if(s==null||s==='NaN'||s==='undefined'||(typeof s==='number'&&!isFinite(s)))s='';return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;')",
                ".replace(/>/g,'&gt;').replace(/\"/g,'&quot;')}",
                "function escJs(s){return String(s==null?'':s).replace(/\\\\/g,'\\\\\\\\').replace(/'/g,\"\\\\'\")}",
                "function mod(id,ch){log('Модерация: '+id);TK.moderate(id,ch||'')}",
                "function safe(v){if(v==null)return '';if(typeof v==='number'&&!isFinite(v))return '';",
                "var s=String(v).replace(/NaN|undefined/g,'');return s==='null'?'':s}",
                "function render(list,el,hide){if(!list.length){el.innerHTML='<p class=empty>Пусто.</p>';return}",
                "el.innerHTML=list.map(x=>{var off=!!x.hidden&&!hide;",
                "var reason=safe(x.verdict&&x.verdict.label);var author=safe(x.author)||'Аноним';var date=safe(x.publishedAt);",
                "var body=off?'Скрыто TKProtect':safe(x.text);",
                "var extra=x.deletedByStreamer?'удалено из чата (YouTube)':(x.bannedByStreamer?'нарушитель заблокирован':'')+(x.deleteError?'ошибка удаления':'')+(x.banError?'ошибка бана':'');",
                "var canMod=userMode==='streamer'&&streamerLinked&&streamerAction==='manual'&&x.verdict&&x.verdict.bad&&x.actionPending&&!x.deletedByStreamer&&x.id;",
                "var btn=canMod?'<div><button onclick=\"mod(\\''+escJs(safe(x.id))+'\\',\\''+escJs(safe(x.channelId))+'\\')\">Удалить'+(x.channelId?' / применить бан':'')+'</button></div>':'';",
                "var tail=reason||safe(extra);",
                "return '<div class=\"item'+(off?' hidden':'')+'\"><div class=meta><b>'+esc(author)+'</b>'+(date?'<span>'+esc(date)+'</span>':'')+'</div>'+",
                "'<div class=text'+(off?' masked':'')+'>'+esc(body)+'</div>'+(tail?'<div class=bad>'+esc(tail)+'</div>':'')+btn+'</div>'}).join('')}",
                "function refresh(){render(chat,$('chat'),$('force').checked);",
                "render(comments,$('comments'),$('force').checked);",
                "$('nChecked').textContent=chat.length+comments.length;",
                "$('nHidden').textContent=chat.filter(x=>x.hidden).length+comments.filter(x=>x.hidden).length}",
                "function merge(old,next){const seen=new Set(old.map(x=>x.id).filter(Boolean));",
                "const deduped=next.filter(x=>{if(x.id&&seen.has(x.id))return false;if(x.id)seen.add(x.id);return true});",
                "return deduped.length?old.concat(deduped).slice(-300):old}",
                "function applyStatus(d){if(!d)return; if(d.ready===undefined){if(d.error){$('status').textContent='Ошибка: '+d.error;log('Ошибка: '+d.error)} return;} ready=!!d.ready;if(d.streamerAction)streamerAction=d.streamerAction;if(d.userMode)userMode=d.userMode;streamerLinked=!!d.streamerLinked;",
                "const k=d.keyMask?(' · ключ '+d.keyMask):'';const v=d.apiVersion?(' · API '+d.apiVersion):'';",
                "const act=streamerAction==='manual'?'ручное подтверждение':'авто-удаление';",
                "const m=userMode==='streamer'?(streamerLinked?' · Стример: '+act:' · Стример: Google не привязан'):' · Зритель';",
                "$('status').textContent=ready?('TKProtect активен'+k+v+m):'TKCore ключ не сохранён (длина '+(d.keyLen||0)+') — долгий тап по TK, вставьте ключ и нажмите «Сохранить»';",
                "return k+v+m}",
                "window.tkpOnBootstrap=d=>{const s=applyStatus(d);if(s!==undefined)log(ready?('Готово'+s):'Ключ не сохранён')}",
                "window.tkpOnCheck=d=>{$('checkResult').className=d.bad?'bad':'muted';",
                "$('checkResult').textContent=(d.label||d.type||'')+(d.error?' — '+d.error:'')}",
                "window.tkpOnModerate=d=>{const id=d.id||'';const item=chat.find(x=>x.id===id);",
                "if(item){if(d.deleted)item.deletedByStreamer=true;if(d.banned)item.bannedByStreamer=true;",
                "if(d.deleteError)item.deleteError=d.deleteError;if(d.banError)item.banError=d.banError;",
                "if(d.ok||d.deleted||d.banned||d.deleteError||d.banError)item.actionPending=false;}",
                "log('Модерация '+id+': '+(d.ok?'выполнено':'ошибка')+(d.error?(' — '+d.error):''));refresh()}",
                "window.tkpOnVideo=d=>{const v=d.video||'';if(!/^[A-Za-z0-9_-]{11}$/.test(v))return;",
                "if(v!==videoId){$('video').value=v;connect(v);log('Видео из плеера: '+v)}}",
                "window.tkpOnChat=d=>{applyStatus(d);if(d.error==='no_active_live_chat'&&tab==='chat'){switchTab('comments');",
                "log('У видео нет живого чата — открыты комментарии')}",
                "if(d.error)log('Чат: '+d.error);const msgs=d&&Array.isArray(d.messages)?d.messages:[];",
                "chat=resetChat?msgs:merge(chat,msgs);resetChat=false;refresh()}",
                "window.tkpOnComments=d=>{applyStatus(d);const cmts=d&&Array.isArray(d.comments)?d.comments:[];",
                "if(resetComments||d.error)log('Комментарии: '+cmts.length+(d.hasContinuation?' (ещё...)':'')+(d.error?' · '+d.error:''));",
                "comments=resetComments?cmts:merge(comments,cmts);resetComments=false;refresh()}",
                "$('connect').onclick=()=>connect($('video').value.trim());",
                "function connect(v){if(!/^[A-Za-z0-9_-]{11}$/.test(v)){alert('Откройте видео или введите ID из 11 символов');return}",
                "videoId=v;$('video').value=v;chat=[];comments=[];resetChat=true;resetComments=true;",
                "refresh();log('Подключено к '+v);TK.bootstrap();poll(true)}",
                "$('video').onkeydown=e=>{if(e.key==='Enter')$('connect').click()}",
                "function switchTab(n){tab=n;document.querySelectorAll('.tabs button').forEach(x=>x.classList.toggle('on',x.dataset.tab===n));",
                "['chat','comments','tool'].forEach(x=>$('view-'+x).hidden=(x!==n));if(n==='comments'){if(!comments.length)poll(true)}else poll(false)}",
                "document.querySelectorAll('.tabs button').forEach(b=>b.onclick=()=>switchTab(b.dataset.tab))",
                "$('checkBtn').onclick=()=>{const t=$('check').value.trim();if(!t)return;",
                "$('checkResult').textContent='Проверка...';TK.check(t)}",
                "$('force').onchange=refresh;",
                "function poll(reset){if(!videoId)return;if(tab==='chat')TK.chat(videoId,!!reset);",
                "else if(tab==='comments'&&reset)TK.comments(videoId,true)}",
                "setInterval(()=>{if(videoId&&!document.hidden&&tab==='chat')poll(false)},5000);",
                "TK.bootstrap();",
                "const autoId=TK.getVideoId()||'';",
                "if(autoId){$('video').value=autoId;connect(autoId);log('ID видео взят из плеера')}",
                "else log('ID видео не найден — откройте видео или введите ID вручную');",
                "</script></body></html>"
        );
    }
}
