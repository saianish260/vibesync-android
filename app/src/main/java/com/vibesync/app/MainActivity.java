package com.vibesync.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.text.InputType;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VibeSync: one full-screen WebView that loads the deployed Streamlit app.
 *
 * The URL comes from the build (BuildConfig.VIBESYNC_URL) if one was given;
 * otherwise the app asks for it on first launch and remembers it. It can be
 * changed later from the "Change URL" link on the offline screen.
 *
 * No AndroidX dependencies, so the only permission in the final APK is INTERNET.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "vibesync";
    private static final String KEY_URL = "app_url";
    private static final String SETTINGS_SCHEME = "vibesync";

    /** OTT website host -> Android app package (must match <queries> in AndroidManifest.xml). */
    private static final Map<String, String[]> APPS = new LinkedHashMap<>();
    static {
        APPS.put("netflix.com", new String[]{"com.netflix.mediaclient", "Netflix"});
        APPS.put("primevideo.com", new String[]{"com.amazon.avod.thirdpartyclient", "Prime Video"});
        APPS.put("hotstar.com", new String[]{"in.startv.hotstar", "JioHotstar"});
        APPS.put("jiohotstar.com", new String[]{"in.startv.hotstar", "JioHotstar"});
        APPS.put("crunchyroll.com", new String[]{"com.crunchyroll.crunchyroid", "Crunchyroll"});
        APPS.put("sonyliv.com", new String[]{"com.sonyliv", "Sony LIV"});
        APPS.put("zee5.com", new String[]{"com.graymatrix.did", "ZEE5"});
        APPS.put("fancode.com", new String[]{"com.dream11sportsguru", "FanCode"});
        APPS.put("aha.video", new String[]{"ahaflix.tv", "aha"});
        APPS.put("youtube.com", new String[]{"com.google.android.youtube", "YouTube"});
        APPS.put("youtu.be", new String[]{"com.google.android.youtube", "YouTube"});
    }

    private String appUrl = "";
    /** True while the WebView shows a TMDB / JustWatch watch page on the way to an OTT title. */
    private boolean inWatchFlow = false;
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(getColor(R.color.vs_background));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);        // Streamlit's UI is JavaScript
        s.setDomStorageEnabled(true);        // Streamlit uses localStorage/sessionStorage
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);   // route target=_blank links to onCreateWindow
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(s.getUserAgentString() + " VibeSyncApp/" + BuildConfig.VERSION_NAME);  // lets the page know links are handled natively

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (SETTINGS_SCHEME.equals(uri.getScheme())) {
                    askForUrl();
                    return true;
                }
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                if (!"intent".equals(scheme) && isAppUrl(uri)) { inWatchFlow = false; return false; }
                // TMDB / JustWatch watch pages load inside VibeSync; the provider link they lead to
                // (often via a redirect) is caught below and opened in that app at the exact title.
                if (isWatchHost(uri.getHost())) { inWatchFlow = true; return false; }
                if (inWatchFlow && appFor(uri.getHost()) == null && (scheme.equals("https") || scheme.equals("http"))) return false;
                openExternally(uri);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (url != null && isAppUrl(Uri.parse(url))) inWatchFlow = false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showOffline();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // Trailer / "Watch on" links open in YouTube, JioHotstar, etc.
                WebView catcher = new WebView(MainActivity.this);
                catcher.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        openExternally(r.getUrl());
                        v.destroy();
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(catcher);
                resultMsg.sendToTarget();
                return true;
            }
        });

        setContentView(web);

        appUrl = prefs().getString(KEY_URL, BuildConfig.VIBESYNC_URL);
        if (savedInstanceState != null && !appUrl.isEmpty()) {
            web.restoreState(savedInstanceState);
        } else if (appUrl.isEmpty()) {
            showWelcome();
            askForUrl();
        } else {
            web.loadUrl(appUrl);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** Dialog to enter or change the VibeSync URL. */
    private void askForUrl() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://vibesync-you.streamlit.app");
        input.setText(appUrl);
        input.setSingleLine(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new AlertDialog.Builder(this)
                .setTitle("VibeSync server URL")
                .setMessage("Paste your deployed VibeSync link (Streamlit Cloud), or http://<laptop-ip>:8501 on the same Wi-Fi.")
                .setView(input)
                .setCancelable(!appUrl.isEmpty())
                .setPositiveButton("Open", (d, w) -> {
                    String url = normalize(input.getText().toString());
                    if (url.isEmpty()) {
                        Toast.makeText(this, "Enter a link to continue", Toast.LENGTH_SHORT).show();
                        askForUrl();
                        return;
                    }
                    appUrl = url;
                    prefs().edit().putString(KEY_URL, url).apply();
                    web.loadUrl(url);
                })
                .show();
    }

    private static String normalize(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty() || url.contains(" ")) return "";
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        return url;
    }

    private boolean isAppUrl(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        String appHost = Uri.parse(appUrl).getHost();
        return host.equals(appHost) || host.endsWith(".streamlit.app") || host.endsWith("streamlit.io");
    }

    /**
     * Links leaving VibeSync:
     *  - OTT link + app installed  -> open that exact title inside the app
     *  - OTT link + app missing    -> open the app's Play Store page
     *  - anything else             -> default handler (browser)
     */
    private void openExternally(Uri uri) {
        if (isWatchHost(uri.getHost())) {                 // watch page: show it in VibeSync, Back returns
            inWatchFlow = true;
            web.loadUrl(uri.toString());
            return;
        }
        if ("intent".equals(uri.getScheme())) {           // intent:// links (from Chrome-style pages)
            try {
                Intent i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                String pkg = i.getPackage();
                if (pkg != null && !isInstalled(pkg)) { openPlayStore(pkg, null); return; }
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                startActivity(i);
            } catch (URISyntaxException | ActivityNotFoundException e) {
                Toast.makeText(this, "Couldn't open that link", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        uri = normalizeOttLink(uri);
        String[] app = appFor(uri.getHost());
        if (app != null) {
            String pkg = app[0], name = app[1];
            if (isInstalled(pkg)) {
                if ("in.startv.hotstar".equals(pkg)) {
                    // JioHotstar's app ignores web links; it opens titles through hotstar://<content id>
                    String id = hotstarId(uri);
                    if (id == null) {                      // no title in the link (e.g. home page): open the app
                        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                        if (launch != null) { startActivity(launch); return; }
                    } else {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("hotstar://" + id)).setPackage(pkg));
                            return;
                        } catch (ActivityNotFoundException ignored) { /* fall through to the web link */ }
                    }
                }
                Intent i = new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addCategory(Intent.CATEGORY_BROWSABLE);
                try {
                    startActivity(i);                      // deep link straight to the title
                    return;
                } catch (ActivityNotFoundException ignored) {
                    // The app doesn't accept this link. Open the exact title page in the browser
                    // instead of dropping the person on the app's home screen.
                    Toast.makeText(this, name + " can't open this link directly. Opening the title page", Toast.LENGTH_SHORT).show();
                }
            } else if (!"com.google.android.youtube".equals(pkg)) {
                openPlayStore(pkg, name);                  // not installed: send them to the Play Store
                return;
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show();
        }
    }

    /** Prime Video's app only accepts app.primevideo.com links (www.primevideo.com opens the browser). */
    private static Uri normalizeOttLink(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (host.equals("primevideo.com") || host.equals("www.primevideo.com")) {
            return uri.buildUpon().authority("app.primevideo.com").build();
        }
        return uri;
    }

    /**
     * Content id from a Hotstar link: the last long number in the path, e.g.
     * /in/movies/interstellar/1971000531, /in/1271461642, /in/shows/x/123/episode-y/456 (episode wins).
     */
    static String hotstarId(Uri uri) {
        String path = uri.getPath();
        if (path == null) return null;
        String found = null;
        for (String seg : path.split("/")) {
            if (seg.length() >= 4 && seg.matches("\\d+")) found = seg;
        }
        return found;
    }

    private static boolean isWatchHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase();
        return host.equals("themoviedb.org") || host.endsWith(".themoviedb.org")
                || host.equals("justwatch.com") || host.endsWith(".justwatch.com");
    }

    private static String[] appFor(String host) {
        if (host == null) return null;
        host = host.toLowerCase();
        for (Map.Entry<String, String[]> e : APPS.entrySet()) {
            String k = e.getKey();
            if (host.equals(k) || host.endsWith("." + k)) return e.getValue();
        }
        return null;
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPlayStore(String pkg, String name) {
        if (name != null) Toast.makeText(this, name + " isn't installed. Opening Play Store", Toast.LENGTH_SHORT).show();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg))
                    .setPackage("com.android.vending"));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
        }
    }

    private void showPage(String emoji, String message, String buttonsHtml) {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:radial-gradient(90% 55% at 50% 0%,rgba(255,181,71,.28),transparent 70%),#0D0A12;"
                + "color:#F6F2EC;font-family:sans-serif;text-align:center;padding:24px;box-sizing:border-box'><div>"
                + "<div style='font-size:56px'>" + emoji + "</div>"
                + "<h1 style='font-size:34px;font-weight:900;letter-spacing:-.03em;margin:14px 0 8px'>Vibe<span style='color:#FFB547'>Sync</span></h1>"
                + "<p style='color:#A69EB0;max-width:290px;margin:0 auto 26px;line-height:1.5'>" + message + "</p>"
                + buttonsHtml + "</div></body></html>";
        web.loadDataWithBaseURL(appUrl.isEmpty() ? null : appUrl, html, "text/html", "utf-8", null);
    }

    private static String button(String href, String label, boolean primary) {
        return "<a href='" + href + "' style='display:inline-block;margin:6px;padding:15px 26px;border-radius:999px;"
                + (primary ? "background:#FFB547;color:#1A1208;" : "background:#221C2B;color:#F6F2EC;")
                + "font-weight:bold;text-decoration:none;'>" + label + "</a>";
    }

    private void showWelcome() {
        showPage("&#127871;", "Connect to your VibeSync server to get started.",
                button(SETTINGS_SCHEME + "://settings", "Set server URL", true));
    }

    private void showOffline() {
        showPage("&#128225;", "Can't reach the VibeSync server. Check your internet or wake the app, then try again.",
                button(appUrl, "Try again", true) + "<br>"
                        + button(SETTINGS_SCHEME + "://settings", "Change URL", false));
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();            // back inside the app first
        } else {
            super.onBackPressed();   // then close
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
