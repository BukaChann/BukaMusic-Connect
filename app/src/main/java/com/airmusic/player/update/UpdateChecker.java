package com.airmusic.player.update;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

import androidx.core.content.FileProvider;

import com.airmusic.player.BuildConfig;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Secure updater: a trusted ECDSA-signed manifest identifies one HTTPS APK.
 * The APK size, SHA-256 digest and signing certificate are all verified before
 * the system installer is opened. No long-lived storage credentials ship in
 * the client.
 */
public final class UpdateChecker {

    private static final int MANIFEST_LIMIT = 64 * 1024;
    private static final int SIGNATURE_LIMIT = 16 * 1024;
    private static final long APK_LIMIT = 512L * 1024 * 1024;
    private static final Pattern SAFE_VERSION =
            Pattern.compile("[0-9A-Za-z][0-9A-Za-z._-]{0,31}");
    private static final Pattern SHA256 =
            Pattern.compile("[0-9a-fA-F]{64}");

    /** Verified contents of latest.json. */
    public static final class Info {
        public final int versionCode;
        public final String versionName;
        public final String url;
        public final String notes;
        public final long size;
        public final String sha256;

        Info(int versionCode, String versionName, String url, String notes,
             long size, String sha256) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.url = url;
            this.notes = notes;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public interface Callback {
        void onResult(Info info, String error);
    }

    public interface Progress {
        /** @return false when the user cancels. */
        boolean onProgress(int percent);
    }

    private UpdateChecker() {
    }

    public static int currentVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28
                    ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String currentVersionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Reads and cryptographically verifies the signed release manifest. */
    public static void check(final Callback callback) {
        new Thread(() -> {
            Info info = null;
            String error = null;
            try {
                info = readSignedManifest();
            } catch (Throwable t) {
                error = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final Info result = info;
            final String err = error;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(
                    () -> callback.onResult(result, err));
        }, "update-check").start();
    }

    private static Info readSignedManifest() throws Exception {
        String manifestUrl = BuildConfig.UPDATE_MANIFEST_URL == null
                ? "" : BuildConfig.UPDATE_MANIFEST_URL.trim();
        String publicKeyText = BuildConfig.UPDATE_MANIFEST_PUBLIC_KEY == null
                ? "" : BuildConfig.UPDATE_MANIFEST_PUBLIC_KEY.trim();
        if (manifestUrl.isEmpty() || publicKeyText.isEmpty()) {
            throw new IOException("signed update channel is not configured");
        }
        Set<String> hosts = allowedHosts();
        byte[] manifest = readBytesLimited(manifestUrl, MANIFEST_LIMIT, hosts);
        byte[] signature = readBytesLimited(manifestUrl + ".sig",
                SIGNATURE_LIMIT, hosts);
        verifyManifestSignature(manifest, signature, publicKeyText);

        JSONObject object = new JSONObject(new String(manifest, StandardCharsets.UTF_8));
        int versionCode = object.optInt("versionCode", 0);
        String versionName = object.optString("versionName", "").trim();
        String downloadUrl = object.optString("url", "").trim();
        String notes = object.optString("notes", "");
        long size = object.optLong("size", 0L);
        String digest = object.optString("sha256", "").trim();

        if (versionCode <= 0) throw new IOException("invalid versionCode");
        if (!SAFE_VERSION.matcher(versionName).matches()) {
            throw new IOException("invalid versionName");
        }
        if (notes.length() > 16000) notes = notes.substring(0, 16000);
        requireAllowedUrl(downloadUrl, hosts);
        if (size <= 0 || size > APK_LIMIT) throw new IOException("invalid update size");
        if (!SHA256.matcher(digest).matches()) throw new IOException("invalid SHA-256");
        return new Info(versionCode, versionName, downloadUrl, notes, size,
                digest.toLowerCase(Locale.ROOT));
    }

    /** Downloads, verifies and atomically publishes the APK in app storage. */
    public static File download(Context context, Info info, Progress progress)
            throws Exception {
        if (info == null) throw new IOException("missing update info");
        if (info.versionCode <= currentVersionCode(context)) {
            throw new IOException("update is not newer than installed version");
        }
        Set<String> hosts = allowedHosts();
        requireAllowedUrl(info.url, hosts);
        File dir = new File(context.getFilesDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
        long usable = dir.getUsableSpace();
        if (usable > 0 && usable < info.size + 16L * 1024 * 1024) {
            throw new IOException("not enough free space");
        }

        File part = new File(dir, "update.part");
        if (part.exists() && !part.delete()) throw new IOException("cannot reset temp file");
        HttpURLConnection connection = openAllowedConnection(info.url, hosts);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            long declared = contentLength(connection);
            if (declared > APK_LIMIT) throw new IOException("file too large");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long done = 0L;
            int lastPercent = -1;
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    done += count;
                    if (done > info.size || done > APK_LIMIT) {
                        throw new IOException("download exceeded declared size");
                    }
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    int percent = (int) Math.min(100L, done * 100L / info.size);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        if (progress != null && !progress.onProgress(percent)) {
                            throw new IOException("cancelled");
                        }
                    }
                }
                output.getFD().sync();
            }
            if (done != info.size) throw new IOException("download size mismatch");
            String actual = hex(digest.digest());
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                    info.sha256.getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("SHA-256 mismatch");
            }
            if (!verifyApkSigner(context, part)) {
                throw new IOException("APK signer mismatch");
            }

