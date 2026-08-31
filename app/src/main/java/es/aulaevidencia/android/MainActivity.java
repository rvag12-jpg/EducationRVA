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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.FragmentActivity;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

public class MainActivity extends FragmentActivity {
    private static final String HOME_URL="file:///android_asset/www/index.html";
    private WebView webView; private SecurityManager security; private volatile boolean unlocked=false; private boolean authDialogVisible=false,webInitialized=false; private long backgroundAt=0L;
    private ActivityResultLauncher<Intent> backupPicker;
    @Override protected void onCreate(Bundle b){super.onCreate(b);security=new SecurityManager(this);cleanupShareCache();backupPicker=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),r->{if(r.getResultCode()!=RESULT_OK||r.getData()==null||r.getData().getData()==null)return;Uri u=r.getData().getData();try(InputStream in=getContentResolver().openInputStream(u)){if(in==null)throw new Exception();byte[] data=in.readAllBytes();if(data.length>25*1024*1024)throw new Exception("Archivo demasiado grande");String text=new String(data,StandardCharsets.UTF_8);String name="copia.aebk";try{android.database.Cursor c=getContentResolver().query(u,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null);if(c!=null){if(c.moveToFirst())name=c.getString(0);c.close();}}catch(Exception ignored){}final String fn=name,ft=text;webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('docio-backup-file-selected',{detail:{name:"+JSONObject.quote(fn)+",content:"+JSONObject.quote(ft)+"}}));",null);}catch(Exception ex){Toast.makeText(this,"No se pudo leer la copia de seguridad",Toast.LENGTH_LONG).show();}});getWindow().setStatusBarColor(Color.rgb(23,59,103));getWindow().setNavigationBarColor(Color.rgb(245,247,250));applyScreenshotProtection();showPrivacyPlaceholder();authenticateOrSetup();}
    private void showPrivacyPlaceholder(){TextView v=new TextView(this);v.setText("DOCIO\nDatos protegidos");v.setTextSize(22f);v.setTextColor(Color.rgb(23,59,103));v.setGravity(android.view.Gravity.CENTER);v.setBackgroundColor(Color.rgb(245,247,250));setContentView(v);}
    private void authenticateOrSetup(){if(authDialogVisible||unlocked)return;if(!security.hasPin())showPinSetupDialog();else if(security.isBiometricEnabled()&&canUseBiometric())showBiometricPrompt();else showPinUnlockDialog();}
    private void showPinSetupDialog(){authDialogVisible=true;LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);int pad=(int)(20*getResources().getDisplayMetrics().density);box.setPadding(pad,pad/2,pad,0);EditText pin=pinField("Código de 6–12 cifras"),confirm=pinField("Repetir código");box.addView(pin);box.addView(confirm);AlertDialog d=new AlertDialog.Builder(this).setTitle("Protege DOCIO").setMessage("Crea un código local. Los datos académicos se almacenarán cifrados en este dispositivo.").setView(box).setCancelable(false).setPositiveButton("Crear",null).create();d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{String p1=pin.getText().toString(),p2=confirm.getText().toString();if(!p1.equals(p2)){confirm.setError("Los códigos no coinciden");return;}security.setPin(p1);authDialogVisible=false;d.dismiss();unlocked=true;initializeWebView();if(canUseBiometric())offerBiometric();}catch(Exception ex){pin.setError(ex.getMessage());}}));d.show();}
    private void showPinUnlockDialog(){authDialogVisible=true;EditText pin=pinField("Código");AlertDialog d=new AlertDialog.Builder(this).setTitle("Desbloquear DOCIO").setView(pin).setCancelable(false).setPositiveButton("Desbloquear",null).setNegativeButton(security.isBiometricEnabled()&&canUseBiometric()?"Biometría":"Salir",null).create();d.setOnShowListener(x->{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{long rr=security.getLockoutRemainingMs();if(rr>0){pin.setError("Demasiados intentos. Espera "+Math.max(1,rr/1000)+" s");return;}if(security.verifyPin(pin.getText().toString())){authDialogVisible=false;d.dismiss();unlockSucceeded();}else{pin.setText("");pin.setError("Código incorrecto");}}catch(Exception ex){pin.setError("No se pudo verificar el código");}});d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{d.dismiss();authDialogVisible=false;if(security.isBiometricEnabled()&&canUseBiometric())showBiometricPrompt();else finishAndRemoveTask();});});d.show();pin.requestFocus();}
    private EditText pinField(String h){EditText e=new EditText(this);e.setHint(h);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);e.setSingleLine(true);return e;} private boolean canUseBiometric(){return BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)==BiometricManager.BIOMETRIC_SUCCESS;}
    private void showBiometricPrompt(){if(!canUseBiometric()){showPinUnlockDialog();return;}authDialogVisible=true;Executor executor=ContextCompat.getMainExecutor(this);BiometricPrompt p=new BiometricPrompt(this,executor,new BiometricPrompt.AuthenticationCallback(){@Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r){authDialogVisible=false;unlockSucceeded();}@Override public void onAuthenticationError(int c,CharSequence s){authDialogVisible=false;if(!isFinishing())showPinUnlockDialog();}});p.authenticate(new BiometricPrompt.PromptInfo.Builder().setTitle("Desbloquear DOCIO").setNegativeButtonText("Usar código").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build());}
    private void offerBiometric(){new AlertDialog.Builder(this).setTitle("Desbloqueo biométrico").setMessage("¿Quieres permitir el desbloqueo con biometría fuerte?").setPositiveButton("Activar",(d,w)->security.setBiometricEnabled(true)).setNegativeButton("Ahora no",null).show();}
    private void unlockSucceeded(){unlocked=true;backgroundAt=0;if(!webInitialized)initializeWebView();else{setContentView(webView);webView.setVisibility(View.VISIBLE);}} private void lockNow(){unlocked=false;if(webView!=null)webView.setVisibility(View.INVISIBLE);showPrivacyPlaceholder();authenticateOrSetup();}
    private void initializeWebView(){webInitialized=true;webView=new WebView(this);setContentView(webView);WebSettings s=webView.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(false);s.setAllowFileAccess(true);s.setAllowContentAccess(false);s.setAllowFileAccessFromFileURLs(false);s.setAllowUniversalAccessFromFileURLs(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setCacheMode(WebSettings.LOAD_NO_CACHE);s.setGeolocationEnabled(false);CookieManager cm=CookieManager.getInstance();cm.setAcceptCookie(false);cm.setAcceptThirdPartyCookies(webView,false);WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0);webView.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){String u=r.getUrl().toString();return !u.startsWith("file:///android_asset/www/")&&!"about:blank".equals(u);}});webView.setWebChromeClient(new WebChromeClient());webView.addJavascriptInterface(new AndroidBridge(),"Android");webView.loadUrl(HOME_URL);}
    private void applyScreenshotProtection(){if(security.isScreenshotProtectionEnabled())getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);}
    private class AndroidBridge{
      @JavascriptInterface public String platform(){return "android-personal-secure";} @JavascriptInterface public String secureLoad(){try{return security.loadSecureJson();}catch(Exception e){return "ERROR:"+e.getMessage();}} @JavascriptInterface public boolean secureSave(String j){try{security.saveSecureJson(j);return true;}catch(Exception e){return false;}} @JavascriptInterface public String secureSaveDiagnostic(String j){try{security.saveSecureJson(j);return "OK";}catch(Exception e){return "ERROR:"+e.getMessage();}}
      @JavascriptInterface public String securityStatus(){try{JSONObject o=new JSONObject();o.put("encryptedStorage",true);o.put("pinEnabled",security.hasPin());o.put("biometricAvailable",canUseBiometric());o.put("biometricEnabled",security.isBiometricEnabled());o.put("autoLockMinutes",security.getAutoLockMinutes());o.put("screenshotProtection",security.isScreenshotProtectionEnabled());o.put("secureDataPresent",security.hasSecureData());return o.toString();}catch(Exception e){return "{}";}}
      @JavascriptInterface public void setBiometricEnabled(boolean e){security.setBiometricEnabled(e);}@JavascriptInterface public void setAutoLockMinutes(int m){security.setAutoLockMinutes(m);}@JavascriptInterface public void setScreenshotProtection(boolean e){security.setScreenshotProtectionEnabled(e);runOnUiThread(MainActivity.this::applyScreenshotProtection);}@JavascriptInterface public void lockNow(){runOnUiThread(MainActivity.this::lockNow);}@JavascriptInterface public void changePin(){runOnUiThread(MainActivity.this::showChangePinDialog);}
      @JavascriptInterface public String encryptBackup(String j,String p){try{return security.encryptBackup(j,p);}catch(Exception e){return "ERROR:"+e.getMessage();}} @JavascriptInterface public String decryptBackup(String x,String p){try{return security.decryptBackup(x,p);}catch(Exception e){return "ERROR:No se pudo descifrar la copia. Comprueba la contraseña y el archivo.";}}
      @JavascriptInterface public void chooseBackupFile(){runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/octet-stream");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/octet-stream","application/json","text/plain","*/*"});backupPicker.launch(i);});}
      @JavascriptInterface public void shareText(String f,String c,String m){runOnUiThread(()->shareAsFile(f,c,m));}@JavascriptInterface public void printCurrentPage(String n){runOnUiThread(()->printWebView(n));}
    }
    private void showChangePinDialog(){EditText n=pinField("Nuevo código (6–12 cifras)");new AlertDialog.Builder(this).setTitle("Cambiar código").setView(n).setPositiveButton("Cambiar",(d,w)->{try{security.setPin(n.getText().toString());}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}).setNegativeButton("Cancelar",null).show();}
    private void printWebView(String n){try{PrintManager pm=(PrintManager)getSystemService(Context.PRINT_SERVICE);String x=(n==null||n.isBlank())?"DOCIO":n;PrintDocumentAdapter a=webView.createPrintDocumentAdapter(x);pm.print(x,a,new PrintAttributes.Builder().build());}catch(Exception e){Toast.makeText(this,"No se pudo imprimir",Toast.LENGTH_LONG).show();}}
    private void cleanupShareCache(){File d=new File(getCacheDir(),"shares");File[] fs=d.listFiles();if(fs!=null)for(File f:fs)if(f.isFile())f.delete();}
    private void shareAsFile(String f,String c,String m){try{String safe=(f==null||f.isBlank())?"DOCIO.txt":f.replaceAll("[^a-zA-Z0-9._-]","_");File d=new File(getCacheDir(),"shares");d.mkdirs();File file=new File(d,safe);try(FileOutputStream out=new FileOutputStream(file)){out.write((c==null?"":c).getBytes(StandardCharsets.UTF_8));}Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",file);Intent send=new Intent(Intent.ACTION_SEND);send.setType(m==null?"application/octet-stream":m);send.putExtra(Intent.EXTRA_STREAM,uri);send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(send,"Compartir desde DOCIO"));}catch(Exception e){Toast.makeText(this,"No se pudo compartir",Toast.LENGTH_LONG).show();}}
    @Override public void onBackPressed(){if(!unlocked){finishAndRemoveTask();return;}if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
    @Override protected void onPause(){if(unlocked&&!authDialogVisible)backgroundAt=SystemClock.elapsedRealtime();if(webView!=null)webView.onPause();super.onPause();}@Override protected void onResume(){super.onResume();applyScreenshotProtection();if(webView!=null)webView.onResume();if(unlocked&&backgroundAt>0&&SystemClock.elapsedRealtime()-backgroundAt>=security.getAutoLockMinutes()*60000L){unlocked=false;showPrivacyPlaceholder();}if(!unlocked&&security.hasPin()&&!authDialogVisible)authenticateOrSetup();}@Override protected void onDestroy(){if(webView!=null){webView.removeJavascriptInterface("Android");webView.destroy();}super.onDestroy();}
}
