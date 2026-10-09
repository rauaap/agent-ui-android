package com.agentui.app;

import java.io.File;
import java.io.IOException;

/** Composer-owned unchanged selection bytes, retained for viewing while upload is pending/failed. */
final class SelectedImageFile implements AutoCloseable {
    private final File file;
    private boolean closed;

    SelectedImageFile(File file) { this.file = file; }

    /** Call off UI thread. A concurrent release cannot delete a file mid-decode. */
    synchronized <T> T read(ImageDiskCache.Reader<T> reader) throws Exception {
        if (closed) throw new IOException("Selected image is no longer available");
        return reader.read(file);
    }

    /** Called on the upload worker, after any upload using these bytes has finished. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        file.delete();
    }
}
