package com.airmusic.player.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small credential vault backed by a non-exportable Android Keystore key.
 * Secrets are never written back to SharedPreferences as plaintext.
 */
public final class SecureCredentials {

    private static final String TAG = "SecureCredentials";
    private static final String STORE = "secure_credentials";
    private static final String KEY_ALIAS = "buka_credentials_v1";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private SecureCredentials() {
    }

    public static synchronized String get(Context context, String key) {
        if (context == null || key == null || key.isEmpty()) return "";
        SharedPreferences prefs = sp(context);
        String packed = prefs.getString(key, "");
        if (packed == null || packed.isEmpty()) return "";
        try {
            String[] parts = packed.split(":", 2);
            if (parts.length != 2) throw new IllegalArgumentException("malformed value");
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // A key can be invalidated by a device restore or lock-screen reset.
            // Fail closed and remove the unusable ciphertext.
            Log.w(TAG, "credential unreadable; clearing encrypted entry");
            prefs.edit().remove(key).apply();
            return "";
        }
    }

    public static synchronized boolean put(Context context, String key, String value) {
        if (context == null || key == null || key.isEmpty()) return false;
        SharedPreferences prefs = sp(context);
        if (value == null || value.isEmpty()) {
            prefs.edit().remove(key).apply();
            return true;
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String packed = Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            prefs.edit().putString(key, packed).apply();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "credential could not be encrypted");
            return false;
        }
    }

    public static synchronized void remove(Context context, String key) {
        if (context == null || key == null || key.isEmpty()) return;
        sp(context).edit().remove(key).apply();
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    /** Generates an unguessable token suitable for one device pairing. */
    public static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        }
        return out.toString();
    }
}
