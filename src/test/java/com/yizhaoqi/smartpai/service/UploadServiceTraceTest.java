package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadServiceTraceTest {

    private UploadService uploadService;
    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOperations;
    private FileUploadRepository fileUploadRepository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        uploadService = new UploadService();
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        fileUploadRepository = mock(FileUploadRepository.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ReflectionTestUtils.setField(uploadService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(uploadService, "fileUploadRepository", fileUploadRepository);
    }

    @Test
    void reusesCachedIngestionTraceIdForSameUserAndFile() {
        when(valueOperations.get("trace:ingestion:alice:md5-a")).thenReturn("trc-cached");

        String traceId = uploadService.getOrCreateIngestionTraceId("md5-a", "alice");

        assertEquals("trc-cached", traceId);
    }

    @Test
    void reusesPersistedIngestionTraceIdAndCachesIt() {
        FileUpload upload = new FileUpload();
        upload.setIngestionTraceId("trc-existing");
        when(valueOperations.get("trace:ingestion:alice:md5-a")).thenReturn(null);
        when(fileUploadRepository.findByFileMd5AndUserId("md5-a", "alice")).thenReturn(Optional.of(upload));

        String traceId = uploadService.getOrCreateIngestionTraceId("md5-a", "alice");

        assertEquals("trc-existing", traceId);
        verify(valueOperations).set("trace:ingestion:alice:md5-a", "trc-existing", 7, TimeUnit.DAYS);
    }

    @Test
    void createsTraceIdWhenNoCachedOrPersistedTraceExists() {
        when(valueOperations.get("trace:ingestion:alice:md5-a")).thenReturn(null);
        when(fileUploadRepository.findByFileMd5AndUserId("md5-a", "alice")).thenReturn(Optional.empty());

        String traceId = uploadService.getOrCreateIngestionTraceId("md5-a", "alice");

        assertTrue(traceId.startsWith("trc-"));
        verify(valueOperations).set(eq("trace:ingestion:alice:md5-a"), eq(traceId), eq(7L), eq(TimeUnit.DAYS));
    }
}
