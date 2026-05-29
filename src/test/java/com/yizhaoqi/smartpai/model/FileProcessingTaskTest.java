package com.yizhaoqi.smartpai.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileProcessingTaskTest {

    @Test
    void keepsBackwardCompatibleConstructorWithoutTraceId() {
        FileProcessingTask task = new FileProcessingTask("md5", "path", "name.txt");

        assertEquals("md5", task.getFileMd5());
        assertEquals("path", task.getFilePath());
        assertEquals("name.txt", task.getFileName());
        assertEquals("DEFAULT", task.getOrgTag());
        assertNull(task.getIngestionTraceId());
    }

    @Test
    void carriesIngestionTraceIdForKafkaProcessing() {
        FileProcessingTask task = new FileProcessingTask(
                "md5",
                "path",
                "name.txt",
                "user-1",
                "org-1",
                true,
                "trc-ingest"
        );

        assertEquals("trc-ingest", task.getIngestionTraceId());
        assertTrue(task.isPublic());
    }
}
