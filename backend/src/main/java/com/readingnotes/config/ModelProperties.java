package com.readingnotes.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 大模型调用参数（application.yml 的 model.*，对应需求 F5.1）
 * 运行时可通过 PUT /api/v1/settings/model 热更新
 */
@Data
@Component
@ConfigurationProperties(prefix = "model")
public class ModelProperties {

    /** 温度 [0, 1] */
    private double temperature = 0.3;

    /** 最大输出 token [256, 4096] */
    private int maxTokens = 2048;

    /** nucleus 采样 [0.7, 1] */
    private double topP = 0.9;

    /** 是否开启流式输出 */
    private boolean stream = true;

    /** 每日输入 token 预算上限（默认 80000） */
    private long dailyInputLimit = 80000;

    /** 每日输出 token 预算上限（默认 20000） */
    private long dailyOutputLimit = 20000;
}
