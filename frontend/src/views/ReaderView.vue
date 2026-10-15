<template>
  <div>
    <h2>AI 读书（F3）</h2>
    <el-empty description="脚手架占位：全书摘要 / 书本内问答（SSE 流式）/ 跨书问答 / 思维导图 在此实现" />
    <el-card style="margin-top:12px">
      <template #header>功能规划</template>
      <ul>
        <li>全书摘要：map-reduce 分层生成（POST /ai/summary）</li>
        <li>书本内问答：SSE 流式 + 引用页码高亮（POST /ai/qa/stream）</li>
        <li>跨书问答：按书分组对比 + 来源标注（POST /ai/qa/cross/stream）</li>
        <li>思维导图：Markdown 层级导出（POST /ai/mindmap）</li>
        <li>每轮可保存为笔记 / 重新生成 / 点踩反馈</li>
      </ul>
    </el-card>
  </div>
</template>

<script setup>
// 待后端实现后接入：上传书籍 → 选择书 → 问答/摘要/导图
</script>
