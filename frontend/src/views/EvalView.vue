<template>
  <div>
    <h2>RAG 评估（§4.7 比赛亮点）</h2>
    <el-empty description="脚手架占位：内置 50 题数据集一键回归，展示 Recall@5 / Answer-Acc / Refusal-Acc 等指标" />
    <el-card style="margin-top:12px">
      <template #header>目标指标</template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="Recall@5">≥ 0.80</el-descriptions-item>
        <el-descriptions-item label="Recall@10">≥ 0.90</el-descriptions-item>
        <el-descriptions-item label="Answer-Accuracy">≥ 0.75</el-descriptions-item>
        <el-descriptions-item label="Refusal-Accuracy">≥ 0.95</el-descriptions-item>
        <el-descriptions-item label="Citation-Accuracy">≥ 0.85</el-descriptions-item>
        <el-descriptions-item label="平均首字延迟">≤ 1.5s</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup>
// 待后端实现后接入：POST /eval/run → 进度 → 报告导出
</script>
