package com.airmusic.player.util;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Filesystem boundary checks shared by the local HTTP API and file transfers.
 *
 * <p>String prefix checks are not sufficient: {@code /MusicBackup} starts with
 * {@code /Music}. Canonical paths also resolve {@code ..} and symlinks before
 * the directory boundary is checked.
 */
public final class PathSecurity {

    private PathSecurity() {
    }

    /** Resolves a path whose final component may not exist yet (for uploads). */
    public static File normalizeForCreate(File file) throws IOException {
        if (file == null) throw new IOException("missing path");
        File absolute = file.getAbsoluteFile();
        File parent = absolute.getParentFile();
        if (parent == null) throw new IOException("missing parent");
        String name = absolute.getName();
        if (!isSafeFileName(name)) {
            throw new IOException("unsafe file name");
        }
        return new File(parent.getCanonicalFile(), name);
    }

    /** True only when {@code candidate} is strictly inside {@code root}. */
    public static boolean isInside(File root, File candidate) {
        return compareBoundary(root, candidate, false);
    }

    /** True when the candidate is the root itself or a descendant. */
    public static boolean isSameOrInside(File root, File candidate) {
        return compareBoundary(root, candidate, true);
    }

    private static boolean compareBoundary(File root, File candidate, boolean allowRoot) {
        if (root == null || candidate == null) return false;
        try {
            File rootReal = root.getCanonicalFile();
            File candidateReal = candidate.getCanonicalFile();
            String rootPath = rootReal.getPath();
            String candidatePath = candidateReal.getPath();
            if (File.separatorChar == '\\') {
                rootPath = rootPath.toLowerCase(java.util.Locale.ROOT);
                candidatePath = candidatePath.toLowerCase(java.util.Locale.ROOT);
            }
            String prefix = rootPath.endsWith(File.separator)
                    ? rootPath : rootPath + File.separator;
            return (allowRoot && candidatePath.equals(rootPath))
                    || candidatePath.startsWith(prefix);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    /** True when at least one configured root contains the file. */
    public static boolean isInsideAny(List<File> roots, File candidate) {
        if (roots == null || candidate == null) return false;
        for (File root : roots) {
            if (isInside(root, candidate)) return true;
        }
        return false;
    }

    /** Rejects path separators, control characters and reserved dot names. */
    public static boolean isSafeFileName(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        if (trimmed.isEmpty() || ".".equals(trimmed) || "..".equals(trimmed)) return false;
        if (trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0) return false;
        if (trimmed.indexOf('\0') >= 0) return false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;
        }
        return true;
    }

    /**
     * Creates the target directory when needed and returns its canonical path.
     * An existing symlink is intentionally resolved before it becomes a root.
     */
    public static File prepareRoot(File directory) throws IOException {
        if (directory == null) throw new IOException("missing directory");
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw new IOException("cannot create directory");
        }
        if (!directory.isDirectory()) throw new IOException("not a directory");
        return directory.getCanonicalFile();
    }

    /** Returns true when a path can be used as an ordinary readable file. */
    public static boolean isRegularFile(File file) {
        if (file == null) return false;
        try {
            File real = file.getCanonicalFile();
            return real.isFile() && real.canRead();
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }
}
