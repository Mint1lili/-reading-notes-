<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">智读 · 阅读笔记</div>
      <el-menu router :default-active="$route.path" class="menu">
        <el-menu-item index="/library">书库管理</el-menu-item>
        <el-menu-item index="/reader">AI 读书</el-menu-item>
        <el-menu-item index="/notes">我的笔记</el-menu-item>
        <el-menu-item index="/eval">RAG 评估</el-menu-item>
        <el-menu-item index="/settings">系统设置</el-menu-item>
      </el-menu>
    </el-aside>
    <el-main class="main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style>
.layout { height: 100vh; }
.aside { background: #f5f7fa; border-right: 1px solid #e4e7ed; }
.logo { font-weight: 700; font-size: 16px; padding: 18px 16px; color: #14532d; }
.menu { border-right: none; }
.main { background: #fff; overflow-y: auto; }
</style>
