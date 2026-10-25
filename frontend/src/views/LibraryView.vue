<template>
  <div>
    <!-- ===== 顶栏 ===== -->
    <div class="lib-head">
      <div class="lib-title">书库</div>
      <div class="lib-tools">
        <el-input
          v-model="keyword" placeholder="搜索书名 / 作者…" clearable style="width: 220px"
          @input="onKeyword" @clear="load"
        />
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="load">
          <el-option label="可阅读" value="READY" />
          <el-option label="构建中" value="BUILDING" />
          <el-option label="构建失败" value="FAILED" />
        </el-select>
        <div class="view-switch">
          <el-button-group>
            <el-button :type="view === 'card' ? 'primary' : 'default'" size="default" @click="view = 'card'">
              <el-icon><Grid /></el-icon>
            </el-button>
            <el-button :type="view === 'list' ? 'primary' : 'default'" size="default" @click="view = 'list'">
              <el-icon><List /></el-icon>
            </el-button>
          </el-button-group>
        </div>
        <el-tooltip content="后端上传接口待实现，演示用预置数据" placement="top">
          <el-button type="primary" disabled>
            <el-icon style="margin-right:4px"><UploadFilled /></el-icon>上传 PDF
          </el-button>
        </el-tooltip>
      </div>
    </div>

    <!-- ===== 卡片视图 ===== -->
    <div v-if="view === 'card'" v-loading="loading" class="shelf">
      <div
        v-for="b in books" :key="b.book_id"
        class="shelf-card"
        :class="'st-' + (b.status || 'UNKNOWN')"
        @click="openDetail(b)"
      >
        <div class="cover" :style="{ background: coverGradient(b) }">
          <span class="cover-letter">{{ (b.title || '书')[0] }}</span>
          <span class="cover-title">{{ b.title }}</span>
          <div class="cover-progress" v-if="b.status === 'BUILDING'">
            <el-progress :percentage="b.build_progress || 0" :stroke-width="4" :show-text="false" color="#86efac" />
          </div>
        </div>
        <div class="card-body">
          <div class="card-title">{{ b.title }}</div>
          <div class="card-author">{{ b.author || '佚名' }}</div>
          <div class="card-foot">
            <el-tag size="small" :type="statusTag(b.status)" effect="light" round>{{ statusText(b.status) }}</el-tag>
            <span class="chunks">{{ b.chunk_count || 0 }} 切片</span>
          </div>
        </div>
        <div class="hover-actions">
          <el-button size="small" type="primary" plain @click.stop="goRead(b)">去读书</el-button>
          <el-button size="small" plain @click.stop="goNotes(b)">看笔记</el-button>
        </div>
      </div>

      <el-empty v-if="!loading && !books.length" description="书库空空如也，上传第一本书开始阅读" style="grid-column: 1 / -1" />
    </div>

    <!-- ===== 列表视图 ===== -->
    <el-table v-else :data="books" v-loading="loading" style="width:100%" class="lib-table" @row-click="openDetail">
      <el-table-column prop="title" label="书名" min-width="200">
        <template #default="{ row }">
          <div class="list-title">
            <span class="list-cover" :style="{ background: coverGradient(row) }">{{ (row.title || '书')[0] }}</span>
            {{ row.title }}
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="author" label="作者" width="140" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)" effect="light" round>{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="构建进度" width="150">
        <template #default="{ row }">
          <el-progress :percentage="row.build_progress || 0" :stroke-width="8" />
        </template>
      </el-table-column>
      <el-table-column prop="chunk_count" label="切片数" width="90" />
      <el-table-column label="上传时间" width="160">
        <template #default="{ row }">{{ fmtDate(row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" plain @click.stop="goRead(row)">去读书</el-button>
          <el-button size="small" plain @click.stop="goNotes(row)">看笔记</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- ===== 详情抽屉 ===== -->
    <el-drawer v-model="drawer" title="书籍详情" size="380px" :close-on-click-modal="false">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-cover" :style="{ background: coverGradient(detail) }">{{ (detail.title || '书')[0] }}</div>
          <div class="detail-title">{{ detail.title }}</div>
          <div class="detail-author">{{ detail.author || '佚名' }}</div>
          <el-descriptions :column="1" border style="margin-top: 16px">
            <el-descriptions-item label="状态">
              <el-tag size="small" :type="statusTag(detail.status)" effect="light" round>{{ statusText(detail.status) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="构建进度">{{ detail.build_progress || 0 }}%</el-descriptions-item>
            <el-descriptions-item label="切片数">{{ detail.chunk_count || 0 }}</el-descriptions-item>
            <el-descriptions-item label="上传时间">{{ fmtDate(detail.created_at) }}</el-descriptions-item>
          </el-descriptions>
          <div class="detail-actions">
            <el-button type="primary" size="large" style="width: 100%" :disabled="detail.status !== 'READY'" @click="goRead(detail)">
              <el-icon style="margin-right:4px"><Reading /></el-icon>去 AI 读书
            </el-button>
            <el-button size="large" style="width: 100%" @click="goNotes(detail)">查看本书笔记</el-button>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Grid, List, UploadFilled, Reading } from '@element-plus/icons-vue'
import { listBooks, getBook } from '../api'

const router = useRouter()
const books = ref([])
const loading = ref(false)
const keyword = ref('')
const statusFilter = ref('')
const view = ref('card')

const drawer = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

const statusText = (s) => ({ READY: '可阅读', BUILDING: '构建中', FAILED: '构建失败' }[s] || s || '未知')
const statusTag = (s) => ({ READY: 'success', BUILDING: 'warning', FAILED: 'danger' }[s] || 'info')
const fmtDate = (iso) => (iso ? iso.slice(0, 16).replace('T', ' ') : '')

const palettes = [
  ['#14532d', '#2f855a'], ['#1e6a3f', '#5b8c6a'], ['#0e3a23', '#1f7a54'],
  ['#2f855a', '#86b59b'], ['#14532d', '#3e6b4f'], ['#166534', '#4ade80']
]
function coverGradient(b) {
  let h = 0
  for (const ch of (b.title || '书')) h = (h * 31 + ch.charCodeAt(0)) % 997
  const [a, c] = palettes[h % palettes.length]
  return `linear-gradient(155deg, ${a} 0%, ${c} 100%)`
}

async function load() {
  loading.value = true
  try {
    const data = await listBooks({
      page: 1, page_size: 50,
      keyword: keyword.value || undefined,
      status: statusFilter.value || undefined
    })
    books.value = data.items || []
  } catch { books.value = [] } finally { loading.value = false }
}

let kwTimer = null
function onKeyword() { clearTimeout(kwTimer); kwTimer = setTimeout(load, 300) }

async function openDetail(b) {
  drawer.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getBook(b.book_id)
  } catch { detail.value = b } finally { detailLoading.value = false }
}

