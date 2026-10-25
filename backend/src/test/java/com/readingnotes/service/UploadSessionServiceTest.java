package com.readingnotes.service;

import com.readingnotes.common.BusinessException;
import com.readingnotes.config.AppProperties;
import com.readingnotes.config.BuildProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分片断点续传（F1.1）的离线测试：只依赖文件系统，不连数据库。
 */
class UploadSessionServiceTest {

    @TempDir
    Path tempDir;

    private UploadSessionService service;

    @BeforeEach
    void setUp() {
        AppProperties app = new AppProperties();
        app.setUploadsDir(tempDir.toString());
        BuildProperties build = new BuildProperties();
        build.setUploadChunkSize(4);          // 便于用 10 字节内容切出 3 片
        build.setUploadSessionTtlHours(24);
        service = new UploadSessionService(app, build);
    }

    @Test
    void assembleAfterAllChunksUploaded() throws Exception {
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> init = service.init("认知觉醒.pdf", content.length);
        assertEquals(3, init.get("total_chunks"));

        String uploadId = (String) init.get("upload_id");
        service.saveChunk(uploadId, 0, part(content, 0, 4));
        service.saveChunk(uploadId, 1, part(content, 4, 4));
        // 断点续传：再查一次，应只剩最后一片
        Map<String, Object> status = service.status(uploadId);
        assertEquals(1, ((java.util.List<?>) status.get("missing")).size());
        assertEquals(67, status.get("progress"), "3 片传了 2 片应为 67%");

        service.saveChunk(uploadId, 2, part(content, 8, 2));
        Path merged = service.assemble(uploadId);
        assertTrue(Files.isRegularFile(merged));
        assertArrayEquals(content, Files.readAllBytes(merged));
    }

    @Test
    void assembleRejectsMissingChunk() {
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        String uploadId = (String) service.init("缺片.pdf", content.length).get("upload_id");
        service.saveChunk(uploadId, 0, part(content, 0, 4));

        BusinessException e = assertThrows(BusinessException.class, () -> service.assemble(uploadId));
        assertTrue(e.getMessage().contains("还缺"), "应提示缺片数量：" + e.getMessage());
    }

    @Test
    void assembleRejectsSizeMismatch() throws Exception {
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        String uploadId = (String) service.init("残缺.pdf", content.length).get("upload_id");
        service.saveChunk(uploadId, 0, part(content, 0, 4));
        service.saveChunk(uploadId, 1, part(content, 4, 4));
        // 最后一片少传 1 字节：合并后大小对不上，必须拒绝，不能拼出坏 PDF
        service.saveChunk(uploadId, 2, part(content, 8, 1));

        BusinessException e = assertThrows(BusinessException.class, () -> service.assemble(uploadId));
        assertTrue(e.getMessage().contains("大小不一致"), "应提示大小不一致：" + e.getMessage());
    }

    @Test
    void reinitReusesSessionForSameFile() {
        Map<String, Object> first = service.init("重复.pdf", 10);
        Map<String, Object> second = service.init("重复.pdf", 10);
        assertEquals(first.get("upload_id"), second.get("upload_id"), "同文件重复 init 应复用会话，避免丢进度");
    }

    @Test
    void rejectNonPdf() {
        assertThrows(BusinessException.class, () -> service.init("book.epub", 10));
    }

    private static MockMultipartFile part(byte[] src, int offset, int len) {
        byte[] slice = new byte[len];
        System.arraycopy(src, offset, slice, 0, len);
        return new MockMultipartFile("file", "part", "application/octet-stream", slice);
    }
}
