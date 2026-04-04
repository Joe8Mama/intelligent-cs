package com.ics.llm.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

/**
 * ============================================================================
 * 阿里云 OSS 存储服务
 * ============================================================================
 * 封装 OSS 文件上传逻辑，将语料 JSONL 文件上传到阿里云 OSS
 */
@Slf4j
@Service
public class OssService {

    @Value("${aliyun.oss.endpoint:}")
    private String endpoint;

    @Value("${aliyun.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret:}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucket-name:}")
    private String bucketName;

    @Value("${aliyun.oss.dir-prefix:corpus/raw/}")
    private String dirPrefix;

    private OSS createClient() {
        return new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
    }

    /**
     * 上传语料 JSONL 文件到 OSS
     *
     * @param fileName   文件名 (如 batch_xxx.jsonl)
     * @param jsonlBytes JSONL 内容字节
     * @return OSS Object Key (完整路径)
     */
    public String uploadCorpusJsonl(String fileName, byte[] jsonlBytes) {
        OSS ossClient = null;
        String key = dirPrefix + fileName;

        try {
            ossClient = createClient();
            ByteArrayInputStream inputStream = new ByteArrayInputStream(jsonlBytes);

            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentType("application/x-jsonlines; charset=utf-8");
            meta.setContentLength(jsonlBytes.length);

            PutObjectResult result = ossClient.putObject(bucketName, key, inputStream, meta);
            log.info("[OSS] 语料上传成功: {}, ETag: {}", key, result.getETag());
            return key;

        } catch (Exception e) {
            log.error("[OSS] 语料上传失败: {}", key, e);
            throw new RuntimeException("语料文件上传 OSS 失败: " + key, e);
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }
}
