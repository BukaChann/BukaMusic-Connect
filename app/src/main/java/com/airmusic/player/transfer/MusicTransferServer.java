package com.airmusic.player.transfer;

import android.content.Context;
import android.util.Log;

import com.airmusic.player.library.AudioExt;
import com.airmusic.player.util.PathSecurity;
import com.airmusic.player.util.Prefs;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tiny embedded HTTP server that lets a phone on the same LAN upload music
 * files to this device by scanning a QR code or typing the IP:port into a
 * browser. Only the app's supported audio extensions are accepted.
 */
public final class MusicTransferServer {

    private static final String TAG = "MusicTransferServer";
    private static final int DEFAULT_PORT = 8080;
    private static final int MAX_THREADS = 8;
    private static final int MAX_QUEUED_REQUESTS = 16;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_HEADER_LINE_BYTES = 8 * 1024;
    private static final int MAX_JSON_BYTES = 256 * 1024;
    private static final long MAX_UPLOAD_BYTES = 512L * 1024 * 1024;
    private static final long MAX_CONCURRENT_UPLOAD_BYTES = 1024L * 1024 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 15000;
    private static final int REQUESTS_PER_WINDOW = 120;
    private static final long REQUEST_WINDOW_MS = 10000L;
    private static final int AUTH_FAILURES_PER_WINDOW = 5;
    private static final long AUTH_WINDOW_MS = 60000L;
    private static final long AUTH_BLOCK_MS = 60000L;
    /** One server per process; the background service keeps it alive. */
    private static MusicTransferServer shared;

    public interface Listener {
        void onUploaded(String fileName, boolean success, String message);
    }

    private final String musicFolderPath;
    private final byte[] iconPng;
    private final String language;
    /**
     * Everyone who wants to hear about finished uploads. The background service
     * registers the "refresh the library" listener and the transfer screen adds
     * its toast on top, so an upload from the desktop console or from the web
     * page always refreshes the library on the device.
     */
    private final java.util.List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, RequestWindow> requestWindows =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AuthWindow> authWindows =
            new ConcurrentHashMap<>();
    private final AtomicLong lastRateCleanup = new AtomicLong();
    private final AtomicLong activeUploadBytes = new AtomicLong();
    private ServerSocket serverSocket;
    private ThreadPoolExecutor executor;
    private int port = DEFAULT_PORT;
    private Context context;
    private DeviceDiscovery discovery;
    private volatile String cachedAccessToken;

    public MusicTransferServer(String musicFolderPath, byte[] iconPng, String language,
                               Listener listener) {
        this.musicFolderPath = musicFolderPath;
        this.iconPng = iconPng;
        this.language = language == null ? "zh" : language;
        addListener(listener);
    }

    /** Registers an upload listener (the same listener twice is ignored). */
    public void addListener(Listener value) {
        if (value != null && !listeners.contains(value)) listeners.add(value);
    }

    /**
     * 注销上传监听。页面的监听器必须在 onDestroy 里撤掉：服务器是常驻单例，
     * 留在列表里会把已经结束的页面永久持有（整个页面 + 位图都放不掉）。
     */
    public void removeListener(Listener value) {
        if (value != null) listeners.remove(value);
    }

    private void notifyUploaded(String fileName, boolean success, String message) {
        for (Listener target : listeners) {
            try {
                target.onUploaded(fileName, success, message);
            } catch (Throwable t) {
                Log.w(TAG, "upload listener failed", t);
            }
        }
    }

    /** Shared instance used by the background service and the transfer screen. */
    public static synchronized MusicTransferServer shared(Context context, byte[] iconPng,
                                                         String language, Listener listener) {
        if (shared == null) {
            shared = new MusicTransferServer(new Prefs(context).getMusicFolderPath(),
                    iconPng, language, listener);
        }
        shared.context = context.getApplicationContext();
        return shared;
    }

    public static synchronized MusicTransferServer sharedOrNull() {
        return shared;
    }

    public String getMusicFolderPath() {
        if (context != null) {
            String current = new Prefs(context).getMusicFolderPath();
            if (current != null && !current.isEmpty()) return current;
        }
        return musicFolderPath;
    }

