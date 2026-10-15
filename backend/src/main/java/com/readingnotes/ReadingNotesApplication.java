package com.readingnotes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 知识库驱动的个人阅读读书笔记智能分析系统
 * 百景大赛 · 开放创新赛道参赛项目
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class ReadingNotesApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReadingNotesApplication.class, args);
    }
}
