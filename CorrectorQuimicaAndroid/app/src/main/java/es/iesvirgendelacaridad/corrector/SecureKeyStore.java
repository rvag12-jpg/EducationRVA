package es.iesvirgendelacaridad.corrector;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Almacena la clave OpenAI cifrada con AES/GCM y una clave no exportable de AndroidKeyStore. */
public final class SecureKeyStore {
    private static final String KS = "AndroidKeyStore";
    private static final String ALIAS = "corrector_quimica_openai_aes_v1";
    private static final String PREFS = "corrector_quimica_secrets_v1";
    private static final String IV = "openai_iv";
    private static final String CT = "openai_ciphertext";
    private final Context context;

    public SecureKeyStore(Context context) { this.context = context.getApplicationContext(); }

    public synchronized void save(String apiKey) throws Exception {
        String value = apiKey == null ? "" : apiKey.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("La clave API está vacía.");
        SecretKey key = getOrCreate();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString(CT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit();
    }

    public synchronized String get() {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String iv = p.getString(IV, ""), ct = p.getString(CT, "");
        if (iv == null || ct == null || iv.isEmpty() || ct.isEmpty()) return "";
        try {
            KeyStore store = KeyStore.getInstance(KS); store.load(null);
            SecretKey key = (SecretKey) store.getKey(ALIAS, null);
            if (key == null) { clear(); return ""; }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) { clear(); return ""; }
    }

    public synchronized boolean hasKey() { return !get().isEmpty(); }

    public synchronized void clear() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        try {
            KeyStore store = KeyStore.getInstance(KS); store.load(null);
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
        } catch (Exception ignored) {}
    }

    private SecretKey getOrCreate() throws Exception {
        KeyStore store = KeyStore.getInstance(KS); store.load(null);
        SecretKey key = (SecretKey) store.getKey(ALIAS, null);
        if (key != null) return key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
