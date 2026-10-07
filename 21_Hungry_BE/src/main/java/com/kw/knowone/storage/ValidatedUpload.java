package com.kw.knowone.storage;

public record ValidatedUpload(StoragePort.StagedObject staged,String originalName,String mediaType,long byteSize,
        byte[] sha256,Integer pageCount,Integer durationSeconds,boolean image) { }
