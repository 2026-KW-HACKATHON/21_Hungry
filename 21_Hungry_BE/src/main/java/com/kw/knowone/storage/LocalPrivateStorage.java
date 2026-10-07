package com.kw.knowone.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalPrivateStorage implements StoragePort {
    private final Path root;
    private final Path temporary;

    public LocalPrivateStorage(@Value("${app.storage.local-root}") String configuredRoot) throws IOException {
        root=Path.of(configuredRoot).toAbsolutePath().normalize();
        temporary=root.resolve("tmp");
        Files.createDirectories(temporary);
        Files.createDirectories(root.resolve("objects"));
    }

    @Override public StagedObject stage(InputStream input) throws IOException {
        Path path=Files.createTempFile(temporary,"upload-",".tmp");
        try { return new StagedObject(path,Files.copy(input,path,StandardCopyOption.REPLACE_EXISTING)); }
        catch(IOException failure){ Files.deleteIfExists(path); throw failure; }
    }

    @Override public String promote(StagedObject staged) throws IOException {
        String key=UUID.randomUUID().toString().replace("-","");
        Path target=resolve("objects/"+key.substring(0,2)+"/"+key);
        Files.createDirectories(target.getParent());
        Files.move(staged.path(),target,StandardCopyOption.ATOMIC_MOVE);
        return root.relativize(target).toString().replace('\\','/');
    }

    @Override public StoredObject read(String objectKey) throws IOException {
        Path path=resolve(objectKey);
        return new StoredObject(Files.newInputStream(path),Files.size(path));
    }

    @Override public boolean delete(String objectKey) throws IOException {
        return objectKey==null||Files.deleteIfExists(resolve(objectKey));
    }

    @Override public void discard(StagedObject staged) {
        if(staged==null)return;
        try { Files.deleteIfExists(staged.path()); } catch(IOException ignored) { }
    }

    @Override public int cleanupStagedBefore(Instant cutoff) throws IOException {
        int removed=0;try(var paths=Files.list(temporary)){for(Path path:paths.toList())if(Files.isRegularFile(path)&&Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)&&Files.deleteIfExists(path))removed++;}return removed;
    }

    private Path resolve(String key) {
        Path value=root.resolve(key).normalize();
        if(!value.startsWith(root))throw new IllegalArgumentException("Invalid storage key");
        return value;
    }
}
