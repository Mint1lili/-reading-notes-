package com.readingnotes.service;

import java.util.Map;

/**
 * 健康检查与运维服务（接口 12）
 */
public interface HealthService {

    /** 12.1 整体健康：SQLite / Chroma / 豆包 API / 磁盘 + 活跃任务 */
    Map<String, Object> overallHealth();

    /** 12.2 Chroma 状态：集合列表 + 维度匹配校验 */
    Map<String, Object> chromaHealth();
}
