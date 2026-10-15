<template>
  <div>
    <h2>书库管理（F1）</h2>
    <el-alert type="info" :closable="false" title="脚手架已接通 GET /books 与 GET /books/{id} 全链路；上传/删除/回收站待后端实现后启用" style="margin-bottom:12px" />
    <el-table :data="books" v-loading="loading" style="width:100%">
      <el-table-column prop="title" label="书名" />
      <el-table-column prop="author" label="作者" />
      <el-table-column prop="status" label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="build_progress" label="构建进度" width="140">
        <template #default="{ row }">
          <el-progress :percentage="row.build_progress || 0" :stroke-width="10" />
        </template>
      </el-table-column>
      <el-table-column prop="chunk_count" label="切片数" width="90" />
      <el-table-column prop="created_at" label="上传时间" width="180" />
    </el-table>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { listBooks } from '../api'

const books = ref([])
const loading = ref(false)

const statusType = (s) => {
  if (s === 'READY') return 'success'
  if (s === 'BUILDING' || s === 'UPLOADING') return 'warning'
  if (s === 'FAILED') return 'danger'
  return 'info'
}

onMounted(async () => {
  loading.value = true
  try {
    const data = await listBooks({ page: 1, page_size: 20 })
    books.value = data.items || []
  } finally {
    loading.value = false
  }
})
</script>
