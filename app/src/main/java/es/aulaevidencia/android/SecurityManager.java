package es.aulaevidencia.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class SecurityManager {
    private static final String PREFS = "aulaevidencia_security_v2";
    private static final String DATA_ALIAS = "aulaevidencia_data_key_v2";
    private static final String SECURE_FILE = "aulaevidencia_secure_store_v2.bin";
    private static final byte[] DATA_AAD = "es.aulaevidencia.android|secure-store|v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BACKUP_AAD = "AulaEvidencia|encrypted-backup|v1".getBytes(StandardCharsets.UTF_8);
    private static final int PIN_ITERATIONS = 600_000;
    private static final int PIN_KEY_BITS = 256;
    private static final int BACKUP_ITERATIONS = 600_000;
    private static final SecureRandom RNG = new SecureRandom();

    private final Context context;
    private final SharedPreferences prefs;
    private final AtomicFile secureFile;

    public SecurityManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.secureFile = new AtomicFile(new File(this.context.getFilesDir(), SECURE_FILE));
        ensureDataKey();
    }

    public boolean hasPin() {
        return prefs.contains("pin_salt") && prefs.contains("pin_hash");
    }

    public void setPin(String pin) throws Exception {
        validatePin(pin);
        byte[] salt = randomBytes(16);
        byte[] hash = derive(pin.toCharArray(), salt, PIN_ITERATIONS, PIN_KEY_BITS);
        prefs.edit()
                .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("pin_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
                .putInt("pin_iterations", PIN_ITERATIONS)
                .putInt("failed_attempts", 0)
                .putLong("lockout_until_epoch", 0L)
                .apply();
        Arrays.fill(hash, (byte) 0);
    }

    public boolean verifyPin(String pin) throws Exception {
        long remaining = getLockoutRemainingMs();
        if (remaining > 0) return false;
        String saltB64 = prefs.getString("pin_salt", null);
        String hashB64 = prefs.getString("pin_hash", null);
        if (saltB64 == null || hashB64 == null) return false;
        byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
        byte[] expected = Base64.decode(hashB64, Base64.NO_WRAP);
        int iterations = prefs.getInt("pin_iterations", PIN_ITERATIONS);
        byte[] actual = derive(pin.toCharArray(), salt, iterations, expected.length * 8);
        boolean ok = MessageDigest.isEqual(expected, actual);
        Arrays.fill(actual, (byte) 0);
        if (ok) {
            prefs.edit().putInt("failed_attempts", 0).putLong("lockout_until_epoch", 0L).apply();
            return true;
        }
        int failures = prefs.getInt("failed_attempts", 0) + 1;
        long lockout = 0L;
        if (failures >= 10) lockout = 5 * 60_000L;
        else if (failures >= 5) lockout = 30_000L;
        SharedPreferences.Editor e = prefs.edit().putInt("failed_attempts", failures);
        if (lockout > 0) e.putLong("lockout_until_epoch", System.currentTimeMillis() + lockout);
        e.apply();
        return false;
    }

    public long getLockoutRemainingMs() {
        long until = prefs.getLong("lockout_until_epoch", 0L);
        if (until <= 0) return 0L;
        return Math.max(0L, until - System.currentTimeMillis());
    }

    public int getAutoLockMinutes() { return prefs.getInt("auto_lock_minutes", 5); }
    public void setAutoLockMinutes(int minutes) { prefs.edit().putInt("auto_lock_minutes", Math.max(1, Math.min(60, minutes))).apply(); }
    public boolean isBiometricEnabled() { return prefs.getBoolean("biometric_enabled", false); }
    public void setBiometricEnabled(boolean enabled) { prefs.edit().putBoolean("biometric_enabled", enabled).apply(); }
    public boolean isScreenshotProtectionEnabled() { return prefs.getBoolean("screenshot_protection", true); }
    public void setScreenshotProtectionEnabled(boolean enabled) { prefs.edit().putBoolean("screenshot_protection", enabled).apply(); }
    public boolean hasSecureData() { return secureFile.getBaseFile().exists() && secureFile.getBaseFile().length() > 0; }

    public synchronized void saveSecureJson(String json) throws Exception {
        if (json == null) throw new IllegalArgumentException("Datos vacíos");
        SecretKey key = getDataKey();
        byte[] iv = randomBytes(12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(DATA_AAD);
        byte[] ciphertext = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(0x41455332); out.writeInt(2); out.writeInt(iv.length); out.write(iv); out.writeInt(ciphertext.length); out.write(ciphertext); out.flush();
        java.io.FileOutputStream stream = null;
        try { stream = secureFile.startWrite(); stream.write(buffer.toByteArray()); secureFile.finishWrite(stream); }
        catch (Exception ex) { if (stream != null) secureFile.failWrite(stream); throw ex; }
    }

    public synchronized String loadSecureJson() throws Exception {
        if (!hasSecureData()) return "";
        byte[] blob = secureFile.readFully();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob));
        int magic = in.readInt(); int version = in.readInt();
        if (magic != 0x41455332 || version != 2) throw new IllegalStateException("Formato de almacenamiento no reconocido");
        int ivLen = in.readInt(); if (ivLen < 12 || ivLen > 32) throw new IllegalStateException("IV no válido");
        byte[] iv = new byte[ivLen]; in.readFully(iv);
        int cipherLen = in.readInt(); if (cipherLen < 16 || cipherLen > 50_000_000) throw new IllegalStateException("Bloque cifrado no válido");
        byte[] ciphertext = new byte[cipherLen]; in.readFully(ciphertext);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getDataKey(), new GCMParameterSpec(128, iv));
        cipher.updateAAD(DATA_AAD);
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    public String encryptBackup(String json, String passphrase) throws Exception {
        validateBackupPassphrase(passphrase);
        byte[] salt = randomBytes(16); byte[] iv = randomBytes(12);
        byte[] keyBytes = derive(passphrase.toCharArray(), salt, BACKUP_ITERATIONS, 256);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv)); cipher.updateAAD(BACKUP_AAD);
        byte[] ciphertext = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8)); Arrays.fill(keyBytes, (byte) 0);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(0x41454231); out.writeInt(1); out.writeInt(BACKUP_ITERATIONS); out.writeInt(salt.length); out.write(salt); out.writeInt(iv.length); out.write(iv); out.writeInt(ciphertext.length); out.write(ciphertext); out.flush();
        return "AE1." + Base64.encodeToString(buffer.toByteArray(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    public String decryptBackup(String encoded, String passphrase) throws Exception {
        validateBackupPassphrase(passphrase);
        if (encoded == null || !encoded.startsWith("AE1.")) throw new IllegalArgumentException("Copia cifrada no válida");
        byte[] blob = Base64.decode(encoded.substring(4), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob));
        int magic = in.readInt(); int version = in.readInt(); int iterations = in.readInt();
        if (magic != 0x41454231 || version != 1 || iterations < 100_000 || iterations > 2_000_000) throw new IllegalArgumentException("Copia cifrada no válida");
        int saltLen = in.readInt(); if (saltLen < 16 || saltLen > 64) throw new IllegalArgumentException("Copia cifrada no válida");
        byte[] salt = new byte[saltLen]; in.readFully(salt);
        int ivLen = in.readInt(); if (ivLen < 12 || ivLen > 32) throw new IllegalArgumentException("Copia cifrada no válida");
        byte[] iv = new byte[ivLen]; in.readFully(iv);
        int cipherLen = in.readInt(); if (cipherLen < 16 || cipherLen > 100_000_000) throw new IllegalArgumentException("Copia cifrada no válida");
        byte[] ciphertext = new byte[cipherLen]; in.readFully(ciphertext);
        byte[] keyBytes = derive(passphrase.toCharArray(), salt, iterations, 256); SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        try { Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv)); cipher.updateAAD(BACKUP_AAD); return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8); }
        finally { Arrays.fill(keyBytes, (byte) 0); }
    }

    public void wipeSecureData() { secureFile.delete(); }

    private void ensureDataKey() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); if (ks.containsAlias(DATA_ALIAS)) return;
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(DATA_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).setRandomizedEncryptionRequired(true).build();
            kg.init(spec); kg.generateKey();
        } catch (Exception ex) { throw new IllegalStateException("No se pudo inicializar Android Keystore", ex); }
    }

    private SecretKey getDataKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); KeyStore.Entry entry = ks.getEntry(DATA_ALIAS, null);
        if (!(entry instanceof KeyStore.SecretKeyEntry)) throw new IllegalStateException("Clave de datos no disponible");
        return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
    }

    private static byte[] derive(char[] secret, byte[] salt, int iterations, int bits) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(secret, salt, iterations, bits);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); Arrays.fill(secret, '\0'); }
    }
    private static byte[] randomBytes(int n) { byte[] out = new byte[n]; RNG.nextBytes(out); return out; }
    private static void validatePin(String pin) { if (pin == null || !pin.matches("\\d{6,12}")) throw new IllegalArgumentException("El código debe contener entre 6 y 12 cifras"); }
    private static void validateBackupPassphrase(String p) { if (p == null || p.length() < 10) throw new IllegalArgumentException("La contraseña de copia debe tener al menos 10 caracteres"); }
}
