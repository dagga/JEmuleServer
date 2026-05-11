package org.jemule.core.event;

public class FileEvent extends BaseEvent {
    public static final String PUBLISHED = "FILE_PUBLISHED";
    public static final String SEARCHED = "FILE_SEARCHED";

    private final String fileName;
    private final String fileHash;

    public FileEvent(String type, String fileName, String fileHash, String message) {
        super(type, message);
        this.fileName = fileName;
        this.fileHash = fileHash;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFileHash() {
        return fileHash;
    }
}
