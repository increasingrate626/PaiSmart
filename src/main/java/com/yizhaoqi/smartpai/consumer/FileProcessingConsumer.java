package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import com.yizhaoqi.smartpai.utils.TraceContext;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@Service
@Slf4j
public class FileProcessingConsumer {

    private final ParseService parseService;
    private final VectorizationService vectorizationService;

    @Autowired
    private KafkaConfig kafkaConfig;

    public FileProcessingConsumer(ParseService parseService, VectorizationService vectorizationService) {
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
    }

    @KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        String ingestionTraceId = task.getIngestionTraceId();
        TraceContext.setTraceId(ingestionTraceId);
        TraceContext.setIngestionTraceId(ingestionTraceId);
        TraceContext.setUserId(task.getUserId());

        log.info("Received file processing task: fileMd5={}, fileName={}, ingestionTraceId={}",
                task.getFileMd5(), task.getFileName(), ingestionTraceId);
        log.info("File permission info: userId={}, orgTag={}, isPublic={}",
                task.getUserId(), task.getOrgTag(), task.isPublic());

        InputStream fileStream = null;
        try {
            fileStream = downloadFileFromStorage(task.getFilePath());
            if (fileStream == null) {
                throw new IOException("Downloaded file stream is null");
            }

            if (!fileStream.markSupported()) {
                fileStream = new BufferedInputStream(fileStream);
            }

            parseService.parseAndSave(
                    task.getFileMd5(),
                    fileStream,
                    task.getUserId(),
                    task.getOrgTag(),
                    task.isPublic(),
                    ingestionTraceId
            );
            log.info("File parse completed: fileMd5={}, ingestionTraceId={}", task.getFileMd5(), ingestionTraceId);

            vectorizationService.vectorize(
                    task.getFileMd5(),
                    task.getUserId(),
                    task.getOrgTag(),
                    task.isPublic(),
                    ingestionTraceId
            );
            log.info("Vectorization completed: fileMd5={}, ingestionTraceId={}", task.getFileMd5(), ingestionTraceId);
        } catch (Exception e) {
            log.error("Error processing file task: fileMd5={}, ingestionTraceId={}",
                    task.getFileMd5(), ingestionTraceId, e);
            throw new RuntimeException("Error processing task", e);
        } finally {
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    log.error("Error closing file stream", e);
                }
            }
            TraceContext.clearTraceContext();
        }
    }

    private InputStream downloadFileFromStorage(String filePath) throws ServerException, InsufficientDataException,
            ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException,
            InvalidResponseException, XmlParserException, InternalException {
        log.info("Downloading file from storage: {}", filePath);

        try {
            File file = new File(filePath);
            if (file.exists()) {
                log.info("Detected file system path: {}", filePath);
                return new FileInputStream(file);
            }

            if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
                log.info("Detected remote URL: {}", filePath);
                URL url = new URL(filePath);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("User-Agent", "SmartPAI-FileProcessor/1.0");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    log.info("Successfully connected to URL, starting download");
                    return connection.getInputStream();
                } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    log.error("Access forbidden - possible expired presigned URL");
                    throw new IOException("Access forbidden - the presigned URL may have expired");
                } else {
                    log.error("Failed to download file, HTTP response code: {} for URL: {}", responseCode, filePath);
                    throw new IOException(String.format("Failed to download file, HTTP response code: %d", responseCode));
                }
            }

            throw new IllegalArgumentException("Unsupported file path format: " + filePath);
        } catch (Exception e) {
            log.error("Error downloading file from storage: {}", filePath, e);
            return null;
        }
    }
}
