package com.kw.knowone.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;

public interface StoragePort {
    StagedObject stage(InputStream input) throws IOException;
    String promote(StagedObject staged) throws IOException;
    StoredObject read(String objectKey) throws IOException;
    boolean delete(String objectKey) throws IOException;
    void discard(StagedObject staged);
    int cleanupStagedBefore(Instant cutoff) throws IOException;

    record StagedObject(Path path, long byteSize) { }
    record StoredObject(InputStream input, long byteSize) implements AutoCloseable {
        @Override public void close() throws IOException { input.close(); }
    }
}
