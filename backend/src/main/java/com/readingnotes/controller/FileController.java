package com.readingnotes.controller;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 导出文件下载（API 文档 §9.11 的 download_url）
 *
 * <p>GET /api/v1/files/exports/{fileName} —— 从 app.exports-dir 读文件返回。
 * 文件名只允许字母数字与 ._- ，杜绝路径穿越（../）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final AppProperties props;

    public FileController(AppProperties props) {
        this.props = props;
    }

    @GetMapping("/exports/{fileName}")
    public ResponseEntity<Resource> downloadExport(@PathVariable String fileName) {
        // 安全：禁止路径穿越与非法字符
        if (fileName == null || !fileName.matches("[A-Za-z0-9._-]+")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件名不合法");
        }
        Path file = Paths.get(props.getExportsDir()).resolve(fileName).normalize();
        if (!Files.isRegularFile(file) || !file.startsWith(Paths.get(props.getExportsDir()).normalize())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件不存在或已过期");
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
