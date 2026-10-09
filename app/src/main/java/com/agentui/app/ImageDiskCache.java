package com.agentui.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/** One process-wide owner serializes publication, eviction and readers. Call off UI thread. */
final class ImageDiskCache {
    interface Reader<T> { T read(File file) throws Exception; }
    private final File directory;
    private final long limit;

    ImageDiskCache(File directory, long limit) {
        this.directory = directory;
        this.limit = limit;
        File[] interrupted = directory.listFiles((dir, name) -> name.startsWith("partial-") && name.endsWith(".tmp"));
        if (interrupted != null) for (File file : interrupted) file.delete();
    }

    synchronized File temporaryFile() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create image cache");
        return File.createTempFile("partial-", ".tmp", directory);
    }

    static String key(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte b : digest) key.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    synchronized <T> T read(String url, Reader<T> reader) throws Exception {
        File file = new File(directory, key(url));
        if (!file.isFile()) return null;
        file.setLastModified(System.currentTimeMillis());
        return reader.read(file);
    }

    synchronized void put(String url, InputStream bytes) throws IOException {
        File temp = temporaryFile();
        try {
            copy(bytes, temp);
            File target = new File(directory, key(url));
            if (!temp.renameTo(target)) throw new IOException("Cannot publish image cache");
            File[] files = directory.listFiles((dir, name) -> name.matches("[0-9a-f]{64}"));
            if (files == null) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            long total = 0;
            for (File file : files) total += file.length();
            for (File file : files) {
                if (total <= limit) break;
                long size = file.length();
                if (file.delete()) total -= size;
            }
        } finally { temp.delete(); }
    }

    static void copy(InputStream input, File file) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[16384];
            long size = 0;
            int n;
            while ((n = input.read(buffer)) != -1) {
                size += n;
                if (size > ImageAttachment.MAX_BYTES) throw new IOException("Image exceeds 10 MiB");
                out.write(buffer, 0, n);
            }
            if (size == 0) throw new IOException("Empty image");
        }
    }
}
