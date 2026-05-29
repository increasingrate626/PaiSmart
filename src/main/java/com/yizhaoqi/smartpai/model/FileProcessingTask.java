package com.yizhaoqi.smartpai.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileProcessingTask {
    private String fileMd5;
    private String filePath;
    private String fileName;
    private String userId;
    private String orgTag;
    private boolean isPublic;
    private String ingestionTraceId;

    public FileProcessingTask(String fileMd5, String filePath, String fileName) {
        this.fileMd5 = fileMd5;
        this.filePath = filePath;
        this.fileName = fileName;
        this.userId = null;
        this.orgTag = "DEFAULT";
        this.isPublic = false;
        this.ingestionTraceId = null;
    }
}
