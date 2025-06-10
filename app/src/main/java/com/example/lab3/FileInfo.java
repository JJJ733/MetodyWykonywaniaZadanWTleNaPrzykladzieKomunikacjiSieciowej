package com.example.lab3;

public class FileInfo {
    private final int size;
    private final String type;

    public FileInfo(int size, String type) {
        this.size = size;
        this.type = type;
    }

    public int getSize() {
        return size;
    }

    public String getType() {
        return type;
    }
}
