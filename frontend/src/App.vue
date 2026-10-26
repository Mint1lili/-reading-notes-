<!--
  应用外壳：左侧固定导航 + 右侧路由视图。
  导航顺序按功能模块排列（F1 书库 → F3 读书 → F4 笔记 → 评估 → F5 设置），
  与需求文档的模块编号一致，方便对照验收。
-->
<template>
  <el-container class="layout">
    <el-aside v-if="!isLogin" width="216px" class="aside">
      <div class="brand">
        <div class="brand-mark">智</div>
        <div class="brand-text">
          <div class="brand-name">智读</div>
          <div class="brand-sub">阅读笔记</div>
        </div>
      </div>

      <el-menu router :default-active="$route.path" class="menu">
        <el-menu-item index="/library">
          <el-icon><Collection /></el-icon><span>书库管理</span>
        </el-menu-item>
        <el-menu-item index="/reader">
          <el-icon><Reading /></el-icon><span>AI 读书</span>
        </el-menu-item>
        <el-menu-item index="/notes">
          <el-icon><Notebook /></el-icon><span>我的笔记</span>
        </el-menu-item>
        <el-menu-item index="/eval">
          <el-icon><TrendCharts /></el-icon><span>RAG 评估</span>
        </el-menu-item>
        <el-menu-item index="/settings">
          <el-icon><Setting /></el-icon><span>系统设置</span>
        </el-menu-item>
      </el-menu>

      <div class="user-area">
        <div class="avatar">{{ avatarChar }}</div>
        <div class="user-meta">
          <div class="user-email" :title="user?.email">{{ user?.email || '已登录' }}</div>
          <el-button link type="danger" size="small" class="logout" @click="logout">退出登录</el-button>
        </div>
      </div>
    </el-aside>

    <el-main :class="{ 'main--bare': isLogin }" class="main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Collection, Reading, Notebook, TrendCharts, Setting } from '@element-plus/icons-vue'
import { getStoredUser, clearAuth } from './utils/token'

const router = useRouter()
const route = useRoute()
const isLogin = computed(() => route.path === '/login')
const user = computed(() => getStoredUser())
const avatarChar = computed(() => {
  const email = user.value?.email || ''
  return email ? email[0].toUpperCase() : '智'
})

function logout() {
  clearAuth()
  ElMessage.success('已退出登录')
  router.replace('/login')
}
</script>

<style>
* { box-sizing: border-box; }
html, body, #app { height: 100%; margin: 0; }
body { font-family: -apple-system, 'Microsoft YaHei', 'PingFang SC', sans-serif; background: #f6f8f7; }

.layout { height: 100vh; }

/* ===== 侧边栏 ===== */
.aside {
  background: #fbfcfb;
  border-right: 1px solid #e4ece7;
  display: flex;
  flex-direction: column;
  padding: 16px 12px 12px;
}
.brand { display: flex; align-items: center; gap: 10px; padding: 4px 8px 18px; }
.brand-mark {
  width: 36px; height: 36px; border-radius: 10px;
  background: linear-gradient(150deg, #14532d, #2f855a);
  color: #fff; font-weight: 700; font-size: 18px;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 4px 10px rgba(20, 83, 45, 0.25);
}
.brand-name { font-weight: 700; font-size: 16px; color: #14532d; line-height: 1.2; }
.brand-sub { font-size: 11px; color: #94a3b8; }

.menu { border-right: none; background: transparent; --el-menu-item-height: 42px; }
.menu .el-menu-item {
  border-radius: 8px; margin: 2px 0; color: #475569;
}
.menu .el-menu-item:hover { background: #eef5f1; }
.menu .el-menu-item.is-active {
  background: #14532d; color: #fff;
}
.menu .el-menu-item.is-active:hover { background: #14532d; }
.menu .el-menu-item .el-icon { font-size: 17px; }

.user-area {
  margin-top: auto;
  padding: 12px 6px 0;
  border-top: 1px solid #e8eee9;
  display: flex; align-items: center; gap: 10px;
}
.avatar {
  width: 34px; height: 34px; border-radius: 50%;
  background: #e8f5ec; color: #14532d; font-weight: 700;
  display: flex; align-items: center; justify-content: center; font-size: 15px;
  flex-shrink: 0;
}
.user-meta { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; }
.user-email {
  font-size: 12px; color: #64748b;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 130px;
}
.logout { padding: 0; }

/* ===== 主区 ===== */
.main {
  background: #f6f8f7;
  padding: 20px 24px;
  overflow-y: auto;
}

/* 登录页全屏（不套侧边栏布局） */
.main--bare {
  padding: 0;
}
</style>
