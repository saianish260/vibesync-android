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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VibeSync: one full-screen WebView that loads the deployed Streamlit app.
 *
 * The URL comes from the build (BuildConfig.VIBESYNC_URL) if one was given;
 * otherwise the app asks for it on first launch and remembers it. It can be
 * changed later from the "Change URL" link on the offline screen.
 *
 * Only AndroidX dependency: Credential Manager, for Google sign-in. Permissions: INTERNET, plus POST_NOTIFICATIONS and RECEIVE_BOOT_COMPLETED
 * for new-episode reminders (see Bridge, ReminderReceiver).
 */
public class MainActivity extends Activity {

    private static final String PREFS = "vibesync";
    private static final String KEY_URL = "app_url";
    private static final String SETTINGS_SCHEME = "vibesync";
    /** Widget / notification taps: the VibeSync title id to open (the page picks it up through the bridge). */
    static final String EXTRA_OPEN = "vs_open";

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
        APPS.put("tv.apple.com", new String[]{"com.apple.atve.androidtv.appletv", "Apple TV"});
        APPS.put("youtube.com", new String[]{"com.google.android.youtube", "YouTube"});
        APPS.put("youtu.be", new String[]{"com.google.android.youtube", "YouTube"});
        // movie tickets
        APPS.put("sunnxt.com", new String[]{"com.suntv.sunnxt", "Sun NXT"});
        APPS.put("in.bookmyshow.com", new String[]{"com.bt.bms", "BookMyShow"});
        APPS.put("district.in", new String[]{"com.application.zomato.district", "District"});
    }
    /** Apps whose website works fine when the app isn't installed (no Play Store detour). */
    private static final java.util.Set<String> WEB_OK = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.google.android.youtube", "com.bt.bms", "com.application.zomato.district"));

    /** On the TV the phone apps are replaced by their TV editions (same links, different package). */
    private static final Map<String, String> TV_PKG = new LinkedHashMap<>();
    static {
        TV_PKG.put("com.netflix.mediaclient", "com.netflix.ninja");
        TV_PKG.put("com.amazon.avod.thirdpartyclient", "com.amazon.amazonvideo.livingroom");
        TV_PKG.put("com.google.android.youtube", "com.google.android.youtube.tv");
        TV_PKG.put("in.startv.hotstar", "in.startv.hotstar.dplus.tv");      // JioHotstar's TV app, where it's separate
    }
    /** VibeSync TV's remote navigation and big-screen styles (assets/tv.js, tv.css), added to the page. */
    private String tvInject = "";

    private String appUrl = "";
    /** True while the WebView shows a TMDB / JustWatch watch page on the way to an OTT title. */
    private boolean inWatchFlow = false;
    private WebView web;
    /** The page in the main frame; the JS bridge only answers while it's the VibeSync site. */
    private volatile String mainUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        tvInject = buildTvInject();
        web = new WebView(this);
        web.setBackgroundColor(getColor(R.color.vs_background));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);        // Streamlit's UI is JavaScript
        s.setDomStorageEnabled(true);        // Streamlit uses localStorage/sessionStorage
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);   // route target=_blank links to onCreateWindow
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(s.getUserAgentString() + " VibeSyncApp/" + BuildConfig.VERSION_NAME + " VibeSyncTV");  // lets the page know links are handled natively
        web.addJavascriptInterface(new Bridge(this, () -> isAppUrl(Uri.parse(mainUrl))), "VibeSyncApp");

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
                if (isWatchHost(uri.getHost()) && resolveWatchPage(uri)) return true;
                if (isWatchHost(uri.getHost())) { inWatchFlow = true; return false; }
                if (inWatchFlow && appFor(uri.getHost()) == null && (scheme.equals("https") || scheme.equals("http"))) return false;
                openExternally(uri);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (url != null) mainUrl = url;
                if (url != null && isAppUrl(Uri.parse(url))) inWatchFlow = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && isAppUrl(Uri.parse(url))) {
                    view.evaluateJavascript(HIDE_HOST_BADGES, null);
                    view.evaluateJavascript(tvInject, null);
                }
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

        takeOpenIntent(getIntent());
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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeOpenIntent(intent);          // VibeSync is already open: the page notices and opens the title
    }

    private void takeOpenIntent(Intent intent) {
        String id = intent == null ? null : intent.getStringExtra(EXTRA_OPEN);
        if (id != null && !id.isEmpty())
            getSharedPreferences(Bridge.PREFS, MODE_PRIVATE).edit().putString("open", id).apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Updater.maybeCheck(this);                      // offers a newer VibeSync TV in a popup (every few hours)
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

    /**
     * Streamlit Community Cloud pins its hosting badges (crown + profile avatar) to the bottom-right of
     * the page, on top of VibeSync's own tab bar. Inside the app we hide anything small that is pinned to
     * that corner of the outer page (never the app's iframe itself), and keep doing so as it re-renders.
     */
    private static final String HIDE_HOST_BADGES = "(function(){if(window.__vsBadges)return;window.__vsBadges=1;"
            + "var st=document.createElement('style');st.textContent='[class*=\"viewerBadge\"],[class*=\"profileContainer\"]{display:none!important}';"
            + "(document.head||document.documentElement).appendChild(st);"
            + "function sweep(){var w=innerWidth,h=innerHeight;var all=document.body?document.body.querySelectorAll('*'):[];"
            + "for(var i=0;i<all.length;i++){var e=all[i];if(e.tagName==='IFRAME'||e.querySelector('iframe'))continue;"
            + "var p=getComputedStyle(e).position;if(p!=='fixed'&&p!=='absolute')continue;var r=e.getBoundingClientRect();"
            + "if(r.width>0&&r.width<w*0.6&&r.height<h*0.3&&r.bottom>h-160&&r.left>w*0.3)e.style.setProperty('display','none','important');}}"
            + "sweep();var n=0,t=setInterval(function(){sweep();if(++n>40)clearInterval(t);},500);"
            + "if(document.body)new MutationObserver(function(){clearTimeout(window.__vsT);window.__vsT=setTimeout(sweep,150);})"
            + ".observe(document.body,{childList:true,subtree:true});})();";

    private String asset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Finds VibeSync's page inside Streamlit's frames (same site): the window that holds #app and #bnav. */
    private static final String FIND_VS = "function vsW(w,d){if(d>5)return null;try{var doc=w.document;"
            + "if(doc.getElementById('app')&&doc.getElementById('bnav'))return w;var f=doc.querySelectorAll('iframe');"
            + "for(var i=0;i<f.length;i++){var r=vsW(f[i].contentWindow,d+1);if(r)return r;}}catch(e){}return null}";

    /**
     * Keeps checking for VibeSync's page (Streamlit can rebuild its frame); whenever it's there without the TV
     * layer, adds tv.css + tv.js to it, then moves the remote's focus into that frame.
     */
    private String buildTvInject() {
        String css = org.json.JSONObject.quote(asset("tv.css")), js = org.json.JSONObject.quote(asset("qr.js") + "\n" + asset("tv.js"));
        return "(function(){if(window.__vsTvLoop)return;window.__vsTvLoop=1;" + FIND_VS
                + "function focusIn(w){var x=w,ch=[];while(x&&x!==window){try{var fe=x.frameElement;if(!fe)break;ch.unshift(fe);x=x.parent;}catch(e){break}}"
                + "ch.forEach(function(f){try{f.focus()}catch(e){}});try{w.vsFocus&&w.vsFocus()}catch(e){}}"
                + "setInterval(function(){var w=vsW(window,0);if(!w||w.__vsTV)return;var d=w.document;"
                + "var st=d.createElement('style');st.textContent=" + css + ";d.head.appendChild(st);"
                + "var sc=d.createElement('script');sc.textContent=" + js + ";d.head.appendChild(sc);"
                + "setTimeout(function(){focusIn(w)},300);},800);})();";
    }

    /** Back on the remote: close VibeSync's top-most panel; "n" when there was nothing to close. */
    private static final String TV_BACK = "(function(){" + FIND_VS + "var w=vsW(window,0);try{return w&&w.vsBack&&w.vsBack()?'y':'n'}catch(e){return 'n'}})()";

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
        if (resolveBookLink(uri)) return;
        if (isWatchHost(uri.getHost()) && resolveWatchPage(uri)) return;
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
        if (primeSearchLink(uri)) { primeFromSearch(uri); return; }
        if (primeNeedsGti(uri)) { primeViaGti(uri); return; }
        uri = normalizeOttLink(uri);
        String[] app = appFor(uri.getHost());
        if (app != null) {
            String pkg = app[0], name = app[1];
            if (TV_PKG.containsKey(pkg) && (!isInstalled(pkg) || isInstalled(TV_PKG.get(pkg)))) pkg = TV_PKG.get(pkg);
            if (isInstalled(pkg)) {
                if (pkg.startsWith("in.startv.hotstar")) {
                    // JioHotstar: its official title page (https://www.hotstar.com/in/movies/<name>/<id>) first, then
                    // its share-link format (https://jiohotstar.com/<id>), then the old content-id scheme.
                    String id = hotstarId(uri);
                    if (id == null) {                      // no title in the link (e.g. home page): open the app
                        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
                        if (launch != null) { startActivity(ott(launch)); return; }
                    } else {
                        Uri official = hotstarOfficial(uri);
                        if (official != null) {
                            try {
                                startActivity(ott(new Intent(Intent.ACTION_VIEW, official).setPackage(pkg).addCategory(Intent.CATEGORY_BROWSABLE)));
                                return;
                            } catch (ActivityNotFoundException ignored) { /* try the share link */ }
                        }
                        try {
                            startActivity(ott(new Intent(Intent.ACTION_VIEW, Uri.parse("https://jiohotstar.com/" + id + "?utm_source=share"))
                                    .setPackage(pkg).addCategory(Intent.CATEGORY_BROWSABLE)));
                            return;
                        } catch (ActivityNotFoundException e) {
                            try {
                                startActivity(ott(new Intent(Intent.ACTION_VIEW, Uri.parse("hotstar://" + id)).setPackage(pkg)));
                                return;
                            } catch (ActivityNotFoundException ignored) { /* fall through to the web link */ }
                        }
                    }
                }
                Intent i = new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addCategory(Intent.CATEGORY_BROWSABLE);
                try {
                    startActivity(ott(i));                 // deep link straight to the title
                    return;
                } catch (ActivityNotFoundException ignored) {
                    // TVs rarely have a browser: open the app itself so the title is one search away
                    Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                    if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launch != null) {
                        Toast.makeText(this, "Opening " + name + ". Find the title there", Toast.LENGTH_LONG).show();
                        startActivity(ott(launch));
                        return;
                    }
                    // The app doesn't accept this link. Open the exact title page in the browser
                    // instead of dropping the person on the app's home screen.
                    Toast.makeText(this, name + " can't open this link directly. Opening the title page", Toast.LENGTH_SHORT).show();
                }
            } else if (!WEB_OK.contains(pkg)) {
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

    /*
     * Prime Video's app opens a specific title only from app.primevideo.com/detail?gti=<title id>. Links like
     * primevideo.com/detail/0ABC… (what most listings give) open the app's home screen instead. So for those,
     * VibeSync reads the title's Prime page once (on the phone), takes its gti, and opens the app with that:
     * the app shows the profile picker, then the title. Found gti's are remembered.
     */
    private static final Pattern GTI = Pattern.compile("amzn1\\.dv\\.gti\\.[0-9a-fA-F-]{36}");

    private static boolean primeNeedsGti(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (!(host.equals("primevideo.com") || host.endsWith(".primevideo.com"))) return false;
        if (uri.getQueryParameter("vs_nogti") != null) return false;      // already tried
        String gti = uri.getQueryParameter("gti");
        return (gti == null || gti.isEmpty()) && uri.getPath() != null && uri.getPath().contains("/detail");
    }

    private void primeViaGti(final Uri uri) {
        primeResolve(uri.buildUpon().authority("www.primevideo.com").build(), "gti:" + uri.getPath(),
                uri.buildUpon().authority("app.primevideo.com").appendQueryParameter("vs_nogti", "1").build());
    }

    /** A Prime search link (no exact link was known): search Prime's website for the title, open the top match. */
    private static boolean primeSearchLink(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (!(host.equals("primevideo.com") || host.endsWith(".primevideo.com"))) return false;
        if (uri.getQueryParameter("vs_nogti") != null) return false;
        String q = uri.getQueryParameter("phrase");
        return q != null && !q.trim().isEmpty() && uri.getPath() != null && uri.getPath().contains("/search");
    }

    private void primeFromSearch(final Uri uri) {
        String q = uri.getQueryParameter("phrase");
        primeResolve(Uri.parse("https://www.primevideo.com/search/ref=atv_nb_sr?phrase=" + Uri.encode(q)), "q:" + q.trim().toLowerCase(),
                uri.buildUpon().authority("app.primevideo.com").appendQueryParameter("vs_nogti", "1").build());
    }

    /** Read a Prime page on the phone, take the first title id (gti) on it and open that title in the app. */
    private void primeResolve(final Uri page, final String cacheKey, final Uri fallback) {
        final SharedPreferences prefs = getSharedPreferences("vibesync_links", MODE_PRIVATE);
        String known = prefs.getString(cacheKey, "");
        if (!known.isEmpty()) { openExternally(Uri.parse("https://app.primevideo.com/detail?gti=" + known)); return; }
        Toast.makeText(this, "Opening in Prime Video…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String gti = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(page.toString()).openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(8000); c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36");
                c.setRequestProperty("Accept-Language", "en-IN,en;q=0.8");
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) > 0 && b.size() < 3_000_000) b.write(buf, 0, n);
                    Matcher m = GTI.matcher(b.toString("UTF-8"));
                    if (m.find()) gti = m.group();
                }
            } catch (Exception ignored) { }
            final String found = gti;
            runOnUiThread(() -> {
                if (found != null) {
                    prefs.edit().putString(cacheKey, found).apply();
                    openExternally(Uri.parse("https://app.primevideo.com/detail?gti=" + found));
                } else openExternally(fallback);           // couldn't find it: still opens Prime Video
            });
        }).start();
    }

    /** Prime Video's app only accepts app.primevideo.com links (www.primevideo.com opens the browser).
     *  SonyLIV: its official title page, www.sonyliv.com/movies/<name>-<id>. */
    private static Uri normalizeOttLink(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (host.equals("sonyliv.com") || host.endsWith(".sonyliv.com")) return sonyShareLink(uri);
        if (host.equals("primevideo.com") || host.equals("www.primevideo.com")) {
            return uri.buildUpon().authority("app.primevideo.com").build();
        }
        return uri;
    }

    /**
     * Content id from a Hotstar link: the last long number in the path, e.g.
     * /in/movies/interstellar/1971000531, /in/1271461642, /in/shows/x/123/episode-y/456 (episode wins).
     */
    static Uri sonyShareLink(Uri uri) {
        // SonyLIV's official title page: https://www.sonyliv.com/movies/<name>-<id> (or /shows/…). Older
        // VibeSync links in its share format (/movie-bundle/<name>-<id>?utm_…) are turned back into it.
        java.util.List<String> segs = uri.getPathSegments();
        if (segs == null || segs.isEmpty()) return uri;
        String last = segs.get(segs.size() - 1);
        if (!last.matches(".*-\\d{6,}$")) return uri;                          // no title id: leave it alone
        String first = segs.get(0).toLowerCase();
        String path = first.startsWith("movie") ? "/movies/" + Uri.encode(last.replace(":", ""), "-_.") : uri.getEncodedPath();
        return new Uri.Builder().scheme("https").authority("www.sonyliv.com").encodedPath(path).build();
    }

    /**
     * An OTT app opened from VibeSync starts in its own task, the way a link tapped in Chrome or WhatsApp opens it.
     * Started inside VibeSync's task instead, apps that ask "Who's watching?" (JioHotstar, SonyLIV) lost the title
     * after the profile was picked and showed their home page.
     */
    private static Intent ott(Intent i) {
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** JioHotstar's official title page, https://www.hotstar.com/in/movies|shows/<name>/<id> (null if the link isn't one). */
    static Uri hotstarOfficial(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (!host.endsWith("hotstar.com")) return null;
        String path = uri.getEncodedPath();
        if (path == null || !path.matches("/in/(movies|shows|tv)/[^/]+/\\d{4,}(/.*)?")) return null;
        return new Uri.Builder().scheme("https").authority("www.hotstar.com").encodedPath(path).build();
    }

    static String hotstarId(Uri uri) {
        String path = uri.getPath();
        if (path == null) return null;
        String found = null;
        for (String seg : path.split("/")) {
            if (seg.length() >= 4 && seg.matches("\\d+")) found = seg;
        }
        return found;
    }

    /*
     * A live title's "Watch on <app>" button points at its TMDB watch page with ?vs_app=<app name>.
     * Instead of showing that page, read it in the background (from this phone, like the WebView would),
     * take the JustWatch link for that app, and open its real destination: the exact title in the app.
     * If anything goes wrong, show the watch page as before.
     */
    private static final Pattern A_TAG = Pattern.compile("<a\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z_:-]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern IMG_ALT = Pattern.compile("<img\\b[^>]*\\balt=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private boolean resolveWatchPage(Uri uri) {
        final String want = uri.getQueryParameter("vs_app");
        if (want == null || want.isEmpty()) return false;
        final String page = uri.toString().replaceAll("[&?]vs_app=[^&#]*", "").replaceFirst("#.*$", "");
        Toast.makeText(this, "Opening " + want + "…", Toast.LENGTH_SHORT).show();
        final String ua = web.getSettings().getUserAgentString();
        new Thread(() -> {
            String dest = null;
            try { dest = findDestination(download(page, ua), norm(want)); } catch (Exception ignored) { }
            final String found = dest;
            runOnUiThread(() -> {
                if (found != null) openExternally(Uri.parse(found));
                else { inWatchFlow = true; web.loadUrl(page); }
            });
        }).start();
        return true;
    }

    /**
     * "Book tickets": the page sends a DuckDuckGo "first result" link (q = "\\ site:<site> <title> <city>")
     * with vs_book=<site> and vs_t=<title>. Look the search up here, take the first result on that site whose
     * address contains the title, and open it in the ticketing app at that exact movie. If nothing matches,
     * the link opens in the browser, where DuckDuckGo jumps to its first result.
     */
    private boolean resolveBookLink(Uri uri) {
        final String site = uri.getQueryParameter("vs_book"), title = uri.getQueryParameter("vs_t"), q = uri.getQueryParameter("q");
        if (site == null || site.isEmpty() || q == null) return false;
        final Uri plain = uri.buildUpon().clearQuery().appendQueryParameter("q", q).build();
        Toast.makeText(this, "Finding this movie on " + site + "…", Toast.LENGTH_SHORT).show();
        final String ua = web.getSettings().getUserAgentString();
        new Thread(() -> {
            String found = null;
            try {
                String html = download("https://html.duckduckgo.com/html/?q=" + Uri.encode(q.replaceFirst("^\\\\\\s*", "")), ua);
                Matcher m = Pattern.compile("uddg=([^&\"']+)").matcher(html);
                String want = norm(title);
                while (m.find() && found == null) {
                    String u = Uri.decode(m.group(1));
                    Uri cand = Uri.parse(u);
                    String host = cand.getHost() == null ? "" : cand.getHost();
                    if (!(host.equals(site) || host.endsWith("." + site))) continue;
                    if (want.isEmpty() || norm(cand.getPath()).contains(want)) found = u;
                }
            } catch (Exception ignored) { }
            final String dest = found;
            runOnUiThread(() -> {
                Uri target = dest != null ? Uri.parse(dest) : plain;
                if (dest == null) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE)); }
                    catch (ActivityNotFoundException e) { Toast.makeText(this, "No browser to open this", Toast.LENGTH_SHORT).show(); }
                } else openExternally(target);
            });
        }).start();
        return true;
    }

    private static String download(String page, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(page).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept-Language", "en-IN,en;q=0.8");
        String cookies = android.webkit.CookieManager.getInstance().getCookie(page);
        if (cookies != null) c.setRequestProperty("Cookie", cookies);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 3_000_000) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static String norm(String s) { return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    /** Destination URL of the first JustWatch link on the page for the wanted app (exact name first, then a close match). */
    static String findDestination(String html, String want) {
        String close = null;
        Matcher m = A_TAG.matcher(html);
        while (m.find()) {
            String href = null, title = "";
            Matcher a = ATTR.matcher(m.group());
            while (a.find()) {
                String k = a.group(1).toLowerCase(java.util.Locale.ROOT), v = a.group(2).replace("&amp;", "&");
                if (k.equals("href")) href = v; else if (k.equals("title")) title = v;
            }
            if (href == null || !href.contains("justwatch.com")) continue;
            String dest = queryParam(href, "r");
            if (dest == null || !(dest.startsWith("https://") || dest.startsWith("http://"))) continue;
            String name = title.contains(" on ") ? title.substring(title.lastIndexOf(" on ") + 4) : "";
            if (name.isEmpty()) {
                Matcher alt = IMG_ALT.matcher(html);
                alt.region(m.end(), Math.min(html.length(), m.end() + 600));
                if (alt.find()) name = alt.group(1);
            }
            String key = norm(name);
            if (key.equals(want)) return dest;
            if (close == null && key.length() > 3 && (key.contains(want) || want.contains(key))) close = dest;
        }
        return close;
    }

    static String queryParam(String url, String key) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        for (String part : url.substring(q + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                try { return java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8"); } catch (Exception e) { return null; }
            }
        }
        return null;
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
        if (web != null && isAppUrl(Uri.parse(mainUrl))) {
            // the remote's Back closes the open panel first (title details, search, mixer…)
            web.evaluateJavascript(TV_BACK, r -> { if (r == null || !r.contains("y")) backOut(); });
            return;
        }
        backOut();
    }

    private void backOut() {
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
