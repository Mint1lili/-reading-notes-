<template>
  <div>
    <h2>我的笔记（F4）</h2>
    <el-empty description="脚手架占位：笔记 CRUD / 标签 / 双向链接 / 知识回流 / 导出 在此实现" />
    <el-card style="margin-top:12px">
      <template #header>功能规划</template>
      <ul>
        <li>AI 产物一键存为笔记（摘要 / 问答 / 导图）</li>
        <li>标签体系 + 按标签筛选</li>
        <li>双向链接：笔记 ↔ 原文页码跳转</li>
        <li>知识回流：笔记加入 notes_all 参与检索（开关）</li>
        <li>导出 Markdown（带 front-matter）/ 整本书 PDF</li>
      </ul>
    </el-card>
  </div>
</template>

<script setup>
// 待后端实现后接入
</script>