            File target = new File(dir, "BukaMusic-" + info.versionName + ".apk");
            if (!part.renameTo(target)) {
                if (target.exists() && !target.delete()) {
                    throw new IOException("cannot replace old APK");
                }
                if (!part.renameTo(target)) throw new IOException("atomic move failed");
            }
            File[] old = dir.listFiles();
            if (old != null) {
                for (File file : old) {
                    if (!file.equals(target) && file.isFile()) {
                        // Downloaded update files are disposable.
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                }
            }
            return target;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            connection.disconnect();
        }
    }

    private static boolean verifyApkSigner(Context context, File apk) {
        try {
            PackageManager manager = context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
            PackageInfo archive = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            byte[] expected = firstSigner(installed, flags);
            byte[] actual = firstSigner(archive, flags);
            return expected != null && actual != null
                    && MessageDigest.isEqual(expected, actual);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static byte[] firstSigner(PackageInfo info, int flags) {
        if (info == null) return null;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            Signature[] signers = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
            return signers != null && signers.length > 0
                    ? signers[0].toByteArray() : null;
        }
        return info.signatures != null && info.signatures.length > 0
                ? info.signatures[0].toByteArray() : null;
    }

    private static void verifyManifestSignature(byte[] manifest, byte[] signatureText,
                                                String publicKeyText) throws Exception {
        String base64 = new String(signatureText, StandardCharsets.US_ASCII)
                .replaceAll("\\s+", "");
        byte[] signature = Base64.decode(base64, Base64.NO_WRAP);
        String key = publicKeyText
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Base64.decode(key, Base64.NO_WRAP)));
        java.security.Signature verifier =
                java.security.Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(manifest);
        if (!verifier.verify(signature)) {
            throw new IOException("manifest signature invalid");
        }
    }

    private static byte[] readBytesLimited(String value, int limit, Set<String> hosts)
            throws IOException {
        HttpURLConnection connection = openAllowedConnection(value, hosts);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            long declared = contentLength(connection);
            if (declared > limit) throw new IOException("response too large");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    total += count;
                    if (total > limit) throw new IOException("response too large");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openAllowedConnection(String value, Set<String> hosts)
            throws IOException {
        String current = value;
        for (int redirect = 0; redirect < 6; redirect++) {
            URL url = new URL(current);
            requireAllowedUrl(url.toString(), hosts);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setUseCaches(false);
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) return connection;
            if (code == 301 || code == 302 || code == 303
                    || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("redirect without location");
                }
                current = new URL(url, location).toString();
                continue;
            }
            connection.disconnect();
            throw new IOException("HTTP " + code);
        }
        throw new IOException("too many redirects");
    }

    private static void requireAllowedUrl(String value, Set<String> hosts) throws IOException {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("HTTPS required");
        }
        if (url.getUserInfo() != null) throw new IOException("URL userinfo rejected");
        int port = url.getPort();
        if (port != -1 && port != 443) throw new IOException("non-standard port rejected");
        String host = url.getHost();
        if (host == null || !hosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IOException("download host not allowlisted");
        }
    }

    private static Set<String> allowedHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        String raw = BuildConfig.UPDATE_ALLOWED_HOSTS == null
                ? "" : BuildConfig.UPDATE_ALLOWED_HOSTS;
        for (String part : raw.split(",")) {
            String host = part.trim().toLowerCase(Locale.ROOT);
            if (!host.isEmpty()) hosts.add(host);
        }
        if (hosts.isEmpty()) throw new IllegalStateException("update host allowlist missing");
        return hosts;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return out.toString();
    }

    @SuppressWarnings("deprecation")
    private static long contentLength(HttpURLConnection connection) {
        return Build.VERSION.SDK_INT >= 24
                ? connection.getContentLengthLong()
                : connection.getContentLength();
    }

    public static void cleanDownloads(Context context) {
        File dir = new File(context.getFilesDir(), "update");
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    public static final int INSTALL_LAUNCHED = 0;
    public static final int INSTALL_NEED_PERMISSION = 1;
    public static final int INSTALL_NO_INSTALLER = 2;

    public static int install(Context context, File apk) {
        boolean allowed = canInstall(context);
        Uri uri = FileProvider.getUriForFile(context,
                "com.airmusic.player.fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return INSTALL_LAUNCHED;
        } catch (Throwable t) {
            android.util.Log.w("UpdateChecker", "install intent failed", t);
            return allowed ? INSTALL_NO_INSTALLER : INSTALL_NEED_PERMISSION;
        }
    }

    public static boolean canInstall(Context context) {
        return Build.VERSION.SDK_INT < 26
                || context.getPackageManager().canRequestPackageInstalls();
    }

    public static boolean openUnknownSourcesSettings(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Throwable ignored) {
            }
        }
        for (String action : new String[]{Settings.ACTION_SECURITY_SETTINGS,
                Settings.ACTION_APPLICATION_SETTINGS}) {
            try {
                context.startActivity(new Intent(action)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
