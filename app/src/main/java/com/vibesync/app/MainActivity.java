package com.vibesync.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
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

    private String appUrl = "";
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

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (SETTINGS_SCHEME.equals(uri.getScheme())) {
                    askForUrl();
                    return true;
                }
                if (isAppUrl(uri)) return false;
                openExternally(uri);
                return true;
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

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            // no app can open this link; stay in VibeSync
        }
    }

    private void showPage(String emoji, String message, String buttonsHtml) {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:#07060F;color:#E8E6FF;font-family:sans-serif;text-align:center'><div>"
                + "<div style='font-size:48px'>" + emoji + "</div>"
                + "<h1 style='letter-spacing:.15em;color:#00F0FF;text-shadow:0 0 18px #00F0FF'>VIBESYNC</h1>"
                + "<p style='color:#A3A0C8;max-width:280px;margin:0 auto 24px'>" + message + "</p>"
                + buttonsHtml + "</div></body></html>";
        web.loadDataWithBaseURL(appUrl.isEmpty() ? null : appUrl, html, "text/html", "utf-8", null);
    }

    private static String button(String href, String label, String color) {
        return "<a href='" + href + "' style='display:inline-block;margin:6px;padding:14px 22px;border-radius:14px;"
                + "background:" + color + ";color:#fff;font-weight:bold;text-decoration:none;"
                + "box-shadow:0 0 22px " + color + "'>" + label + "</a>";
    }

    private void showWelcome() {
        showPage("&#9889;", "Connect to your VibeSync server to get started.",
                button(SETTINGS_SCHEME + "://settings", "Set server URL", "#FF2BD6"));
    }

    private void showOffline() {
        showPage("&#128225;", "Can't reach the VibeSync server. Check your internet or wake the app, then try again.",
                button(appUrl, "Try again", "#FF2BD6") + "<br>"
                        + button(SETTINGS_SCHEME + "://settings", "Change URL", "#7A5CFF"));
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
