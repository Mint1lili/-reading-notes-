package com.readingnotes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 知识库驱动的个人阅读读书笔记智能分析系统 —— 应用入口。
 * <p>
 * 模块划分：F1 书籍管理 / F2 知识库构建 / F3 RAG 问答 / F4 笔记回流 / F5 设置；
 * 启动依赖 MySQL 与 Chroma（Chroma 未就绪不阻塞启动，构建时会以 70001 快速失败）。
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class ReadingNotesApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReadingNotesApplication.class, args);
    }
}
