package com.readingnotes.ai;

import java.util.List;

/**
 * 文本向量化（Embedding）服务抽象（F2.4 / F3 共用）
 *
 * 设计目的：向量化来源可切换，比赛演示时可在两种模式间一键切换：
 * - api   : 调远端大模型平台（联通云 Qwen-Embedding / 豆包），不占本地内存，需消耗 token 额度
 * - local : 调本地部署的开源向量模型服务（如 bge-small-zh，localhost:8001），免费离线，常驻约 1GB 内存
 *
 * 切换方式：application.yml 中 ai.embedding.mode=api|local，重启生效。
 * 注意：切换后向量维度可能变化，若 Chroma 已有数据需删集合重建（ErrorCode.DIM_MISMATCH 兜底）。
 */
public interface EmbeddingProvider {

    /**
     * 批量向量化
     * @param texts 待向量化文本列表
     * @return 与输入对齐的向量列表，每项维度固定（同一 provider 内恒定）
     */
    List<List<Float>> embed(List<String> texts);

    /** 当前 provider 产出的向量维度（供 Chroma 集合创建与一致性校验） */
    int dimension();
}
