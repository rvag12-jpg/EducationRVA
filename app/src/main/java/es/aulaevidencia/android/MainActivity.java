package es.aulaevidencia.android;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.content.Context;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import android.app.AlertDialog;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.FragmentActivity;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

public class MainActivity extends FragmentActivity {
    private static final String HOME_URL = "file:///android_asset/www/index.html";
    private WebView webView;
    private SecurityManager security;
    private boolean unlocked = false;
    private boolean authDialogVisible = false;
    private boolean webInitialized = false;
    private long backgroundAt = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        security = new SecurityManager(this);
        cleanupShareCache();
        getWindow().setStatusBarColor(Color.rgb(23, 59, 103));
        getWindow().setNavigationBarColor(Color.rgb(245, 247, 250));
        applyScreenshotProtection();
        showPrivacyPlaceholder();
        authenticateOrSetup();
    }

    private void showPrivacyPlaceholder() {
        TextView view = new TextView(this);
        view.setText("AulaEvidencia\nDatos protegidos");
        view.setTextSize(22f);
        view.setTextColor(Color.rgb(23, 59, 103));
        view.setGravity(android.view.Gravity.CENTER);
        view.setBackgroundColor(Color.rgb(245, 247, 250));
        setContentView(view);
    }

    private void authenticateOrSetup() {
        if (authDialogVisible || unlocked) return;
        if (!security.hasPin()) showPinSetupDialog();
        else if (security.isBiometricEnabled() && canUseBiometric()) showBiometricPrompt();
        else showPinUnlockDialog();
    }

    private void showPinSetupDialog() {
        authDialogVisible = true;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText pin = pinField("Código de 6–12 cifras");
        EditText confirm = pinField("Repetir código");
        box.addView(pin);
        box.addView(confirm);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Protege AulaEvidencia")
                .setMessage("Crea un código local. Los datos académicos se almacenarán cifrados en este dispositivo.")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Crear", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String p1 = pin.getText().toString();
                String p2 = confirm.getText().toString();
                if (!p1.equals(p2)) { confirm.setError("Los códigos no coinciden"); return; }
                security.setPin(p1);
                authDialogVisible = false;
                d.dismiss();
                unlocked = true;
                initializeWebView();
                if (canUseBiometric()) offerBiometric();
            } catch (Exception ex) { pin.setError(ex.getMessage()); }
        }));
        d.show();
    }

    private void showPinUnlockDialog() {
        authDialogVisible = true;
        EditText pin = pinField("Código");
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Desbloquear AulaEvidencia")
                .setView(pin)
                .setCancelable(false)
                .setPositiveButton("Desbloquear", null)
                .setNegativeButton(security.isBiometricEnabled() && canUseBiometric() ? "Biometría" : "Salir", null)
                .create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    long remaining = security.getLockoutRemainingMs();
                    if (remaining > 0) { pin.setError("Demasiados intentos. Espera " + Math.max(1, remaining / 1000) + " s"); return; }
                    if (security.verifyPin(pin.getText().toString())) {
                        authDialogVisible = false; d.dismiss(); unlockSucceeded();
                    } else { pin.setText(""); pin.setError("Código incorrecto"); }
                } catch (Exception ex) { pin.setError("No se pudo verificar el código"); }
            });
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                d.dismiss(); authDialogVisible = false;
                if (security.isBiometricEnabled() && canUseBiometric()) showBiometricPrompt(); else finishAndRemoveTask();
            });
        });
        d.show(); pin.requestFocus();
    }

    private EditText pinField(String hint) {
        EditText e = new EditText(this); e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD); e.setSingleLine(true); return e;
    }

    private boolean canUseBiometric() {
        return BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void showBiometricPrompt() {
        if (!canUseBiometric()) { showPinUnlockDialog(); return; }
        authDialogVisible = true;
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) { authDialogVisible = false; unlockSucceeded(); }
            @Override public void onAuthenticationError(int errorCode, CharSequence errString) { authDialogVisible = false; if (!isFinishing()) showPinUnlockDialog(); }
            @Override public void onAuthenticationFailed() { Toast.makeText(MainActivity.this, "Biometría no reconocida", Toast.LENGTH_SHORT).show(); }
        });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Desbloquear AulaEvidencia").setSubtitle("Usa biometría fuerte").setNegativeButtonText("Usar código")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build();
        prompt.authenticate(info);
    }

    private void offerBiometric() {
        new AlertDialog.Builder(this).setTitle("Desbloqueo biométrico")
                .setMessage("¿Quieres permitir el desbloqueo con huella o biometría fuerte? El código seguirá disponible como alternativa.")
                .setPositiveButton("Activar", (d, w) -> security.setBiometricEnabled(true)).setNegativeButton("Ahora no", null).show();
    }

    private void unlockSucceeded() {
        unlocked = true; backgroundAt = 0L;
        if (!webInitialized) initializeWebView(); else if (webView != null) { setContentView(webView); webView.setVisibility(View.VISIBLE); }
    }

    private void lockNow() { unlocked = false; if (webView != null) webView.setVisibility(View.INVISIBLE); showPrivacyPlaceholder(); authenticateOrSetup(); }

    private void initializeWebView() {
        webInitialized = true; webView = new WebView(this); setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(false); s.setAllowFileAccess(true); s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false); s.setAllowUniversalAccessFromFileURLs(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setBuiltInZoomControls(false); s.setDisplayZoomControls(false); s.setTextZoom(100); s.setMediaPlaybackRequiresUserGesture(true); s.setSaveFormData(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE); s.setGeolocationEnabled(false);
        CookieManager cm = CookieManager.getInstance(); cm.setAcceptCookie(false); cm.setAcceptThirdPartyCookies(webView, false);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { Uri uri = request.getUrl(); String url = uri == null ? "" : uri.toString(); return !url.startsWith("file:///android_asset/www/") && !"about:blank".equals(url); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return url == null || (!url.startsWith("file:///android_asset/www/") && !"about:blank".equals(url)); }
        });
        webView.setWebChromeClient(new WebChromeClient()); webView.addJavascriptInterface(new AndroidBridge(), "Android"); webView.loadUrl(HOME_URL);
    }

    private void applyScreenshotProtection() { if (security != null && security.isScreenshotProtectionEnabled()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE); }

    private class AndroidBridge {
        @JavascriptInterface public String platform() { return "android-personal-secure"; }
        @JavascriptInterface public String secureLoad() { if (!unlocked) return ""; try { return security.loadSecureJson(); } catch (Exception ex) { return "ERROR:No se pudo descifrar el almacenamiento local"; } }
        @JavascriptInterface public boolean secureSave(String json) { if (!unlocked) return false; try { security.saveSecureJson(json); return true; } catch (Exception ex) { return false; } }
        @JavascriptInterface public String securityStatus() { try { JSONObject o = new JSONObject(); o.put("encryptedStorage", true); o.put("pinEnabled", security.hasPin()); o.put("biometricAvailable", canUseBiometric()); o.put("biometricEnabled", security.isBiometricEnabled()); o.put("autoLockMinutes", security.getAutoLockMinutes()); o.put("screenshotProtection", security.isScreenshotProtectionEnabled()); o.put("secureDataPresent", security.hasSecureData()); return o.toString(); } catch (Exception ex) { return "{}"; } }
        @JavascriptInterface public void setBiometricEnabled(boolean enabled) { runOnUiThread(() -> { if (enabled && !canUseBiometric()) { Toast.makeText(MainActivity.this, "No hay biometría fuerte disponible", Toast.LENGTH_LONG).show(); return; } security.setBiometricEnabled(enabled); Toast.makeText(MainActivity.this, enabled ? "Biometría activada" : "Biometría desactivada", Toast.LENGTH_SHORT).show(); }); }
        @JavascriptInterface public void setAutoLockMinutes(int minutes) { security.setAutoLockMinutes(minutes); }
        @JavascriptInterface public void setScreenshotProtection(boolean enabled) { security.setScreenshotProtectionEnabled(enabled); runOnUiThread(MainActivity.this::applyScreenshotProtection); }
        @JavascriptInterface public void lockNow() { runOnUiThread(MainActivity.this::lockNow); }
        @JavascriptInterface public void changePin() { runOnUiThread(MainActivity.this::showChangePinDialog); }
        @JavascriptInterface public String encryptBackup(String json, String passphrase) { if (!unlocked) return ""; try { return security.encryptBackup(json, passphrase); } catch (Exception ex) { return "ERROR:" + ex.getMessage(); } }
        @JavascriptInterface public String decryptBackup(String payload, String passphrase) { if (!unlocked) return ""; try { return security.decryptBackup(payload, passphrase); } catch (Exception ex) { return "ERROR:No se pudo descifrar la copia. Comprueba la contraseña y el archivo."; } }
        @JavascriptInterface public void shareText(String filename, String content, String mimeType) { runOnUiThread(() -> shareAsFile(filename, content, mimeType)); }
        @JavascriptInterface public void printCurrentPage(String documentName) { runOnUiThread(() -> printWebView(documentName)); }
    }

    private void showChangePinDialog() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); int pad = (int) (20 * getResources().getDisplayMetrics().density); box.setPadding(pad, pad / 2, pad, 0);
        EditText oldPin = pinField("Código actual"), newPin = pinField("Nuevo código (6–12 cifras)"), confirm = pinField("Repetir nuevo código"); box.addView(oldPin); box.addView(newPin); box.addView(confirm);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Cambiar código").setView(box).setNegativeButton("Cancelar", null).setPositiveButton("Cambiar", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { if (!security.verifyPin(oldPin.getText().toString())) { oldPin.setError("Código actual incorrecto"); return; } if (!newPin.getText().toString().equals(confirm.getText().toString())) { confirm.setError("Los códigos no coinciden"); return; } security.setPin(newPin.getText().toString()); d.dismiss(); Toast.makeText(this, "Código actualizado", Toast.LENGTH_SHORT).show(); }
            catch (Exception ex) { newPin.setError(ex.getMessage()); }
        })); d.show();
    }

    private void printWebView(String documentName) {
        if (webView == null || !unlocked) return;
        try { PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE); String safeName = (documentName == null || documentName.isBlank()) ? "AulaEvidencia" : documentName; PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(safeName); printManager.print(safeName, adapter, new PrintAttributes.Builder().build()); }
        catch (Exception ex) { Toast.makeText(this, "No se pudo abrir el diálogo de impresión", Toast.LENGTH_LONG).show(); }
    }

    private void cleanupShareCache() { File dir = new File(getCacheDir(), "shares"); File[] files = dir.listFiles(); if (files == null) return; for (File f : files) if (f.isFile()) f.delete(); }

    private void shareAsFile(String filename, String content, String mimeType) {
        try { String safe = (filename == null || filename.isBlank()) ? "AulaEvidencia.txt" : filename.replaceAll("[^a-zA-Z0-9._-]", "_"); File dir = new File(getCacheDir(), "shares"); if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("No se pudo crear almacenamiento temporal"); File file = new File(dir, safe); try (FileOutputStream out = new FileOutputStream(file, false)) { out.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8)); } Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file); Intent send = new Intent(Intent.ACTION_SEND); send.setType((mimeType == null || mimeType.isBlank()) ? "application/octet-stream" : mimeType); send.putExtra(Intent.EXTRA_STREAM, uri); send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(send, "Compartir desde AulaEvidencia")); }
        catch (Exception ex) { Toast.makeText(this, "No se pudo compartir el archivo", Toast.LENGTH_LONG).show(); }
    }

    @Override public void onBackPressed() { if (!unlocked) { finishAndRemoveTask(); return; } if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed(); }
    @Override protected void onPause() { if (unlocked && !authDialogVisible) backgroundAt = SystemClock.elapsedRealtime(); if (webView != null) webView.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); applyScreenshotProtection(); if (webView != null) webView.onResume(); if (unlocked && backgroundAt > 0L) { long elapsed = SystemClock.elapsedRealtime() - backgroundAt; long timeout = security.getAutoLockMinutes() * 60_000L; if (elapsed >= timeout) { unlocked = false; if (webView != null) webView.setVisibility(View.INVISIBLE); showPrivacyPlaceholder(); } } if (!unlocked && security.hasPin() && !authDialogVisible) authenticateOrSetup(); }
    @Override protected void onDestroy() { if (webView != null) { webView.removeJavascriptInterface("Android"); webView.clearCache(true); webView.destroy(); } super.onDestroy(); }
}