function goRead(b) { router.push({ path: '/reader', query: { book: b.book_id } }) }
function goNotes(b) { router.push({ path: '/notes', query: { book: b.book_id } }) }

onMounted(load)
</script>

<style scoped>
.lib-head {
  display: flex; align-items: center; justify-content: space-between;
  background: #fff; border: 1px solid #e8eee9; border-radius: 12px;
  padding: 14px 18px; margin-bottom: 14px; flex-wrap: wrap; gap: 10px;
}
.lib-title { font-size: 19px; font-weight: 700; color: #14532d; }
.lib-tools { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.view-switch :deep(.el-button) { padding: 8px 11px; }

.shelf {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
}
.shelf-card {
  background: #fff; border: 1px solid #e8eee9; border-radius: 14px;
  overflow: hidden; cursor: pointer; position: relative;
  transition: transform .18s, box-shadow .18s;
}
.shelf-card:hover { transform: translateY(-3px); box-shadow: 0 10px 28px rgba(16,42,28,.12); }
.cover {
  height: 140px; position: relative;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  color: #fff;
}
.cover-letter { font-size: 40px; font-weight: 800; opacity: .9; text-shadow: 0 2px 8px rgba(0,0,0,.2); }
.cover-title {
  position: absolute; left: 10px; right: 10px; bottom: 8px;
  font-size: 12px; font-weight: 600; text-align: center;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: rgba(255,255,255,.92);
}
.cover-progress { position: absolute; left: 10px; right: 10px; bottom: 28px; }
.card-body { padding: 11px 14px 12px; }
.card-title { font-weight: 600; font-size: 14px; color: #1f2937; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.card-author { font-size: 12px; color: #94a3b8; margin-top: 2px; }
.card-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 9px; }
.chunks { font-size: 12px; color: #a0aec0; }

.hover-actions {
  position: absolute; inset: 0;
  background: rgba(16, 42, 28, 0.55);
  display: flex; align-items: center; justify-content: center; gap: 8px;
  opacity: 0; transition: opacity .18s; backdrop-filter: blur(2px);
}
.shelf-card:hover .hover-actions { opacity: 1; }
.hover-actions .el-button { margin: 0; }

.lib-table { border-radius: 12px; overflow: hidden; }
.list-title { display: flex; align-items: center; gap: 10px; }
.list-cover {
  width: 28px; height: 36px; border-radius: 4px; color: #fff;
  font-size: 14px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
}

.detail-cover {
  width: 120px; height: 160px; border-radius: 10px; margin: 0 auto;
  color: #fff; font-size: 52px; font-weight: 800;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 8px 24px rgba(16,42,28,.18);
}
.detail-title { text-align: center; font-size: 18px; font-weight: 700; margin-top: 14px; color: #1f2937; }
.detail-author { text-align: center; font-size: 13px; color: #94a3b8; margin-top: 4px; }
.detail-actions { display: flex; flex-direction: column; gap: 10px; margin-top: 20px; }
</style>
