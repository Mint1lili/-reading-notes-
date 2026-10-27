<!--
  全文阅读（路 A）：复用组员 F2 的章节/切片接口，零后端改动。
  左侧章节树（listChapters 还原树），右侧按章加载切片正文（listChunks 按 chapter 过滤）。
  阅读定位：我们是"AI 读书 + 全文可回读"的知识阅读器——AI 问答的引用来源，就是这里能翻到的原文。
-->
<template>
  <div class="reading">
    <!-- ==================== 顶栏 ==================== -->
    <header class="topbar">
      <el-button link type="primary" @click="back">
        <el-icon style="margin-right: 4px"><ArrowLeft /></el-icon>书库
      </el-button>
      <div class="top-title">
        <span class="book-title">{{ book?.title || '加载中…' }}</span>
        <span class="book-meta" v-if="book">
          {{ book.author || '佚名' }} · {{ book.total_pages || '—' }} 页
        </span>
      </div>
      <div class="top-actions">
        <el-button v-if="book" type="primary" plain @click="goAI">
          <el-icon style="margin-right: 4px"><ChatDotRound /></el-icon>AI 读书
        </el-button>
      </div>
    </header>

    <div class="body">
      <!-- ==================== 左侧章节树 ==================== -->
      <aside class="toc">
        <div class="toc-head">
          <span>目录</span>
          <span class="toc-count">{{ flatChapters.length }} 章</span>
          <el-button link size="small" type="primary" @click="showAll">全部</el-button>
        </div>
        <el-tree
          v-loading="treeLoading"
          :data="tree"
          :props="{ label: 'title', children: 'children' }"
          node-key="chapter_path"
          highlight-current
          default-expand-all
          @node-click="onChapterClick"
        >
          <template #default="{ data }">
            <span class="toc-node">
              <span class="toc-title">{{ data.title }}</span>
              <span class="toc-page" v-if="data.start_page != null">p{{ data.start_page }}-{{ data.end_page }}</span>
            </span>
          </template>
        </el-tree>
        <div v-if="!treeLoading && !flatChapters.length" class="toc-empty">
          这本书还没有可阅读的章节。<br />请确认已上传 PDF 并构建成功（状态 READY）。
        </div>
      </aside>

      <!-- ==================== 右侧正文 ==================== -->
      <main class="content" v-loading="loading">
        <template v-if="chunks.length">
          <div class="chapter-title">{{ currentTitle }}</div>
          <article class="text">
            <div v-for="c in chunks" :key="c.chunk_id" class="para-block">
              <span class="para-page" v-if="c.page">{{ c.page }}</span>
              <p class="para">{{ c.content }}</p>
            </div>
          </article>
          <el-pagination
            class="pager"
            layout="prev, pager, next, total"
            :total="total"
            :current-page="page"
            :page-size="pageSize"
            @current-change="gotoPage"
          />
        </template>

        <el-empty
          v-else-if="!loading"
          description="请从左侧选择章节开始阅读；无章节数据时需先构建知识库"
        />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getBook, listChapters, listChunks } from '../api'

const route = useRoute()
const router = useRouter()
const bookId = route.params.bookId

const book = ref(null)
const flatChapters = ref([])
const tree = ref([])
const chunks = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const currentChapter = ref(null)
const loading = ref(false)
const treeLoading = ref(false)

const back = () => router.push('/library')
const goAI = () => router.push(`/reader?book=${bookId}`)

const currentTitle = computed(() => {
  if (currentChapter.value) return currentChapter.value.title || currentChapter.value.chapter_path
  return book.value?.title || ''
})

