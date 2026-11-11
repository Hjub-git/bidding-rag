package com.bidding.service;

import com.bidding.common.BusinessException;
import com.bidding.common.ResultCode;
import com.bidding.entity.TenderDocument;
import com.bidding.mapper.TenderDocumentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 上传招标文件并触发 Python 入库。
 *
 * <p>流程：校验格式/大小 → 幂等查 {@code (tender_id, file_name)} → 落盘到
 * {@code DATA_ROOT/{tender_id}/{doc_id}_{文件名}} → 调 Python {@code /api/ingest}
 * → 回写 {@code tender_documents}。
 *
 * <p>落盘位置必须与 Python 的 DATA_ROOT 一致：{@code /api/ingest} 有路径防穿越，
 * 文件在 DATA_ROOT 之外会直接 400。这是生产语义——Java 上传落盘，Python 只读这个前缀。
 *
 * <p>幂等键：同一 {@code (tender_id, file_name)} 重复上传复用原 {@code doc_id}，
 * Python 侧 {@code /api/ingest} 会先删旧 chunk 再灌，所以库内 chunk 数不会翻倍。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagUploadService {

    private static final Set<String> ALLOWED_EXT = Set.of("pdf", "docx", "pptx", "xlsx", "txt");
    private static final long MAX_BYTES = 50L * 1024 * 1024;

    private final TenderDocumentMapper tenderDocumentMapper;
    private final ObjectMapper objectMapper;

    @Value("${rag.python.base-url:http://127.0.0.1:8000}")
    private String pythonBaseUrl;

    @Value("${rag.upload.data-root:../rag-service/data}")   // 默认相对 backend/ 工作目录，详见 application.yml
    private String dataRoot;

    /**
     * Python 内部服务令牌：{@code /api/ingest} 同样要求 {@code X-Internal-Token}。
     * 必须与 {@link RagProxyService} 和 Python 的 {@code Config.INTERNAL_TOKEN} 同值
     * （三处都取自 {@code rag.internal-token} / 环境变量 {@code RAG_INTERNAL_TOKEN}）。
     */
    @Value("${rag.internal-token:rag-internal-dev-token-2026}")
    private String internalToken;

    private final HttpClient httpClient = HttpClient.newBuilder()
            // 同 RagProxyService：uvicorn 只支持 HTTP/1.1，默认的 h2c upgrade 会被 400
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public TenderDocument upload(Long tenderId, MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件名不能为空");
        }
        if (file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件为空");
        }
        String ext = extensionOf(original);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持格式：" + ext);
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件过大，最大 50MB");
        }

        // 幂等：同一 (tender_id, file_name) 复用原 doc_id
        TenderDocument doc = tenderDocumentMapper.selectOne(
                new LambdaQueryWrapper<TenderDocument>()
                        .eq(TenderDocument::getTenderId, tenderId)
                        .eq(TenderDocument::getFileName, original));
        if (doc == null) {
            doc = new TenderDocument();
            doc.setTenderId(tenderId);
            // doc_id 用雪花 id，一次 insert 就有值。
            // 不能用自增 id 回填：表里 doc_id 是 NOT NULL，
            // 先 insert 会因 docId=null 直接失败（实测踩到过 500）。
            doc.setDocId(IdWorker.getId());
            doc.setFileName(original);
            // stored_path 是 NOT NULL 且此刻还没落盘，先占位空串，落盘成功后 update 成真实路径。
            // 不占位会直接 SQLException: Field 'stored_path' doesn't have a default value（实测踩到过）。
            doc.setStoredPath("");
            doc.setStatus("pending");
            tenderDocumentMapper.insert(doc);
        }
        Long docId = doc.getDocId();

        // 落盘：DATA_ROOT/{tender_id}/{doc_id}_{安全文件名}
        Path target;
        try {
            Path dir = Paths.get(dataRoot, String.valueOf(tenderId));
            Files.createDirectories(dir);
            target = dir.resolve(docId + "_" + safeFileName(original)).toAbsolutePath().normalize();
            if (!target.startsWith(Paths.get(dataRoot).toAbsolutePath().normalize())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "非法文件路径");
            }
            file.transferTo(target.toFile());
        } catch (IOException e) {
            markFailed(doc, "落盘失败：" + e.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "文件保存失败");
        }

        // 调 Python /api/ingest
        int chunks;
        try {
            chunks = callIngest(docId, tenderId, target.toString());
        } catch (BusinessException e) {
            markFailed(doc, e.getMessage());
            throw e;
        }

        doc.setDocId(docId);
        doc.setStoredPath(target.toString());
        doc.setChunkCount(chunks);
        doc.setStatus("ready");
        tenderDocumentMapper.updateById(doc);

        log.info("上传入库完成 tender_id={} doc_id={} file={} chunks={}",
                tenderId, docId, original, chunks);
        return doc;
    }

    private int callIngest(Long docId, Long tenderId, String filePath) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("doc_id", docId);
            payload.put("tender_id", tenderId);
            payload.put("file_path", filePath);

            HttpRequest request = HttpRequest.newBuilder(URI.create(pythonBaseUrl + "/api/ingest"))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    // 内部服务令牌：没有它 Python 直接 401
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("ingest 失败 doc_id={} status={} body={}", docId,
                        response.statusCode(), response.body());
                throw new BusinessException(ResultCode.INTERNAL_ERROR,
                        "入库服务返回 " + response.statusCode());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
            Object chunks = body.get("chunks");
            return chunks instanceof Number ? ((Number) chunks).intValue() : 0;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("ingest 调用异常 doc_id={}: {}", docId, e.toString());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "调用入库服务失败");
        }
    }

    private void markFailed(TenderDocument doc, String reason) {
        try {
            doc.setStatus("failed");
            tenderDocumentMapper.updateById(doc);
        } catch (Exception e) {
            log.warn("标记 failed 失败: {}", e.toString());
        }
        log.warn("上传失败 tender_id={} file={} reason={}", doc.getTenderId(), doc.getFileName(), reason);
    }

    private static String extensionOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    /** 只保留安全字符，挡掉 ../ 与路径分隔符。 */
    private static String safeFileName(String name) {
        String base = name.replaceAll("[/\\\\]", "_");
        base = base.replace("..", "_");
        if (base.length() > 120) {
            String ext = base.substring(base.lastIndexOf('.'));
            base = base.substring(0, 100) + ext;
        }
        return base;
    }
}
