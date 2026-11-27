package com.adhook.plugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * Package-private storage/digest helpers shared by the plugin core.
 *
 * <p>All methods are caller-thread blocking; the plugin classes never spawn background work.
 * Commits use a sibling temporary file plus an atomic rename. When an atomic move is not
 * supported the operation fails instead of silently replacing user data through a non-atomic
 * fallback.</p>
 */
final class PluginFiles {

    private static final int IO_BUFFER = 8192;
    /** Upper bound used when reading a legacy store so a corrupt file cannot exhaust memory. */
    static final long MAX_STORE_BYTES = 8L * 1024 * 1024;

    private PluginFiles() {}

    static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static String sha256Hex(byte[] bytes) {
        if (bytes == null) return "";
        return hex(sha256Digest().digest(bytes));
    }

    static String sha256Hex(String text) {
        return text == null ? "" : sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("文件不存在");
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[IO_BUFFER];
        try (InputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    static String hex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0x0f, 16));
            builder.append(Character.forDigit(value & 0x0f, 16));
        }
        return builder.toString();
    }

    static String lowerHex(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    static byte[] readAll(File file, long maxBytes) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("文件不存在");
        long length = file.length();
        if (length < 0 || length > maxBytes) throw new IOException("文件超过体积预算");
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(length, 64 * 1024));
        byte[] buffer = new byte[IO_BUFFER];
        long total = 0L;
        try (InputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                total += count;
                if (total > maxBytes) throw new IOException("文件超过体积预算");
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    static String readText(File file, long maxBytes) throws IOException {
        return new String(readAll(file, maxBytes), StandardCharsets.UTF_8);
    }

    /** Creates the parent directory when needed; returns false when it cannot be used. */
    static boolean ensureDirectory(File directory) {
        if (directory == null) return false;
        if (directory.isDirectory()) return true;
        return directory.mkdirs() && directory.isDirectory();
    }

    /**
     * Writes {@code bytes} through a sibling temporary file with fsync and an atomic rename.
     * Returns false without touching the existing target when the atomic step is unavailable.
     */
    static boolean writeAtomically(File target, byte[] bytes) {
        if (target == null || bytes == null) return false;
        File parent = target.getParentFile();
        if (parent != null && !ensureDirectory(parent)) return false;
        File temporary = new File(parent, target.getName() + ".tmp-" + UUID.randomUUID());
        boolean wrote = false;
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
            wrote = true;
        } catch (IOException failure) {
            temporary.delete();
            return false;
        }
        if (!wrote) return false;
        try {
            try {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Same-directory rename only; still never a copy-then-delete on the live file.
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException failure) {
            temporary.delete();
            return false;
        }
    }

    static boolean writeAtomically(File target, String text) {
        return text != null && writeAtomically(target, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Renames a directory in the same parent; returns false when the rename fails. */
    static boolean renameDirectory(File source, File target) {
        if (source == null || target == null || !source.isDirectory()) return false;
        File parent = target.getParentFile();
        if (parent != null && !ensureDirectory(parent)) return false;
        try {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(source.toPath(), target.toPath());
            }
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    /** Recursively deletes a file or directory without following symbolic links. */
    static boolean deleteRecursively(File target) {
        if (target == null || !exists(target)) return true;
        try {
            if (Files.isSymbolicLink(target.toPath()) || !target.isDirectory()) {
                return Files.deleteIfExists(target.toPath());
            }
            File[] children = target.listFiles();
            boolean success = true;
            if (children != null) {
                for (File child : children) success = deleteRecursively(child) && success;
            }
            return target.delete() && success;
        } catch (IOException failure) {
            return false;
        }
    }

    private static boolean exists(File file) {
        try {
            return Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException failure) {
            return file.exists();
        }
    }

    /** Returns a non-existing sibling path with a timestamp/random suffix for preserved corrupt data. */
    static File uniqueSibling(File source, String suffix) {
        File parent = source.getParentFile();
        for (int attempt = 0; attempt < 8; attempt++) {
            String candidate = source.getName() + suffix + "-" + System.currentTimeMillis()
                    + (attempt == 0 ? "" : "-" + UUID.randomUUID().toString().substring(0, 8));
            File file = new File(parent, candidate);
            if (!exists(file)) return file;
        }
        return new File(parent, source.getName() + suffix + "-" + UUID.randomUUID());
    }

    static String base64(byte[] bytes) {
        return bytes == null ? "" : Base64.getEncoder().encodeToString(bytes);
    }

    /** Decodes standard Base64; returns null for malformed input. */
    static byte[] decodeBase64(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    /** Reads at most {@code maxBytes + 1} bytes from a stream and rejects overflow. */
    static byte[] readBounded(InputStream input, long maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[IO_BUFFER];
        long total = 0L;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            total += count;
            if (total > maxBytes) throw new IOException("数据超过体积预算");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
