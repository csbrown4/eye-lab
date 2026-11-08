package au.charlie.eyelab;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
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

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
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
        if (web == v) { try { v.destroy(); } catch (Exception e) { } makeWeb(); web.loadUrl("https://" + HOST + "/assets/index.html?app=1");
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
          if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            req.grant(req.getResources());
          } else { pending = req; requestPermissions(new String[]{Manifest.permission.CAMERA}, 1); }
        });
      }
    });
  }

  @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
    if (pending == null) return;
    if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) pending.grant(pending.getResources()); else pending.deny();
    pending = null;
  }

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