/** 章节扁平列表 → el-tree 嵌套结构（与 LibraryView 同款栈算法） */
const buildTree = (flat) => {
  const root = []
  const stack = [] // { level, node }
  for (const c of flat || []) {
    const node = {
      title: c.title || c.chapter_path || '未命名章节',
      chapter_path: c.chapter_path,
      start_page: c.start_page,
      end_page: c.end_page,
      children: []
    }
    while (stack.length && stack[stack.length - 1].level >= (c.level || 1)) stack.pop()
    if (stack.length) stack[stack.length - 1].node.children.push(node)
    else root.push(node)
    stack.push({ level: c.level || 1, node })
  }
  return root
}

/** 加载某章正文（chapter 为路径前缀；null 则全书第一页） */
async function loadChunks(path) {
  loading.value = true
  try {
    const data = await listChunks(bookId, {
      page: page.value,
      page_size: pageSize,
      chapter: path || undefined
    })
    chunks.value = data.items || []
    total.value = data.total || 0
  } catch {
    chunks.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onChapterClick(node) {
  currentChapter.value = node
  page.value = 1
  loadChunks(node.chapter_path).then(() => {
    // 该章无独立切片（如"前言"在构建时 chapter_path 缺失、chunk 归到未分章组）
    // → 回退显示全书，保证点击任何章节都能看到内容
    if (!chunks.value.length) {
      loadChunks(null)
      ElMessage.info(`「${node.title}」无独立切片，已显示全书内容`)
    }
  })
}

/** 阅读全部：全书连续分页（chapter 不传=全书） */
function showAll() {
  currentChapter.value = null
  page.value = 1
  loadChunks(null)
}

function gotoPage(p) {
  page.value = p
  loadChunks(currentChapter.value?.chapter_path)
}

onMounted(async () => {
  treeLoading.value = true
  try {
    book.value = await getBook(bookId).catch(() => null)
  } catch { /* 详情失败不阻塞阅读 */ }
  try {
    const data = await listChapters(bookId)
    flatChapters.value = data.chapters || []
    tree.value = buildTree(flatChapters.value)
    // 默认加载全书（chapter 不传=全书分页），从第一页开始连续阅读；
    // 左侧目录仅用于定位跳转，翻页可看完每一页内容
    currentChapter.value = null
    await loadChunks(null)
  } catch { /* 章节加载失败时右侧展示空态 */ }
  treeLoading.value = false
})
</script>

<style scoped>
.reading {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f6f8f7;
}
.topbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 20px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  flex: 0 0 auto;
}
.top-title { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.book-title { font-size: 17px; font-weight: 700; color: #111827; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.book-meta { font-size: 12px; color: #6b7280; }
.top-actions { flex: 0 0 auto; }
.body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.toc {
  width: 260px;
  flex: 0 0 auto;
  background: #fff;
  border-right: 1px solid #e5e7eb;
  overflow-y: auto;
  padding: 12px 8px;
}
.toc-head {
  display: flex;
  justify-content: space-between;
  padding: 0 8px 10px;
  font-weight: 600;
  color: #374151;
}
.toc-count { font-weight: 400; color: #9ca3af; font-size: 12px; }
.toc-node { display: flex; justify-content: space-between; align-items: center; width: 100%; padding-right: 4px; }
.toc-title { font-size: 13px; color: #374151; }
.toc-page { font-size: 11px; color: #9ca3af; }
.toc-empty { padding: 24px 12px; color: #9ca3af; font-size: 13px; line-height: 1.7; }
.content {
  flex: 1;
  overflow-y: auto;
  padding: 32px 40px 48px;
}
.chapter-title {
  font-size: 22px;
  font-weight: 700;
  color: #111827;
  margin-bottom: 24px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e5e7eb;
}
.text { max-width: 760px; margin: 0 auto; }
.para-block { position: relative; margin-bottom: 14px; }
.para {
  font-size: 16px;
  line-height: 1.9;
  color: #1f2937;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
}
.para-page {
  position: absolute;
  left: -38px;
  top: 6px;
  font-size: 11px;
  color: #b0b8c4;
  user-select: none;
}
.pager { justify-content: center; margin-top: 28px; }
</style>
