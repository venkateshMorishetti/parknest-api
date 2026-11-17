package com.parknest.api.files;

/** Public address of an uploaded file (served by FileController). */
public final class FileUrls {

    private FileUrls() {}

    public static String of(String fileId) {
        return fileId == null ? null : "/api/v1/files/" + fileId;
    }
}
