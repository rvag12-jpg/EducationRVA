from pathlib import Path

ROOT = Path(__file__).resolve().parent
MAIN = ROOT / "app/src/main/java/es/iesvirgendelacaridad/corrector/MainActivity.java"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
GRADLE = ROOT / "app/build.gradle"

s = MAIN.read_text(encoding="utf-8")

def rep(old, new):
    global s
    if old not in s:
        raise SystemExit("No se encontró bloque esperado en MainActivity: " + old[:120])
    s = s.replace(old, new)

rep('private SharedPreferences prefs;', 'private SharedPreferences prefs;\n    private SecureKeyStore secureKeyStore;')
rep('prefs = getSharedPreferences("corrector_quimica_v1", MODE_PRIVATE);\n        buildUi();',
    'prefs = getSharedPreferences("corrector_quimica_autonoma_v2", MODE_PRIVATE);\n        secureKeyStore = new SecureKeyStore(this);\n        ApiClient.init(this);\n        buildUi();')

rep('root.addView(section("1 · Servidor corrector seguro"));', 'root.addView(section("1 · OpenAI API · modo autónomo"));')
rep('root.addView(note("La clave de OpenAI NO se introduce en Android. El teléfono se conecta a un servidor corrector que guarda la clave fuera del APK. Para uso en la misma red Wi‑Fi, introduce la URL LAN del ordenador, por ejemplo http://192.168.1.25:8765."));',
    'root.addView(note("No necesita ordenador ni servidor. Introduce una clave personal de OpenAI API. Al usarla, se cifra con AES/GCM mediante una clave no exportable del Android Keystore. No se incorpora al APK ni se guarda en texto plano. Los PDF/imágenes se envían directamente a OpenAI por HTTPS para poder corregirlos."));')
rep('backendUrl = edit("URL del servidor", "http://192.168.1.25:8765"); root.addView(backendUrl);',
    'backendUrl = edit("Modelo OpenAI", "gpt-5.6-sol"); root.addView(backendUrl);')
rep('backendToken = edit("Token de acceso del servidor", ""); backendToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); root.addView(backendToken);',
    'backendToken = edit("Clave OpenAI API (déjala vacía si ya está guardada)", ""); backendToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); root.addView(backendToken);')
rep('testBackend = button("Probar conexión", this::onTestBackend); root.addView(testBackend);',
    'testBackend = button("Probar clave y conexión con OpenAI", this::onTestBackend); root.addView(testBackend);\n        root.addView(button("Borrar clave API del dispositivo", v -> { secureKeyStore.clear(); backendToken.setText(""); backendToken.setHint("Clave OpenAI API eliminada"); toast("Clave eliminada del Android Keystore."); }));')

rep('backendUrl.setText(prefs.getString("backend_url", backendUrl.getText().toString()));\n        backendToken.setText(prefs.getString("backend_token", ""));',
    'backendUrl.setText(prefs.getString("openai_model", "gpt-5.6-sol"));\n        backendToken.setText("");\n        backendToken.setHint(secureKeyStore.hasKey() ? "Clave OpenAI API guardada y cifrada" : "Clave OpenAI API");')

rep('prefs.edit()\n                .putString("backend_url", backendUrl.getText().toString().trim())\n                .putString("backend_token", backendToken.getText().toString().trim())',
    'saveTypedApiKey();\n        prefs.edit()\n                .putString("openai_model", backendUrl.getText().toString().trim())')

rep('if (base.isEmpty()) { toast("Indica la URL del servidor."); return; }',
    'if (base.isEmpty()) { toast("Indica el modelo OpenAI, por ejemplo gpt-5.6-sol."); return; }\n        if (!secureKeyStore.hasKey()) { toast("Introduce y guarda primero una clave OpenAI API."); return; }')
rep('JSONObject health = ApiClient.getHealth(base + "/health", backendToken.getText().toString());',
    'JSONObject health = ApiClient.getHealth(base + "/health", secureKeyStore.get());')
rep('toast("Servidor disponible: " + health.optString("app", "corrector"));',
    'toast("Conexión correcta: " + health.optString("app", "OpenAI"));')

rep('JSONObject result = ApiClient.post(normalizedBaseUrl() + "/api/analyze-exam", backendToken.getText().toString(), payload);',
    'JSONObject result = ApiClient.post(normalizedBaseUrl() + "/api/analyze-exam", secureKeyStore.get(), payload);')
rep('JSONObject result = ApiClient.post(normalizedBaseUrl() + "/api/grade", backendToken.getText().toString(), payload);',
    'JSONObject result = ApiClient.post(normalizedBaseUrl() + "/api/grade", secureKeyStore.get(), payload);')

anchor = '    private String normalizedBaseUrl() {'
helper = '''    private void saveTypedApiKey() {\n        String typed = backendToken.getText().toString().trim();\n        if (typed.isEmpty()) return;\n        try {\n            secureKeyStore.save(typed);\n            backendToken.setText(\"\");\n            backendToken.setHint(\"Clave OpenAI API guardada y cifrada\");\n        } catch (Exception e) {\n            toast(\"No se pudo guardar la clave API: \" + e.getMessage());\n        }\n    }\n\n'''
if anchor not in s:
    raise SystemExit("No se encontró normalizedBaseUrl")
s = s.replace(anchor, helper + anchor)
MAIN.write_text(s, encoding="utf-8")

m = MANIFEST.read_text(encoding="utf-8")
m = m.replace('android:allowBackup="true"', 'android:allowBackup="false"')
m = m.replace('android:usesCleartextTraffic="true"', 'android:usesCleartextTraffic="false"')
m = m.replace('android:label="Corrector Química 2.º Bach"', 'android:label="Corrector Química Autónomo"')
MANIFEST.write_text(m, encoding="utf-8")

g = GRADLE.read_text(encoding="utf-8")
g = g.replace('versionCode 1', 'versionCode 2').replace("versionName '1.0.0'", "versionName '2.0.0-autonoma'")
GRADLE.write_text(g, encoding="utf-8")

print('Android autonomous patch applied')
