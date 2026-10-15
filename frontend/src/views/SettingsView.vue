<template>
  <div>
    <h2>系统设置（F5）</h2>
    <el-empty description="脚手架占位：模型参数 / RAG 参数滑块 / Prompt 模板版本管理 / 用量统计 / 备份恢复 / 日志查询 在此实现" />
    <el-card style="margin-top:12px">
      <template #header>规划</template>
      <ul>
        <li>RAG 参数：阈值滑块现场演示拒答效果（比赛亮点）</li>
        <li>Prompt 模板：YAML 在线编辑 + 10 版本 diff / 回滚</li>
        <li>用量统计：日/周/月 Token 折线图 + 预算告警</li>
        <li>备份：一键 zip + SHA-256 校验 + 自动周备</li>
        <li>日志：按 trace_id 查询全链路</li>
      </ul>
    </el-card>
  </div>
</template>

<script setup>
// 待后端实现后接入
</script>
