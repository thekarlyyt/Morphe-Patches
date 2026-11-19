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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final String PREFS_NAME = "tkprotect";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_TK_BASE = "tk_base";
    private static final String KEY_MODE = "tk_mode";
    private static final String KEY_VERSION = "tk_version";
    private static final String KEY_INNERTUBE_KEY = "innertube_key";
    private static final String KEY_CLIENT_VERSION = "client_version";

    private static final String DEF_TK_BASE =
            "https://thekarly.su/wp-content/plugins/tkcore/easyapi.php";
    private static final String DEF_MODE = "0";
    private static final String DEF_VERSION = "2.0";
    private static final String DEF_INNERTUBE_KEY =
            "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String DEF_CLIENT_VERSION = "2.20250312.04.00";

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, String> CHAT_CONTINUATIONS = new HashMap<>();
    private static final Map<String, String> COMMENT_CONTINUATIONS = new HashMap<>();
    private static final Map<String, Verdict> VERDICTS = new LinkedHashMap<>();

    private static Method addButtonMethod;
    private static WebView activeWeb;

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
            web.setBackgroundColor(0xFF101317);
            web.addJavascriptInterface(new Bridge(activity, web), "TK");
            activeWeb = web;

            new AlertDialog.Builder(activity)
                    .setTitle("TKProtect")
                    .setView(web)
                    .setPositiveButton("Закрыть", null)
                    .setOnDismissListener(dialog -> {
                        if (activeWeb == web) {
                            activeWeb = null;
                        }
                        web.destroy();
                    })
                    .show();

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

            final EditText keyInput = field(activity, box, "TKCore API key", pref(activity, KEY_API_KEY, ""));
            final EditText baseInput = field(activity, box, "TKCore URL", pref(activity, KEY_TK_BASE, DEF_TK_BASE));
            final EditText modeInput = field(activity, box, "Режим (phrase2)", pref(activity, KEY_MODE, DEF_MODE));
            final EditText versionInput = field(activity, box, "Версия API", pref(activity, KEY_VERSION, DEF_VERSION));
            final EditText innertubeInput =
                    field(activity, box, "YouTube API key", pref(activity, KEY_INNERTUBE_KEY, DEF_INNERTUBE_KEY));
            final EditText clientInput =
                    field(activity, box, "Версия клиента YouTube", pref(activity, KEY_CLIENT_VERSION, DEF_CLIENT_VERSION));

            TextView hint = new TextView(activity);
            hint.setText("Без TKCore API key проверка не работает. Ключ хранится только на этом устройстве.");
            hint.setPadding(0, dp(activity, 8), 0, 0);
            box.addView(hint);
            scroll.addView(box);

            new AlertDialog.Builder(activity)
                    .setTitle("TKProtect — настройки")
                    .setView(scroll)
                    .setPositiveButton("Сохранить", (dialog, which) -> {
                        savePref(activity, KEY_API_KEY, keyInput.getText().toString().trim());
                        savePref(activity, KEY_TK_BASE, baseInput.getText().toString().trim());
                        savePref(activity, KEY_MODE, modeInput.getText().toString().trim());
                        savePref(activity, KEY_VERSION, versionInput.getText().toString().trim());
                        savePref(activity, KEY_INNERTUBE_KEY, innertubeInput.getText().toString().trim());
                        savePref(activity, KEY_CLIENT_VERSION, clientInput.getText().toString().trim());
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
                    out.put("ready", !pref(context, KEY_API_KEY, "").isEmpty());
                    return out;
                }
            });
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
                        outMessages.put(item);
                    }
                    JSONObject out = new JSONObject();
                    out.put("video", videoInfo(context, videoId));
                    out.put("messages", outMessages);
                    out.put("error", page.optString("error", ""));
                    out.put("ready", !pref(context, KEY_API_KEY, "").isEmpty());
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
                    }
                    String continuation = COMMENT_CONTINUATIONS.get(videoId);
                    if (continuation == null) {
                        continuation = "";
                    }
                    JSONObject page = commentsPage(context, videoId, continuation);
                    String next = page.optString("continuation", "");
                    if (!next.isEmpty() && !next.equals(continuation)) {
                        COMMENT_CONTINUATIONS.put(videoId, next);
                    }
                    JSONArray source = page.optJSONArray("comments");
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
                    out.put("ready", !pref(context, KEY_API_KEY, "").isEmpty());
                    return out;
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
            try {
                if (activeWeb == web) {
                    web.evaluateJavascript(
                            "if(window." + name + ")" + name + "(" + JSONObject.quote(payload.toString()) + ");",
                            null);
                }
            } catch (RuntimeException ex) {
                Log.w(TAG, "Could not deliver JS callback", ex);
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
                return "Капс / флуд";
            case "scam":
                return "Скам";
            case "blacklist":
                return "Ссылка в чёрном списке";
            case "pii":
                return "Персональные данные";
            case "error":
                return "Ошибка проверки";
            default:
                return "Неизвестный ответ: " + type;
        }
    }

    private static boolean isBadVerdict(String type) {
        return !("allowed".equals(type) || "whitelist".equals(type) || "scanner".equals(type)
                || "error".equals(type) || "unknown".equals(type));
    }

    private static Verdict normalizeVerdict(String raw) {
        String type;
        switch (raw) {
            case "-1":
            case "error":
                type = "error";
                break;
            case "0":
            case "allowed":
                type = "allowed";
                break;
            case "1":
            case "obscene":
                type = "obscene";
                break;
            case "2":
            case "phishing":
                type = "phishing";
                break;
            case "3":
            case "caps_flood":
                type = "caps_flood";
                break;
            case "4":
            case "pii":
                type = "pii";
                break;
            case "5":
            case "scam":
                type = "scam";
                break;
            default:
                type = "unknown";
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
                return cached;
            }
        } catch (Exception ignored) {
            // ignored
        }
        Verdict verdict = checkText(context, text);
        if (key != null) {
            if (VERDICTS.size() > 300) {
                VERDICTS.clear();
            }
            VERDICTS.put(key, verdict);
        }
        return verdict;
    }

    private static Verdict checkText(Context context, String text) {
        String apiKey = pref(context, KEY_API_KEY, "");
        if (apiKey.isEmpty()) {
            return new Verdict("error", "Нет API key — откройте настройки", false);
        }
        try {
            String base = pref(context, KEY_TK_BASE, DEF_TK_BASE);
            String query = "api_key=" + enc(apiKey)
                    + "&type=botmoderator"
                    + "&phrase=" + enc(text)
                    + "&phrase2=" + enc(pref(context, KEY_MODE, DEF_MODE))
                    + "&version=" + enc(pref(context, KEY_VERSION, DEF_VERSION));
            HttpResponse response = request(base + "?" + query, "GET", null);
            JSONObject parsed = new JSONObject(response.body);
            String raw = parsed.has("type") ? String.valueOf(parsed.opt("type"))
                    : (parsed.has("error") ? "error" : "unknown");
            return normalizeVerdict(raw);
        } catch (Exception ex) {
            Log.w(TAG, "TKCore check failed", ex);
            return new Verdict("error", "Ошибка проверки: " + ex.getMessage(), false);
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
        if (value instanceof String || value instanceof Number) {
            return String.valueOf(value);
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

    private static JSONArray extractComments(Object node) {
        JSONArray out = new JSONArray();
        for (Object value : walk(node, "commentRenderer")) {
            if (!(value instanceof JSONObject)) {
                continue;
            }
            JSONObject item = (JSONObject) value;
            String id = item.optString("commentId", "");
            String text = textOf(item.opt("contentText"));
            if (id.isEmpty() && text.isEmpty()) {
                continue;
            }
            try {
                JSONObject row = new JSONObject();
                row.put("id", id);
                row.put("text", text);
                row.put("author", textOf(item.opt("authorText")));
                row.put("publishedAt", textOf(item.opt("publishedTimeText")));
                out.put(row);
            } catch (Exception ignored) {
                // ignored
            }
        }
        return out;
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
        List<Object> tokens = new ArrayList<>();
        for (Object value : walk(pool, "continuationItemRenderer")) {
            if (value instanceof JSONObject) {
                Object endpoint = ((JSONObject) value).opt("continuationEndpoint");
                Object command = endpoint instanceof JSONObject
                        ? ((JSONObject) endpoint).opt("continuationCommand") : null;
                if (command instanceof JSONObject) {
                    String token = ((JSONObject) command).optString("token", "");
                    if (!token.isEmpty()) {
                        tokens.add(token);
                    }
                }
            }
        }
        return tokens.isEmpty() ? "" : String.valueOf(tokens.get(tokens.size() - 1));
    }

    private static JSONObject commentsPage(Context context, String videoId, String continuation)
            throws Exception {
        JSONObject extra = continuation.isEmpty()
                ? new JSONObject().put("videoId", videoId)
                : new JSONObject().put("continuation", continuation);
        JSONObject response = innertube(context, "next", extra);
        JSONObject out = new JSONObject();
        out.put("comments", extractComments(response));
        out.put("continuation", commentsContinuation(response));
        out.put("error", "");
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // HTTP and preferences
    // ---------------------------------------------------------------------------------------

    private static final class HttpResponse {
        final String body;

        HttpResponse(String body) {
            this.body = body;
        }
    }

    private static HttpResponse request(String url, String method, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setDoOutput(body != null);
            connection.setRequestProperty("User-Agent", "TKProtect/1.0");
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
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
                return new HttpResponse("");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            stream.close();
            if (code < 200 || code >= 400) {
                throw new IllegalStateException("HTTP " + code + ": " + out.toString("UTF-8"));
            }
            return new HttpResponse(out.toString("UTF-8"));
        } finally {
            connection.disconnect();
        }
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static String pref(Context context, String key, String fallback) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(key, fallback);
    }

    private static void savePref(Context context, String key, String value) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
                "<div class=row><input id=video maxlength=11 placeholder='ID видео (11 символов)'>",
                "<button id=connect>Подключиться</button></div>",
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
                "<label class=muted><input type=checkbox id=force style='width:auto'> Показывать скрытые</label>",
                "<div class=log id=log></div></div>",
                "<script>",
                "let tab='chat',videoId='',chat=[],comments=[],ready=false,resetChat=true,resetComments=true;",
                "const $=id=>document.getElementById(id);",
                "function log(m){const l=$('log');l.innerHTML='<div>'+new Date().toLocaleTimeString()+' — '+m+'</div>'+l.innerHTML}",
                "function esc(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;')",
                ".replace(/>/g,'&gt;').replace(/\"/g,'&quot;')}",
                "function render(list,el,hide){if(!list.length){el.innerHTML='<p class=empty>Пусто.</p>';return}",
                "el.innerHTML=list.map(x=>{const off=x.hidden&&!hide;",
                "return '<div class=\"item'+(off?' hidden':'')+'\"><div class=meta><b>'+esc(x.author||'Аноним')+'</b>',",
                "+(x.publishedAt?'<span>'+esc(x.publishedAt)+'</span>':'')+'</div><div class=text'+(off?' masked':'')+'>'",
                "+(off?'Скрыто TKProtect':esc(x.text))+'</div>'+(x.verdict&&!off&&x.verdict.bad?",
                "'<div class=bad>'+esc(x.verdict.label)+'</div>':'')+'</div>'}).join('')}",
                "function refresh(){render(chat,$('chat'),$('force').checked);",
                "render(comments,$('comments'),$('force').checked);",
                "$('nChecked').textContent=chat.length+comments.length;",
                "$('nHidden').textContent=chat.filter(x=>x.hidden).length+comments.filter(x=>x.hidden).length}",
                "function merge(old,next){const seen=new Set(old.map(x=>x.id).filter(Boolean));",
                "return old.concat(next.filter(x=>{if(x.id&&seen.has(x.id))return false;if(x.id)seen.add(x.id);return true})).slice(-300)}",
                "window.tkpOnBootstrap=d=>{ready=!!d.ready;$('status').textContent=ready?",
                "'TKProtect активен · ключ задан':'Нет TKCore API key — долгий тап по TK для настроек';",
                "log(ready?'Готово':'Нет ключа')}",
                "window.tkpOnCheck=d=>{$('checkResult').className=d.bad?'bad':'muted';",
                "$('checkResult').textContent=(d.label||d.type||'')+(d.error?' — '+d.error:'')}",
                "window.tkpOnChat=d=>{if(d.error)log('Чат: '+d.error);chat=resetChat?d.messages:merge(chat,d.messages);",
                "resetChat=false;refresh()}",
                "window.tkpOnComments=d=>{if(d.error)log('Комментарии: '+d.error);comments=resetComments?d.comments:merge(comments,d.comments);",
                "resetComments=false;refresh()}",
                "$('connect').onclick=()=>{const v=$('video').value.trim();if(!/^[A-Za-z0-9_-]{11}$/.test(v)){alert('Нужен ID из 11 символов');return}",
                "videoId=v;chat=[];comments=[];resetChat=true;resetComments=true;refresh();log('Подключено к '+v);poll(true)}",
                "$('video').onkeydown=e=>{if(e.key==='Enter')$('connect').click()}",
                "document.querySelectorAll('.tabs button').forEach(b=>b.onclick=()=>{",
                "document.querySelectorAll('.tabs button').forEach(x=>x.classList.toggle('on',x===b));tab=b.dataset.tab;",
                "['chat','comments','tool'].forEach(n=>$('view-'+n).hidden=(n!==tab))})",
                "$('checkBtn').onclick=()=>{const t=$('check').value.trim();if(!t)return;",
                "$('checkResult').textContent='Проверка...';TK.check(t)}",
                "$('force').onchange=refresh;",
                "function poll(reset){if(!videoId)return;if(tab==='chat')TK.chat(videoId,!!reset);",
                "else if(tab==='comments')TK.comments(videoId,!!reset)}",
                "setInterval(()=>{if(videoId&&!document.hidden)poll(false)},5000);",
                "TK.bootstrap();",
                "</script></body></html>"
        );
    }
}
