package au.charlie.eyelab;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import java.io.PrintWriter;
import java.io.StringWriter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.widget.Toast;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;

/** Full-screen wrapper: the test suite runs from the APK's own files, with no browser bars or full-screen notice. */
public class MainActivity extends Activity {
  private static final String HOST = "appassets.androidplatform.net";
  private WebView web;
  private PermissionRequest pending;
  private SharedPreferences prefs;
  private String crashMsg = "";

  /** Crash breadcrumbs. The page marks when the camera is running and which step it has reached; a Java exception's
   *  stack trace is saved before the process dies. If the app is opened again with the camera still marked as running,
   *  the last session died during it (a native crash in the camera or graphics driver can't be caught, but this
   *  catches that it happened and where), and the page is told so it can say what happened instead of crashing again. */
  public class Bridge {
    @JavascriptInterface public void camOn(boolean on) { prefs.edit().putBoolean("camOn", on).putString("stage", on ? "starting" : "").commit(); }
    @JavascriptInterface public void stage(String st) { prefs.edit().putString("stage", st == null ? "" : st).commit(); }
    @JavascriptInterface public String lastCrash() { return crashMsg; }
    @JavascriptInterface public String version() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; } }
  }

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("crash", MODE_PRIVATE);
    String trace = prefs.getString("trace", "");
    if (prefs.getBoolean("camOn", false)) {
      crashMsg = "The app closed unexpectedly last time while the camera was in use (last step: " + prefs.getString("stage", "unknown") + ").";
      if (trace.length() > 0) crashMsg += " Error: " + trace;
    } else if (trace.length() > 0) crashMsg = "The app closed unexpectedly last time. Error: " + trace;
    prefs.edit().putBoolean("camOn", false).putString("stage", "").putString("trace", "").commit();
    final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
      try { StringWriter w = new StringWriter(); e.printStackTrace(new PrintWriter(w)); String st = w.toString();
        prefs.edit().putString("trace", st.length() > 600 ? st.substring(0, 600) : st).commit(); } catch (Throwable ignored) { }
      if (prev != null) prev.uncaughtException(t, e);
    });
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // no dimming mid-test
    makeWeb();
    if (b != null) web.restoreState(b); else web.loadUrl("https://" + HOST + "/assets/index.html?app=1");
    hideBars();
  }

  private void makeWeb() {
    web = new WebView(this);
    web.setBackgroundColor(0xFF000000);
    setContentView(web);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);                    // saved settings, calibration and results
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setAllowFileAccess(false);
    s.setTextZoom(100);                              // system font scaling would break physical sizes
    web.addJavascriptInterface(new Bridge(), "EyeLabApp");
    // served from https://appassets.androidplatform.net/assets/ so the page is a secure origin (camera allowed)
    final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
    web.setWebViewClient(new WebViewClient() {
      @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
        return loader.shouldInterceptRequest(r.getUrl());
      }
      // If the page's renderer dies (a cheap tablet running out of memory under the camera + face tracking), Android
      // would otherwise close the whole app. Rebuild the page instead and say what happened.
      @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
        if (web == v) {
          if (prefs.getBoolean("camOn", false)) prefs.edit().putBoolean("camOn", false).commit();
          crashMsg = "The page's process was stopped by Android (usually running out of memory) and has been restarted.";
          try { ViewGroup pv = (ViewGroup) v.getParent(); if (pv != null) pv.removeView(v); } catch (Exception e) { }
          makeWeb(); try { v.destroy(); } catch (Exception e) { } web.loadUrl("https://" + HOST + "/assets/index.html?app=1");
          Toast.makeText(MainActivity.this, "The page ran out of memory and was restarted. Close other apps before camera tests.", Toast.LENGTH_LONG).show(); }
        return true;
      }
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
        Uri u = r.getUrl();
        if (HOST.equals(u.getHost())) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
        return true;                                 // outside links open in the browser
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public void onPermissionRequest(final PermissionRequest req) {
        runOnUiThread(() -> {
          try {
            if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
              grantVideo(req);
            } else if (pending != null) {
              req.deny();                              // a system dialog is already up for an earlier request
            } else { pending = req; requestPermissions(new String[]{Manifest.permission.CAMERA}, 1); }
          } catch (Exception e) { try { req.deny(); } catch (Exception ignored) { } }
        });
      }
      @Override public void onPermissionRequestCanceled(PermissionRequest req) { if (req == pending) pending = null; }
    });
  }

  /** Grant only the camera, whatever else the page's request listed. */
  private void grantVideo(PermissionRequest req) {
    for (String r : req.getResources()) if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) { req.grant(new String[]{r}); return; }
    req.deny();
  }

  @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
    final PermissionRequest p = pending; pending = null;
    if (p == null) return;
    try { if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) grantVideo(p); else p.deny(); } catch (Exception e) { }
  }

  @Override protected void onPause() { super.onPause(); if (web != null) web.onPause(); }
  @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }

  @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) hideBars(); }
  @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }
  @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }

  /** Hide status and navigation bars; a swipe from the edge shows them briefly. */
  @SuppressWarnings("deprecation")
  private void hideBars() {
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
      WindowInsetsController c = getWindow().getInsetsController();
      if (c != null) {
        c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      }
    } else {
      getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
          | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
          | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
  }
}