    /** Starts listening; returns the actual port (0 on failure). */
    public synchronized int start() {
        if (running.get()) return port;
        int attemptPort = DEFAULT_PORT;
        while (attemptPort < DEFAULT_PORT + 20) {
            try {
                serverSocket = new ServerSocket(attemptPort);
                port = attemptPort;
                break;
            } catch (IOException e) {
                attemptPort++;
            }
        }
        if (serverSocket == null) {
            Log.e(TAG, "no free port");
            return 0;
        }
        running.set(true);
        executor = new ThreadPoolExecutor(2, MAX_THREADS, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_REQUESTS),
                runnable -> {
                    Thread thread = new Thread(runnable, "buka-http");
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, pool) -> {
                    // The accept loop owns the socket; overload is handled there.
                    throw new java.util.concurrent.RejectedExecutionException("queue full");
                });
        executor.execute(this::acceptLoop);
        if (context != null) {
            discovery = new DeviceDiscovery(context, this);
            discovery.start();
        }
        Log.i(TAG, "transfer server listening on " + port);
        return port;
    }

    public synchronized void stop() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
    }

    public int getPort() {
        return port;
    }

    /** Pairing token shown by the device and accepted as a bearer credential. */
    public String getAccessToken() {
        if (context == null) throw new IllegalStateException("server context missing");
        String token = cachedAccessToken;
        if (token != null && !token.isEmpty()) return token;
        synchronized (this) {
            if (cachedAccessToken == null || cachedAccessToken.isEmpty()) {
                cachedAccessToken = new Prefs(context).getControlToken();
            }
            return cachedAccessToken;
        }
    }

    /** Invalidates every previously paired client and returns the new token. */
    public synchronized String rotateAccessToken() {
        if (context == null) throw new IllegalStateException("server context missing");
        cachedAccessToken = new Prefs(context).rotateControlToken();
        return cachedAccessToken;
    }

    private String accessToken() {
        return getAccessToken();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                try {
                    executor.execute(() -> handle(socket));
                } catch (java.util.concurrent.RejectedExecutionException overloaded) {
                    try {
                        sendResponse(socket.getOutputStream(), 503, "text/plain",
                                "Server busy".getBytes(StandardCharsets.UTF_8), false);
                    } catch (IOException ignored) {
                    } finally {
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "accept failed", e);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            String remote = socket.getInetAddress() == null
                    ? "unknown" : socket.getInetAddress().getHostAddress();
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            RequestHead request;
            try {
                request = readRequestHead(in);
            } catch (HeaderLimitException e) {
                sendResponse(out, 431, "text/plain", "Request headers too large",
                        false);
                return;
            }
            if (request == null) return;
            if (!allowRequest(remote)) {
                sendResponse(out, 429, "text/plain", "Too many requests", false);
                return;
            }

            String path = request.path;
            String pathOnly = path == null ? "" : path;
            int question = pathOnly.indexOf('?');
            String query = question < 0 ? "" : pathOnly.substring(question + 1);
            if (question >= 0) pathOnly = pathOnly.substring(0, question);
            String method = request.method;
            String range = request.headers.get("range");
            boolean apiRequest = pathOnly.startsWith("/api/");
            boolean infoRequest = apiRequest && "/api/info".equals(pathOnly)
                    && "GET".equals(method);
            String queryToken = queryParameter(query, "token");

            if ("GET".equals(method) && "/".equals(pathOnly)
                    && queryToken != null && !queryToken.isEmpty()) {
                if (!isTokenValid(queryToken) || isAuthBlocked(remote)) {
                    recordAuthFailure(remote);
                    sendResponse(out, 401, "text/plain", "Pairing credential invalid",
                            false);
                    return;
                }
                String cookie = "buka_pair=" + accessToken()
                        + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=604800";
                sendRedirect(out, "/", cookie);
                return;
            }

            boolean authorized = requestIsAuthorized(request, remote);
            if (!infoRequest && !authorized) {
                if ("/icon.png".equals(pathOnly)) {
                    serveIcon(out);
                    return;
                }
                if (request.headers.get("authorization") != null
                        || request.headers.get("cookie") != null) {
                    recordAuthFailure(remote);
                }
                sendResponse(out, 401, "text/plain",
                        "Pair this device before using the service", false);
                return;
            }

            if ("GET".equals(method) && "/icon.png".equals(pathOnly)) {
                serveIcon(out);
            } else if ("GET".equals(method) && "/".equals(pathOnly)) {
                servePage(out);
            } else if ("GET".equals(method) && apiRequest) {
                if (!ControlApi.handle(context, method, path, null, out, range)) {
                    sendResponse(out, 404, "text/plain", "Not found",
                            false);
                }
            } else if ("POST".equals(method) && apiRequest) {
                long contentLength = contentLength(request);
                if (contentLength < 0 || contentLength > MAX_JSON_BYTES) {
                    sendResponse(out, 413, "text/plain", "Request body too large",
                            false);
                    return;
                }
                byte[] body = readBody(in, (int) contentLength);
                if (!ControlApi.handle(context, method, path, body, out, range)) {
                    sendResponse(out, 404, "text/plain", "Not found",
                            false);
                }
            } else if ("POST".equals(method) && "/upload".equals(pathOnly)) {
                long contentLength = contentLength(request);
                if (contentLength <= 0) {
                    sendResponse(out, 411, "text/plain", "Content-Length required",
                            false);
                    return;
                }
                if (contentLength > MAX_UPLOAD_BYTES) {
                    sendResponse(out, 413, "text/plain", "File too large", false);
                    return;
                }
                String encodedName = request.headers.get("x-file-name");
                String fileName = encodedName == null ? ""
                        : URLDecoder.decode(encodedName.trim(), "UTF-8");
                long active = activeUploadBytes.addAndGet(contentLength);
                if (active > MAX_CONCURRENT_UPLOAD_BYTES) {
                    activeUploadBytes.addAndGet(-contentLength);
                    sendResponse(out, 429, "text/plain",
                            "Too many concurrent uploads", false);
                    return;
                }
                try {
                    handleUpload(fileName, in, contentLength, out);
                } finally {
                    activeUploadBytes.addAndGet(-contentLength);
                }
            } else if (apiRequest) {
                sendResponse(out, 405, "text/plain", "Method not allowed", false);
            } else {
                sendResponse(out, 404, "text/plain", "Not found", false);
            }
            out.flush();
        } catch (Exception e) {
            // Deliberately do not include request headers or credentials in logs.
            Log.w(TAG, "request failed: " + e.getClass().getSimpleName());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleUpload(String fileName, InputStream in, long length, OutputStream out)
            throws IOException {
        String cleanName = fileName == null ? "" : fileName.trim();
        if (!PathSecurity.isSafeFileName(cleanName)) {
            String msg = t(language, "文件名或内容为空", "File name or content is empty",
                    "ファイル名または内容が空です", "파일 이름 또는 내용이 비어 있습니다");
            sendJson(out, 400, "ERR:" + msg);
            notifyUploaded(cleanName, false, msg);
            return;
        }
        if (!AudioExt.isAudio(cleanName)) {
            String msg = t(language, "仅支持音乐文件：", "Music files only: ",
                    "音楽ファイルのみ：", "음악 파일만 지원: ") + AudioExt.supportedList();
            sendJson(out, 415, "ERR:" + msg);
            notifyUploaded(cleanName, false,
                    t(language, "不支持的文件格式", "Unsupported file format",
                            "未対応のファイル形式", "지원하지 않는 파일 형식"));
            return;
        }
        String folder = getMusicFolderPath();
        File requestedDir = folder == null || folder.isEmpty()
                ? new File(android.os.Environment.getExternalStorageDirectory(), "Music")
                : new File(folder);
        final File dir;
        try {
            dir = PathSecurity.prepareRoot(requestedDir);
        } catch (IOException e) {
            String msg = t(language, "无法创建音乐目录", "Cannot create the music folder",
                    "音楽フォルダを作成できません", "음악 폴더를 만들 수 없습니다");
            sendJson(out, 500, "ERR:" + msg);
            notifyUploaded(cleanName, false, msg);
            return;
        }
        long usable = dir.getUsableSpace();
        if (usable - com.airmusic.player.util.StorageHelper.MIN_FREE_BYTES < length) {
            String msg = t(language, "上传后剩余空间将不足80MB，已取消上传",
                    "Uploading this file would leave less than 80 MB free; upload cancelled.",
                    "このファイルをアップロードすると空き容量が80MB未満になるため、アップロードをキャンセルしました",
                    "이 파일을 업로드하면 남은 공간이 80MB 미만이 되어 업로드가 취소되었습니다");
            sendJson(out, 507, "ERR:" + msg);
            notifyUploaded(cleanName, false, msg);
            return;
        }

        File target = new File(dir, cleanName);
        File targetPath;
        try {
            targetPath = PathSecurity.normalizeForCreate(target);
            if (!dir.equals(targetPath.getParentFile())) {
                throw new IOException("target escaped music root");
            }
        } catch (IOException e) {
            String msg = t(language, "上传路径无效", "Invalid upload path",
                    "アップロード先が無効です", "업로드 경로가 올바르지 않습니다");
            sendJson(out, 400, "ERR:" + msg);
            notifyUploaded(cleanName, false, msg);
            return;
        }

        File partial = File.createTempFile(".buka-upload-", ".part", dir);
        try {
            try (FileOutputStream fos = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                long remaining = length;
                while (remaining > 0) {
                    int read = in.read(buffer, 0,
                            (int) Math.min(buffer.length, remaining));
                    if (read < 0) throw new EOFException("upload interrupted");
                    fos.write(buffer, 0, read);
                    remaining -= read;
                }
                fos.getFD().sync();
            }
            moveIntoPlace(partial, targetPath);
            String msg = t(language, "上传成功：", "Upload succeeded: ",
                    "アップロード成功：", "업로드 성공: ") + target.getName();
            sendJson(out, 200, "OK:" + msg);
            notifyUploaded(target.getName(), true,
                    t(language, "上传成功", "Upload succeeded",
                            "アップロード成功", "업로드 성공"));
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            String msg = t(language, "上传中断或写入失败", "Upload interrupted or could not be saved",
                    "アップロードが中断されたか保存できませんでした",
                    "업로드가 중단되었거나 저장하지 못했습니다");
            sendJson(out, 400, "ERR:" + msg);
            notifyUploaded(cleanName, false, msg);
        }
    }

    private static void moveIntoPlace(File partial, File target) throws IOException {
        // POSIX rename replaces an existing file atomically.
        if (partial.renameTo(target)) return;
        if (target.exists() && !target.delete()) {
            throw new IOException("cannot replace existing file");
        }
        if (!partial.renameTo(target)) throw new IOException("move failed");
    }

    private void servePage(OutputStream out) throws IOException {
        byte[] page = buildPage().getBytes(StandardCharsets.UTF_8);
        sendResponse(out, 200, "text/html; charset=utf-8", page);
    }

    private void serveIcon(OutputStream out) throws IOException {
        if (iconPng == null || iconPng.length == 0) {
            sendResponse(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        sendResponse(out, 200, "image/png", iconPng);
    }

    private static void sendJson(OutputStream out, int code, String message) throws IOException {
        sendResponse(out, code, "text/plain; charset=utf-8",
                message.getBytes(StandardCharsets.UTF_8), false);
    }

    private static void sendResponse(OutputStream out, int code, String contentType, byte[] body)
            throws IOException {
        sendResponse(out, code, contentType, body, true);
    }

    private static void sendResponse(OutputStream out, int code, String contentType, String body,
                                     boolean authenticated) throws IOException {
        sendResponse(out, code, contentType, body.getBytes(StandardCharsets.UTF_8),
                authenticated);
    }

    private static void sendResponse(OutputStream out, int code, String contentType, byte[] body,
                                     boolean authenticated) throws IOException {
        String head = "HTTP/1.1 " + code + " " + statusText(code) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + (authenticated
                    ? "Content-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; "
                    + "style-src 'unsafe-inline'; img-src 'self' data:\r\n"
                    : "")
                + "\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static void sendRedirect(OutputStream out, String location, String cookie)
            throws IOException {
        String head = "HTTP/1.1 303 See Other\r\n"
                + "Location: " + location + "\r\n"
                + "Set-Cookie: " + cookie + "\r\n"
                + "Content-Length: 0\r\n"
                + "Cache-Control: no-store\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static byte[] readBody(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int r = in.read(body, read, length - read);
            if (r < 0) throw new EOFException("request body interrupted");
            read += r;
        }
        return body;
    }

    private static RequestHead readRequestHead(InputStream in)
            throws IOException, HeaderLimitException {
        String first = readHeaderLine(in);
        if (first == null || first.isEmpty()) return null;
        String[] parts = first.split(" ", 3);
        if (parts.length < 2) throw new HeaderLimitException();
        RequestHead result = new RequestHead(parts[0], parts[1]);
        int total = first.length();
        for (int count = 0; count < 128; count++) {
            String line = readHeaderLine(in);
            if (line == null) throw new EOFException("headers interrupted");
            if (line.isEmpty()) return result;
            total += line.length();
            if (total > MAX_HEADER_BYTES) throw new HeaderLimitException();
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
            result.headers.put(name, line.substring(colon + 1).trim());
        }
        throw new HeaderLimitException();
    }

    private static String readHeaderLine(InputStream in) throws IOException,
            HeaderLimitException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int value = in.read();
            if (value < 0) return buffer.size() == 0 ? null
                    : new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
            if (value == '\n') {
                byte[] bytes = buffer.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') length--;
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            if (buffer.size() >= MAX_HEADER_LINE_BYTES) throw new HeaderLimitException();
            buffer.write(value);
        }
    }

    private static long contentLength(RequestHead request) {
        String value = request.headers.get("content-length");
        if (value == null || value.trim().isEmpty()) return -1;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private boolean requestIsAuthorized(RequestHead request, String remote) {
        if (isAuthBlocked(remote)) return false;
        String authorization = request.headers.get("authorization");
        if (authorization != null) {
            String prefix = "Bearer ";
            if (authorization.regionMatches(true, 0, prefix, 0, prefix.length())
                    && isTokenValid(authorization.substring(prefix.length()).trim())) {
                return true;
            }
        }
        String cookie = request.headers.get("cookie");
        if (cookie == null) return false;
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("buka_pair=")
                    && isTokenValid(trimmed.substring("buka_pair=".length()))) {
                return true;
            }
        }
        return false;
    }

    private boolean isTokenValid(String candidate) {
        if (candidate == null || candidate.isEmpty()) return false;
        byte[] expected = accessToken().getBytes(StandardCharsets.UTF_8);
        byte[] actual = candidate.trim().toLowerCase(java.util.Locale.ROOT)
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private boolean allowRequest(String remote) {
        long now = System.currentTimeMillis();
        RequestWindow window = requestWindows.get(remote);
        if (window == null) {
            RequestWindow created = new RequestWindow(now);
            RequestWindow existing = requestWindows.putIfAbsent(remote, created);
            window = existing == null ? created : existing;
        }
        synchronized (window) {
            if (now - window.startedAt >= REQUEST_WINDOW_MS) {
                window.startedAt = now;
                window.count = 0;
            }
            if (++window.count > REQUESTS_PER_WINDOW) return false;
        }
        cleanupRateWindows(now);
        return true;
    }

    private boolean isAuthBlocked(String remote) {
        AuthWindow window = authWindows.get(remote);
        if (window == null) return false;
        synchronized (window) {
            return nowBlocked(window, System.currentTimeMillis());
        }
    }

    private void recordAuthFailure(String remote) {
        long now = System.currentTimeMillis();
        AuthWindow window = authWindows.get(remote);
        if (window == null) {
            AuthWindow created = new AuthWindow(now);
            AuthWindow existing = authWindows.putIfAbsent(remote, created);
            window = existing == null ? created : existing;
        }
        synchronized (window) {
            if (now - window.startedAt >= AUTH_WINDOW_MS) {
                window.startedAt = now;
                window.failures = 0;
                window.blockedUntil = 0L;
            }
            if (++window.failures >= AUTH_FAILURES_PER_WINDOW) {
                window.blockedUntil = now + AUTH_BLOCK_MS;
            }
        }
    }

    private static boolean nowBlocked(AuthWindow window, long now) {
        if (window.blockedUntil > now) return true;
        if (window.blockedUntil != 0L) {
            window.blockedUntil = 0L;
            window.failures = 0;
            window.startedAt = now;
        }
        return false;
    }

    private void cleanupRateWindows(long now) {
        if (now - lastRateCleanup.get() < 60000L
                || !lastRateCleanup.compareAndSet(lastRateCleanup.get(), now)) {
            return;
        }
        java.util.Iterator<Map.Entry<String, RequestWindow>> requests =
                requestWindows.entrySet().iterator();
        while (requests.hasNext()) {
            Map.Entry<String, RequestWindow> entry = requests.next();
            RequestWindow window = entry.getValue();
            synchronized (window) {
                if (now - window.startedAt > REQUEST_WINDOW_MS * 2) requests.remove();
            }
        }
        java.util.Iterator<Map.Entry<String, AuthWindow>> auth =
                authWindows.entrySet().iterator();
        while (auth.hasNext()) {
            Map.Entry<String, AuthWindow> entry = auth.next();
            AuthWindow window = entry.getValue();
            synchronized (window) {
                if (now - window.startedAt > AUTH_WINDOW_MS
                        && window.blockedUntil <= now) {
                    auth.remove();
                }
            }
        }
    }

    private static String queryParameter(String query, String name) {
        if (query == null || query.isEmpty()) return null;
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            if (equals <= 0) continue;
            if (name.equals(part.substring(0, equals))) {
                try {
                    return URLDecoder.decode(part.substring(equals + 1), "UTF-8");
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 303: return "See Other";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 429: return "Too Many Requests";
            case 431: return "Request Header Fields Too Large";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            case 507: return "Insufficient Storage";
            default: return "Error";
        }
    }

    private static final class RequestHead {
        final String method;
        final String path;
        final Map<String, String> headers = new HashMap<>();

        RequestHead(String method, String path) {
            this.method = method;
            this.path = path;
        }
    }

    private static final class HeaderLimitException extends Exception {
    }

    private static final class RequestWindow {
        long startedAt;
        int count;

        RequestWindow(long startedAt) {
            this.startedAt = startedAt;
        }
    }

    private static final class AuthWindow {
        long startedAt;
        long blockedUntil;
        int failures;

        AuthWindow(long startedAt) {
            this.startedAt = startedAt;
        }
    }

    private static String t(String lang, String zh, String en, String ja, String ko) {
        if ("en".equals(lang)) return en;
        if ("ja".equals(lang)) return ja;
        if ("ko".equals(lang)) return ko;
        return zh;
    }

    private String buildPage() {
        String lang = language == null ? "zh" : language;
        String title = t(lang, "BukaMusic 音乐传输", "BukaMusic Music Transfer",
                "BukaMusic 音楽転送", "BukaMusic 음악 전송");
        String h2 = t(lang, "上传音乐到设备", "Upload Music to Device",
                "音楽をデバイスにアップロード", "기기로 음악 업로드");
        String desc = t(lang,
                "选择或拖拽音乐文件到下方，确认后上传。仅支持音乐格式，其他文件会自动跳过。",
                "Select or drag music files below, then confirm to upload. Only music formats are supported; other files are skipped automatically.",
                "音楽ファイルを選択またはドラッグして、アップロードを確認してください。音楽形式のみ対応で、他のファイルは自動的にスキップされます。",
                "음악 파일을 선택하거나 아래로 끌어다 놓은 뒤 업로드를 확인하세요. 음악 형식만 지원되며 다른 파일은 자동으로 건너뜁니다.");
        String dropText = t(lang, "点击选择文件，或将文件拖拽到此处",
                "Click to choose files, or drag files here",
                "クリックしてファイルを選択、またはここにドラッグ",
                "클릭하여 파일 선택 또는 여기로 끌어다 놓기");
        String upload = t(lang, "上传", "Upload", "アップロード", "업로드");
        String hint = t(lang,
                "仅支持音乐格式，其他文件会被拒绝。上传完成后曲库自动刷新。",
                "Music formats only; other files are rejected. The library refreshes automatically after uploads.",
                "音楽形式のみ対応です。他のファイルは拒否されます。アップロード完了後にライブラリが自動更新されます。",
                "음악 형식만 지원되며 다른 파일은 거부됩니다. 업로드가 완료되면 라이브러리가 자동으로 새로고침됩니다.");
        String tplSelected = t(lang, "已选择 {1} 个文件", "{1} file(s) selected",
                "{1} 個のファイルを選択", "파일 {1}개 선택됨");
        String tplUnsupported = t(lang, "{1}：不支持的文件格式，已跳过", "{1}: unsupported file format, skipped",
                "{1}：未対応のファイル形式のためスキップしました", "{1}: 지원하지 않는 파일 형식이라 건너뜀");
        String tplAlready = t(lang, "{1}：已在列表中", "{1}: already in the list",
                "{1}：リストに既にあります", "{1}: 이미 목록에 있음");
        String tplAdded = t(lang, "已添加 {1} 个文件", "Added {1} file(s)",
                "{1} 個のファイルを追加しました", "파일 {1}개 추가됨");
        String selectFirst = t(lang, "请先选择文件", "Please select files first",
                "ファイルを選択してください", "먼저 파일을 선택하세요");
        String tplUploading = t(lang, "正在上传 ({1}/{2}) {3} {4}%", "Uploading ({1}/{2}) {3} {4}%",
                "アップロード中 ({1}/{2}) {3} {4}%", "업로드 중 ({1}/{2}) {3} {4}%");
        String uploadSucceeded = t(lang, "上传成功", "Upload succeeded",
                "アップロード成功", "업로드 성공");
        String uploadFailed = t(lang, "上传失败", "Upload failed",
                "アップロード失敗", "업로드 실패");
        String tplDone = t(lang, "全部完成：成功 {1}，失败 {2}", "All done: {1} succeeded, {2} failed",
                "すべて完了：成功 {1}、失敗 {2}", "모두 완료: 성공 {1}, 실패 {2}");

        String js = "var exts=['mp3','flac','m4a','m4b','aac','ogg','oga','opus','wav','ape','wma','aiff','aif'];"
                + "var queue=[],uploading=false;"
                + "var drop=document.getElementById('drop'),file=document.getElementById('file'),"
                + "btn=document.getElementById('btn'),filesEl=document.getElementById('files'),"
                + "status=document.getElementById('status'),hint=document.getElementById('hint'),"
                + "prog=document.getElementById('progress'),bar=document.getElementById('bar'),"
                + "ptxt=document.getElementById('ptxt'),toasts=document.getElementById('toasts');"
                + "function fmt(s){var a=Array.prototype.slice.call(arguments,1);"
                + "return s.replace(/\\{(\\d)\\}/g,function(m,n){return a[n-1]!==undefined?a[n-1]:m})}"
                + "function fmtSize(n){if(n<1024)return n+' B';if(n<1048576)return(n/1024).toFixed(1)+' KB';"
                + "return(n/1048576).toFixed(1)+' MB'}"
                + "function toast(msg,ok){var t=document.createElement('div');t.className='toast '+(ok?'ok':'err');"
                + "t.textContent=msg;toasts.appendChild(t);"
                + "setTimeout(function(){t.classList.add('hide');setTimeout(function(){t.remove()},350)},3200)}"
                + "function renderFiles(){filesEl.innerHTML='';"
                + "if(queue.length){filesEl.style.display='block'}else{filesEl.style.display='none'}"
                + "queue.forEach(function(item,i){"
                + "var row=document.createElement('div');row.className='file';"
                + "var nm=document.createElement('span');nm.className='nm';nm.textContent=item.name;nm.title=item.name;"
                + "var sz=document.createElement('span');sz.className='sz';sz.textContent=fmtSize(item.size);"
                + "var x=document.createElement('button');x.className='x';x.textContent='✕';"
                + "x.onclick=function(){if(uploading)return;queue.splice(i,1);renderFiles()};"
                + "row.appendChild(nm);row.appendChild(sz);row.appendChild(x);filesEl.appendChild(row)});"
                + "hint.textContent=queue.length?fmt('" + tplSelected + "',queue.length):''}"
                + "function addFiles(list){var added=0;"
                + "for(var i=0;i<list.length;i++){var f=list[i];"
                + "var ok=exts.indexOf(f.name.split('.').pop().toLowerCase())>=0;"
                + "if(!ok){toast(fmt('" + tplUnsupported + "',f.name),false);continue}"
                + "var dup=queue.some(function(q){return q.name===f.name&&q.size===f.size});"
                + "if(dup){toast(fmt('" + tplAlready + "',f.name),false);continue}"
                + "queue.push({name:f.name,size:f.size,file:f});added++}"
                + "renderFiles();if(added)toast(fmt('" + tplAdded + "',added),true)}"
                + "drop.onclick=function(){file.click()};"
                + "file.onchange=function(){addFiles(file.files);file.value=''};"
                + "drop.ondragover=function(e){e.preventDefault();drop.classList.add('hover')};"
                + "drop.ondragleave=function(){drop.classList.remove('hover')};"
                + "drop.ondrop=function(e){e.preventDefault();drop.classList.remove('hover');addFiles(e.dataTransfer.files)};"
                + "btn.onclick=function(){"
                + "if(!queue.length){toast('" + selectFirst + "',false);return}"
                + "uploading=true;btn.disabled=true;var total=queue.length,done=0,okCount=0,failCount=0;"
                + "prog.style.display='block';"
                + "function next(){"
                + "if(done>=total){uploading=false;btn.disabled=false;prog.style.display='none';bar.style.width='0%';"
                + "var msg=fmt('" + tplDone + "',okCount,failCount);"
                + "status.textContent=msg;status.className='status '+(failCount?'err':'');"
                + "toast(msg,failCount===0);"
                + "queue=[];renderFiles();return}"
                + "var item=queue[done];"
                + "var xhr=new XMLHttpRequest();"
                + "xhr.open('POST','/upload');"
                + "xhr.setRequestHeader('X-File-Name',encodeURIComponent(item.name));"
                + "xhr.upload.onprogress=function(e){if(e.lengthComputable){"
                + "var pct=Math.round(e.loaded/e.total*100);"
                + "bar.style.width=pct+'%';ptxt.textContent=fmt('" + tplUploading + "',done+1,total,item.name,pct)}};"
                + "xhr.onload=function(){var t=xhr.responseText||'';var ok=xhr.status===200&&t.indexOf('OK:')===0;"
                + "var msg=ok?t.substring(3):t.substring(4);"
                + "if(ok)okCount++;else failCount++;"
                + "toast(msg,ok);"
                + "done++;bar.style.width='0%';next()};"
                + "xhr.onerror=function(){failCount++;done++;toast(item.name+'："+ uploadFailed + "',false);"
                + "bar.style.width='0%';next()};"
                + "xhr.send(item.file)}"
                + "next()};";

        String css = "*{box-sizing:border-box;margin:0;padding:0}"
                + "html,body{height:100%}"
                + "body{font-family:-apple-system,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;"
                + "background:linear-gradient(160deg,#16304F 0%,#081120 100%);min-height:100vh;"
                + "display:flex;align-items:center;justify-content:center;color:#fff;padding:28px 20px}"
                + ".top{position:fixed;top:0;left:0;padding:20px 24px;font-size:22px;font-weight:700;color:#4FC3F7;"
                + "text-shadow:0 2px 8px rgba(79,195,247,.35)}"
                + ".wrap{width:100%;max-width:560px;margin:auto}"
                + ".card{background:#12233D;border:1px solid rgba(79,195,247,.25);border-radius:16px;"
                + "padding:28px;box-shadow:0 8px 28px rgba(0,0,0,.35)}"
                + "h2{font-size:18px;margin-bottom:6px;color:#81D4FA}"
                + "p{color:#A8C3E0;font-size:14px;line-height:1.7;margin-bottom:18px}"
                + ".drop{border:2px dashed rgba(79,195,247,.6);border-radius:12px;padding:34px 16px;"
                + "text-align:center;color:#A8C3E0;font-size:15px;cursor:pointer;transition:.2s;background:rgba(79,195,247,.05)}"
                + ".drop.hover{border-color:#4FC3F7;background:rgba(79,195,247,.12);color:#fff}"
                + ".files{margin-top:12px;display:none;max-height:190px;overflow-y:auto;padding-right:2px}"
                + ".file{display:flex;align-items:center;gap:8px;background:rgba(79,195,247,.08);"
                + "border:1px solid rgba(79,195,247,.2);border-radius:10px;padding:8px 10px;margin-top:8px;font-size:13px}"
                + ".file .nm{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#E1F0FB}"
                + ".file .sz{color:#7FA6C8;font-size:12px;white-space:nowrap}"
                + ".file .x{width:24px;height:24px;border-radius:50%;border:0;flex:none;cursor:pointer;"
                + "background:rgba(255,138,128,.16);color:#FF8A80;font-size:12px;line-height:1}"
                + ".file .x:hover{background:rgba(255,138,128,.38)}"
                + ".btn{display:inline-block;margin-top:16px;background:#4FC3F7;color:#081120;border:0;"
                + "padding:12px 26px;border-radius:999px;font-size:15px;font-weight:700;cursor:pointer;transition:.2s}"
                + ".btn:hover{background:#81D4FA}.btn:disabled{opacity:.5;cursor:default}"
                + ".progress{display:none;margin-top:16px}"
                + ".track{height:8px;border-radius:999px;background:rgba(255,255,255,.1);overflow:hidden}"
                + ".bar{height:100%;width:0;background:linear-gradient(90deg,#4FC3F7,#81D4FA);border-radius:999px;transition:width .15s}"
                + ".ptxt{margin-top:8px;font-size:13px;color:#81D4FA;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                + ".status{margin-top:14px;font-size:14px;min-height:20px;color:#81D4FA}"
                + ".status.err{color:#FF8A80}"
                + ".hint{color:#607D8B;font-size:12px;margin-top:10px}"
                + "#toasts{position:fixed;top:16px;right:16px;display:flex;flex-direction:column;gap:8px;"
                + "z-index:99;max-width:320px}"
                + ".toast{padding:10px 14px;border-radius:10px;font-size:13px;color:#fff;background:#12233D;"
                + "border:1px solid rgba(255,255,255,.14);box-shadow:0 6px 20px rgba(0,0,0,.35);animation:slideIn .22s ease}"
                + ".toast.ok{border-color:rgba(105,240,174,.5);color:#A5F3C7}"
                + ".toast.err{border-color:rgba(255,138,128,.5);color:#FF8A80}"
                + ".toast.hide{opacity:0;transform:translateX(12px);transition:.3s}"
                + "@keyframes slideIn{from{opacity:0;transform:translateY(-6px)}to{opacity:1;transform:none}}";

        return "<!DOCTYPE html><html lang=\"" + lang + "\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + "</title>"
                + "<link rel=\"icon\" type=\"image/png\" href=\"/icon.png\">"
                + "<style>" + css + "</style></head><body>"
                + "<div class=\"top\">BukaMusic</div>"
                + "<div class=\"wrap\"><div class=\"card\">"
                + "<h2>" + h2 + "</h2><p>" + desc + "</p>"
                + "<div class=\"drop\" id=\"drop\">" + dropText + "</div>"
                + "<input type=\"file\" id=\"file\" multiple hidden>"
                + "<div class=\"files\" id=\"files\"></div>"
                + "<button class=\"btn\" id=\"btn\">" + upload + "</button>"
                + "<div class=\"progress\" id=\"progress\"><div class=\"track\"><div class=\"bar\" id=\"bar\"></div></div>"
                + "<div class=\"ptxt\" id=\"ptxt\"></div></div>"
                + "<div class=\"status\" id=\"status\"></div>"
                + "<div class=\"hint\" id=\"hint\"></div>"
                + "</div></div>"
                + "<div id=\"toasts\"></div>"
                + "<script>" + js + "</script></body></html>";
    }
}
