<!--
  书库管理（F1 + F2 的构建视图）。
  一块页面承担三件事：
    1. 书籍列表：分页、关键词/状态筛选、回收站切换；
    2. 上传：≤10MB 走一次性上传，>10MB 走分片续传，重名走二次确认；
    3. 详情抽屉：构建进度轮询、章节树、切片预览、重建（带内存试算）。
  构建中的书通过 schedulePoll 每 2s 自刷新，全部就绪后自动停止轮询。
-->
<template>
  <div class="library">
    <!-- ==================== 顶部工具条 ==================== -->
    <div class="toolbar">
      <div class="left">
        <h2>书库管理</h2>
        <el-tag v-if="trashMode" type="warning" effect="plain" size="small">
          回收站 · {{ trashRetainDays }} 天后自动清理
        </el-tag>
        <el-tag v-else type="info" effect="plain" size="small">共 {{ total }} 本</el-tag>
      </div>
      <div class="right">
        <el-input
          v-model="keyword"
          placeholder="搜索书名/作者"
          clearable
          style="width: 200px"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="reload">
          <el-option label="可问答" value="READY" />
          <el-option label="构建中" value="BUILDING" />
          <el-option label="失败" value="FAILED" />
        </el-select>
        <el-switch
          v-model="trashMode"
          active-text="回收站"
          inline-prompt
          @change="onTrashToggle"
        />
        <!-- element-plus 的 el-upload 内部走自己的 XHR，无法拿到统一响应信封，
             这里用原生 input + 自己的进度条，避免二次封装 -->
        <el-button v-if="!trashMode" type="primary" @click="pickFile">
          上传 PDF
        </el-button>
        <input
          ref="fileInput"
          type="file"
          accept="application/pdf,.pdf"
          style="display: none"
          @change="onFilePicked"
        />
      </div>
    </div>

    <!-- ==================== 上传进度 ==================== -->
    <el-card v-if="uploading" class="upload-card" shadow="never">
      <div class="upload-head">
        <span>{{ uploadName }}</span>
        <el-tag size="small" type="info">{{ uploadPhase }}</el-tag>
      </div>
      <el-progress
        :percentage="uploadPercent"
        :status="uploadPercent === 100 && uploadPhase === '解析入库' ? 'success' : undefined"
        :stroke-width="14"
        striped
        :striped-flow="uploadPhase === '解析入库'"
      />
    </el-card>

    <!-- ==================== 列表 ==================== -->
    <el-table :data="books" v-loading="loading" style="width: 100%" row-key="book_id">
      <el-table-column label="书名" min-width="220">
        <template #default="{ row }">
          <div class="title-cell">
            <span class="title-text" @click="openDetail(row)">{{ row.title }}</span>
            <span v-if="row.author" class="author">{{ row.author }}</span>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="构建进度" width="180">
        <template #default="{ row }">
          <div v-if="isBuilding(row.status)" class="progress-cell">
            <el-progress
              :percentage="row.build_progress || 0"
              :stroke-width="10"
              :status="row.status === 'FAILED' ? 'exception' : undefined"
            />
            <span class="stage">{{ stageLabel(row.build_sub_stage) }}</span>
          </div>
          <span v-else-if="row.status === 'FAILED'" class="err" :title="row.error_message">
            {{ row.error_message || '构建失败' }}
          </span>
          <span v-else-if="row.status === 'READY'" class="ok">已完成</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>

      <el-table-column v-if="!trashMode" label="切片 / 章节" width="130">
        <template #default="{ row }">
          <span v-if="row.chunk_count">{{ row.chunk_count }} 片</span>
          <span v-else class="muted">—</span>
          <div v-if="row.chapter_recognized_rate != null" class="rate">
            识别率 {{ Math.round(row.chapter_recognized_rate * 100) }}%
          </div>
        </template>
      </el-table-column>

      <el-table-column v-if="!trashMode" prop="note_count" label="笔记" width="80" />

      <el-table-column v-if="trashMode" label="剩余时间" width="130">
        <template #default="{ row }">
          <span :class="row.remaining_hours < 24 ? 'err' : ''">
            {{ remainText(row.remaining_hours) }}
          </span>
        </template>
      </el-table-column>

      <el-table-column label="上传时间" width="170">
        <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
      </el-table-column>

      <el-table-column label="操作" :width="trashMode ? 150 : 210" fixed="right">
        <template #default="{ row }">
          <!-- 回收站 -->
          <template v-if="trashMode">
            <el-button link type="primary" @click="onRestore(row)">恢复</el-button>
            <el-button link type="danger" @click="onPurge(row)">彻底删除</el-button>
          </template>

          <!-- 正常列表 -->
          <template v-else>
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="isBuilding(row.status)"
              link
              type="warning"
              @click="onCancel(row)"
            >
              取消
            </el-button>
            <el-button
              v-else-if="row.status === 'FAILED' || row.status === 'CANCELLED'"
              link
              type="primary"
              @click="onRetry(row)"
            >
              重试
            </el-button>
            <el-button v-else link type="primary" @click="onRebuild(row)">重建</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </template>
      </el-table-column>

      <template #empty>
        <el-empty :description="trashMode ? '回收站是空的' : '还没有书，点右上角上传 PDF'" />
      </template>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next, total"
        @current-change="load"
      />
    </div>

    <!-- ==================== 详情抽屉 ==================== -->
    <el-drawer v-model="drawer" :title="current?.title || '书籍详情'" size="62%">
      <template v-if="current">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(current.status)" size="small">
              {{ statusLabel(current.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="页数">{{ current.total_pages ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="切片数">{{ current.chunk_count ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="章节识别率">
            {{ current.chapter_recognized_rate != null
              ? Math.round(current.chapter_recognized_rate * 100) + '%' : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="文件大小">{{ fmtSize(current.file_size) }}</el-descriptions-item>
          <el-descriptions-item label="笔记数">{{ current.note_count ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="错误" :span="3">
            <span :class="current.error_message ? 'err' : 'muted'">
              {{ current.error_code ? `[${current.error_code}] ` : '' }}{{ current.error_message || '无' }}
            </span>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 阅读/分析入口：全文阅读（路 A）、AI 读书、本书笔记 -->
        <div class="drawer-actions" style="margin-top: 12px">
          <el-button
            type="primary"
            :disabled="current.status !== 'READY'"
            @click="goReadBook(current)"
          >
            阅读全文
          </el-button>
          <el-button
            type="success"
            plain
            :disabled="current.status !== 'READY'"
            @click="goAIBook(current)"
          >
            AI 读书
          </el-button>
          <el-button @click="goNotesBook(current)">查看笔记</el-button>
        </div>

        <el-alert
          v-for="(w, i) in buildWarnings"
          :key="i"
          type="warning"
          :title="w"
          :closable="false"
          show-icon
          style="margin-top: 10px"
        />

        <el-tabs v-model="tab" class="detail-tabs">
          <!-- 构建进度 -->
          <el-tab-pane label="构建进度" name="build">
            <div v-if="status">
              <el-progress
                :percentage="status.build_progress || 0"
                :stroke-width="16"
                text-inside
              />
              <el-descriptions :column="2" border size="small" style="margin-top: 12px">
                <el-descriptions-item label="子阶段">
                  {{ stageLabel(status.build_sub_stage) }}
                </el-descriptions-item>
                <el-descriptions-item label="批次">
                  {{ status.last_completed_batch ?? 0 }} / {{ status.total_batches ?? '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="计划切片">{{ status.chunk_count_planned ?? '—' }}</el-descriptions-item>
                <el-descriptions-item label="已入库">{{ status.chunk_count_actual ?? '—' }}</el-descriptions-item>
                <el-descriptions-item label="失败批次" :span="2">
                  <span v-if="(status.failed_batches || []).length" class="err">
                    {{ status.failed_batches.join(', ') }}
                  </span>
                  <span v-else class="muted">无</span>
                </el-descriptions-item>
              </el-descriptions>

              <div class="drawer-actions">
                <el-button
                  v-if="isBuilding(current.status)"
                  type="warning"
                  @click="onCancel(current)"
                >
                  取消构建
                </el-button>
                <el-button
                  v-if="current.status === 'FAILED' || current.status === 'CANCELLED'"
                  type="primary"
                  @click="onRetry(current)"
                >
                  断点续传
                </el-button>
                <el-button v-if="!isBuilding(current.status)" @click="onRebuild(current)">
                  重建（选粒度）
                </el-button>
              </div>
            </div>
            <el-empty v-else description="尚无构建记录" />
          </el-tab-pane>

          <!-- 章节结构 -->
          <el-tab-pane :label="`章节 (${chapters.length})`" name="chapters">
            <el-alert
              v-if="!chapters.length"
              type="info"
              :closable="false"
              title="未识别到章节结构，问答将退化为纯向量检索"
              show-icon
            />
            <el-tree
              v-else
              :data="chapterTree"
              :props="{ label: 'title', children: 'children' }"
              node-key="chapter_path"
              default-expand-all
              @node-click="onChapterClick"
            >
              <template #default="{ data }">
                <span class="tree-node">
                  <span class="tree-title">{{ data.title }}</span>
                  <span class="tree-meta">p{{ data.start_page }}-{{ data.end_page }}</span>
                </span>
              </template>
            </el-tree>
          </el-tab-pane>

          <!-- 切片预览 -->
          <el-tab-pane :label="`切片 (${chunkTotal})`" name="chunks">
            <div class="chunk-head">
              <span v-if="chunkChapterFilter" class="filter-chip">
                已筛选：{{ chunkChapterFilter }}
                <el-button link type="primary" size="small" @click="clearChapterFilter">清除</el-button>
              </span>
              <span v-else class="muted">随机抽样校验切片粒度是否合理</span>
            </div>
            <div v-loading="chunkLoading" class="chunk-list">
              <div v-for="c in chunks" :key="c.chunk_id" class="chunk-item">
                <div class="chunk-meta">
                  <el-tag size="small" effect="plain">#{{ c.position }}</el-tag>
                  <span class="chunk-page">p{{ c.page }}</span>
                  <span class="chunk-wc">{{ c.word_count }} 字</span>
                  <span class="chunk-batch">批次 {{ c.batch_id }}</span>
                  <span class="chunk-path">{{ c.chapter_path || '未归章' }}</span>
                  <el-tag
                    size="small"
                    :type="c.embedding_status === 'DONE' ? 'success' : 'info'"
                    effect="plain"
                  >
                    {{ c.embedding_status }}
                  </el-tag>
                </div>
                <div class="chunk-content">{{ c.content }}</div>
              </div>
              <el-empty v-if="!chunks.length && !chunkLoading" description="暂无切片" />
            </div>
            <el-pagination
              v-model:current-page="chunkPage"
              :page-size="chunkPageSize"
              :total="chunkTotal"
              layout="prev, pager, next"
              small
              class="chunk-pager"
              @current-change="loadChunks"
            />
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>

    <!-- ==================== 删除确认 ==================== -->
    <el-dialog v-model="delDialog" title="移入回收站" width="440px">
      <p>
        将把《{{ delTarget?.title }}》移入回收站。
      </p>
      <p class="muted small">
        回收站保留 {{ trashRetainDays }} 天，期间可随时恢复；到期后连同 PDF、切片、向量一起永久清除。
      </p>
      <el-checkbox v-model="keepNotes">保留该书已写的笔记</el-checkbox>
      <template #footer>
        <el-button @click="delDialog = false">取消</el-button>
        <el-button type="danger" :loading="acting" @click="confirmDelete">移入回收站</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 重建粒度选择 ==================== -->
    <el-dialog v-model="rebuildDialog" title="重建知识库" width="480px">
      <p>重建会清空该书已有的切片与向量，按新粒度重新解析、切片、入库。</p>
      <el-radio-group v-model="granularity" class="gran">
        <el-radio-button label="coarse">粗 · 1000 字</el-radio-button>
        <el-radio-button label="medium">中 · 600 字</el-radio-button>
        <el-radio-button label="fine">细 · 300 字</el-radio-button>
      </el-radio-group>
      <div class="preview-stat">
        <template v-if="previewLoading">正在试算，不消耗 Embedding 额度…</template>
        <template v-else-if="previewStat">
          预计 <b>{{ previewStat.chunk_count }}</b> 片 · 中位片长
          <b>{{ previewStat.chunk_stats.p50 }}</b> 字 · 章节覆盖
          <b>{{ Math.round((previewStat.chapter?.coverage || 0) * 100) }}%</b>
          · 约 {{ previewStat.total_batches }} 批<template v-if="previewStat.cached_parse">（解析已缓存）</template>
          <div v-if="previewStat.warnings?.length" class="warn">
            {{ previewStat.warnings[0] }}
          </div>
        </template>
        <template v-else>暂时无法试算（原书文件缺失时只能直接重建）</template>
      </div>
      <p class="muted small">
        粗粒度片数少、召回范围大；细粒度定位准但片数多、检索噪声也大。默认用「中」。
      </p>
      <template #footer>
        <el-button @click="rebuildDialog = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="confirmRebuild">开始重建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  cancelBuild,
  deleteBook,
  getBook,
  getBuildStatus,
  listBooks,
  listChapters,
  listChunks,
  listTrash,
  purgeBook,
  rebuildBook,
  restoreBook,
  retryBuild,
  uploadBook,
  uploadBookResumable,
  getBuildPreview
} from '../api'

// ---------------------------------------------------------------------------
// 列表状态
// ---------------------------------------------------------------------------
const books = ref([])
const router = useRouter()
const loading = ref(false)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const keyword = ref('')
const statusFilter = ref('')
const trashMode = ref(false)
const trashRetainDays = ref(7)

// ---------------------------------------------------------------------------
// 上传
// ---------------------------------------------------------------------------
const fileInput = ref(null)
const uploading = ref(false)
const uploadName = ref('')
const uploadPercent = ref(0)
const uploadPhase = ref('上传中')
const pendingConfirm = ref(null) // { file, meta } 重复书名时暂存

// ---------------------------------------------------------------------------
// 详情抽屉
// ---------------------------------------------------------------------------
const drawer = ref(false)
const current = ref(null)
const status = ref(null)
const chapters = ref([])
const tab = ref('build')
const buildWarnings = ref([])

// 切片
const chunks = ref([])
const chunkTotal = ref(0)
const chunkPage = ref(1)
const chunkPageSize = ref(10)
const chunkLoading = ref(false)
const chunkChapterFilter = ref('')

// 弹窗
const delDialog = ref(false)
const delTarget = ref(null)
const keepNotes = ref(true)
const rebuildDialog = ref(false)
const rebuildTarget = ref(null)
const granularity = ref('medium')
const acting = ref(false)

// 轮询定时器
let pollTimer = null

// ---------------------------------------------------------------------------
// 展示映射：后端枚举 → 中文文案 / el-tag 颜色
// ---------------------------------------------------------------------------
const STATUS_LABEL = {
  UPLOADING: '上传中',
  BUILDING: '构建中',
  REBUILDING: '重建中',
  READY: '可问答',
  FAILED: '构建失败',
  CANCELLED: '已取消',
  DELETING: '删除中',
  DELETED: '已删除',
  PURGING: '清除中',
  PURGED: '已清除'
}
const STAGE_LABEL = {
  PARSE: '解析 PDF',
  RECOGNIZE: '识别章节',
  CHUNK: '语义切片',
  EMBED: '向量化',
  INGEST: '入库 Chroma'
}

const statusLabel = (s) => STATUS_LABEL[s] || s || '—'
const stageLabel = (s) => (s ? STAGE_LABEL[s] || s : '—')
const isBuilding = (s) => s === 'BUILDING' || s === 'REBUILDING' || s === 'UPLOADING'

/** 状态 → el-tag 类型：只有 READY 是绿色、FAILED 是红色，其余统一灰色/橙色 */
const statusType = (s) => {
  if (s === 'READY') return 'success'
  if (s === 'BUILDING' || s === 'REBUILDING' || s === 'UPLOADING') return 'warning'
  if (s === 'FAILED') return 'danger'
  return 'info'
}

/** 后端时间统一是 ISO-8601 UTC，这里转本地时区的 yyyy-MM-dd HH:mm */
const fmtTime = (s) => {
  if (!s) return '—'
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return s
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 文件大小展示：<1MB 用 KB，否则一位小数的 MB */
const fmtSize = (n) => {
  if (!n) return '—'
  if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}

/** 回收站剩余保留时间：<24h 显示小时，否则显示"X 天 Y 小时" */
const remainText = (h) => {
  if (h == null) return '—'
  if (h <= 0) return '即将清理'
  if (h < 24) return `${h} 小时`
  return `${Math.floor(h / 24)} 天 ${h % 24} 小时`
}

/**
 * 章节扁平列表 → el-tree 的嵌套结构。
 * <p>
 * 后端只给 level 与 chapter_path，不带 parent_id；这里用栈按 level 还原父子关系：
 * 遇到不大于栈顶的层级就先弹栈，剩下的栈顶即为父节点。O(n) 且不需要预处理 path。
 */
const chapterTree = computed(() => {
  const roots = []
  const stack = []
  for (const c of chapters.value) {
    const node = { ...c, children: [] }
    const lvl = Math.max(1, c.level || 1)
    while (stack.length && stack[stack.length - 1]._lvl >= lvl) stack.pop()
    if (stack.length) {
      stack[stack.length - 1].node.children.push(node)
    } else {
      roots.push(node)
    }
    stack.push({ _lvl: lvl, node })
  }
  return roots
})

// ---------------------------------------------------------------------------
// 数据加载
// ---------------------------------------------------------------------------
/**
 * 加载当前列表：回收站模式与正常列表走不同接口，字段结构一致。
 * 无论成功失败都在 finally 里重新排一次轮询，保证构建中的书不会漏刷。
 */
async function load () {
  loading.value = true
  try {
    if (trashMode.value) {
      const data = await listTrash()
      books.value = data.items || []
      total.value = data.total || 0
      trashRetainDays.value = data.retain_days ?? 7
    } else {
      const data = await listBooks({
        page: page.value,
        page_size: pageSize.value,
        keyword: keyword.value || undefined,
        status: statusFilter.value || undefined
      })
      books.value = data.items || []
      total.value = data.total || 0
    }
  } catch (e) {
    // http.js 已统一提示
  } finally {
    loading.value = false
    schedulePoll()
  }
}

/** 重置到第一页再查（搜索、切状态、切回收站时用） */
const reload = () => {
  page.value = 1
  load()
}

/** 回收站开关：页码归 1，避免在旧页码上查到空页 */
const onTrashToggle = () => {
  page.value = 1
  load()
}

/**
 * 有构建中的书就 2s 后自动刷新，全部就绪就停 —— 避免常驻轮询打满后端。
 */
function schedulePoll () {
  clearTimeout(pollTimer)
  const active = !trashMode.value && books.value.some((b) => isBuilding(b.status))
  if (active) {
    pollTimer = setTimeout(() => load(), 2000)
  }
}

// ---------------------------------------------------------------------------
// 上传
// ---------------------------------------------------------------------------
/** 打开系统文件选择框；重置 input.value 由 onFilePicked 负责 */
const pickFile = () => fileInput.value?.click()

/**
 * 选中文件后的前置校验：扩展名、50MB 上限，并对 >10MB 的文件提前告知耗时。
 * 校验通过直接进入上传流程。
 */
function onFilePicked (e) {
  const file = e.target.files?.[0]
  e.target.value = '' // 允许重复选同一个文件
  if (!file) return

  if (!/\.pdf$/i.test(file.name)) {
    ElMessage.warning('只支持 PDF 文件')
    return
  }
  if (file.size > 50 * 1024 * 1024) {
    ElMessage.warning('文件超过 50MB 上限')
    return
  }
  // 大文件先提醒，避免用户以为卡死
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.info('文件较大，解析与向量化需要几分钟，可先离开本页')
  }
  doUpload(file, false)
}

/**
 * 执行上传。
 * <p>
 * 进度条只表示"上传"这一段（0~70%），上传完成后直接跳到 100% 并提示"解析入库"——
 * 后端构建是异步的，真实进度由列表轮询反映，不在这里假装推进。
 * 重名（20003）时弹确认，用户同意后以 confirmDuplicate=true 重跑本函数；
 * 分片模式下后端会保留会话，重跑只补缺失片。
 *
 * @param {File} file
 * @param {boolean} confirmDuplicate 是否已确认"同名仍上传"
 */
async function doUpload (file, confirmDuplicate) {
  uploading.value = true
  uploadName.value = file.name
  uploadPercent.value = 0
  uploadPhase.value = '上传中'
  try {
    const meta = confirmDuplicate ? { confirm_duplicate: true } : {}
    // >10MB 走分片续传：断网/刷新后只补缺失片，不用从头再传
    const uploader = file.size > 10 * 1024 * 1024 ? uploadBookResumable : uploadBook
    const data = await uploader(file, meta, (p) => {
      // 上传段占 0~70%，剩下的 70~100% 表示"后端已接收，正在解析入队"
      uploadPhase.value = file.size > 10 * 1024 * 1024 ? '分片上传（可断线续传）' : '上传中'
      uploadPercent.value = Math.round(p * 0.7)
    })
    uploadPercent.value = 100
    uploadPhase.value = '解析入库'
    ElMessage.success(`《${data.title}》已上传，正在后台构建知识库`)
    page.value = 1
    await load()
  } catch (err) {
    // 20003 重名：后端返回 data.existing_book_id，需要二次确认
    const body = err?.response?.data
    if (body?.code === 20003) {
      try {
        await ElMessageBox.confirm(
          `${body.message}。仍要作为新书上传吗？`,
          '检测到同名书籍',
          { confirmButtonText: '继续上传', cancelButtonText: '取消', type: 'warning' }
        )
        await doUpload(file, true)
        return
      } catch {
        // 用户取消
      }
    }
    uploading.value = false
  } finally {
    setTimeout(() => { uploading.value = false }, 800)
  }
}

// ---------------------------------------------------------------------------
// 详情
// ---------------------------------------------------------------------------
/** 打开详情抽屉：先清空上一本的状态，避免闪现旧数据 */
async function openDetail (row) {
  drawer.value = true
  tab.value = 'build'
  current.value = row
  status.value = null
  chapters.value = []
  chunks.value = []
  chunkTotal.value = 0
  chunkPage.value = 1
  chunkChapterFilter.value = ''
  buildWarnings.value = []
  await refreshDetail(row.book_id)
}

// ===== 阅读/分析入口（READY 才可读） =====
const goReadBook = (b) => router.push(`/book/${b.book_id}/read`)
const goAIBook = (b) => router.push(`/reader?book=${b.book_id}`)
const goNotesBook = (b) => router.push(`/notes?bookId=${b.book_id}`)

/**
 * 刷新详情：详情与构建状态并行请求，任一失败都不影响另一个（各自 catch 成 null）。
 * 只有确实有切片时才拉章节与切片列表，省掉构建初期的空请求。
 */
async function refreshDetail (bookId) {
  try {
    const [detail, st] = await Promise.all([
      getBook(bookId).catch(() => null),
      getBuildStatus(bookId).catch(() => null)
    ])
    if (detail) current.value = detail
    status.value = st
    buildWarnings.value = st?.warnings || []

    if (status.value?.chunk_count_actual || current.value?.chunk_count) {
      chapters.value = (await listChapters(bookId).catch(() => ({ chapters: [] }))).chapters || []
      await loadChunks()
    }
  } catch (e) {
    // 已统一提示
  }
}

/** 加载切片分页；失败时清空列表而不是保留上一次的旧切片 */
async function loadChunks () {
  if (!current.value) return
  chunkLoading.value = true
  try {
    const data = await listChunks(current.value.book_id, {
      page: chunkPage.value,
      page_size: chunkPageSize.value,
      chapter: chunkChapterFilter.value || undefined
    })
    chunks.value = data.items || []
    chunkTotal.value = data.total || 0
  } catch (e) {
    chunks.value = []
    chunkTotal.value = 0
  } finally {
    chunkLoading.value = false
  }
}

/** 点章节树节点：按该章节前缀过滤切片，并切到切片页签 */
function onChapterClick (node) {
  chunkChapterFilter.value = node.chapter_path
  chunkPage.value = 1
  tab.value = 'chunks'
  loadChunks()
}

/** 清除章节过滤，回到全书切片 */
function clearChapterFilter () {
  chunkChapterFilter.value = ''
  chunkPage.value = 1
  loadChunks()
}

// ---------------------------------------------------------------------------
// 操作：删除 / 恢复 / 彻底删除 / 重试 / 取消 / 重建
// ---------------------------------------------------------------------------
/** 打开删除确认框；默认保留笔记（需求：删除书籍不删笔记） */
function onDelete (row) {
  delTarget.value = row
  keepNotes.value = true
  delDialog.value = true
}

/** 确认软删除：进回收站，7 天内可恢复 */
async function confirmDelete () {
  acting.value = true
  try {
    await deleteBook(delTarget.value.book_id, { keep_notes: keepNotes.value })
    delDialog.value = false
    ElMessage.success('已移入回收站')
    await load()
  } catch (e) {
    // 已提示
  } finally {
    acting.value = false
  }
}

/** 从回收站恢复：后端会按原文件是否还在，决定直接 READY 还是自动重建 */
async function onRestore (row) {
  try {
    await restoreBook(row.book_id)
    ElMessage.success('已恢复，正在重建知识库')
    await load()
  } catch (e) {
    /* 已提示 */
  }
}

/** 彻底删除：不可恢复，用 error 类型的确认框强调风险 */
async function onPurge (row) {
  try {
    await ElMessageBox.confirm(
      `彻底删除《${row.title}》？PDF、切片、向量将一并清除，无法恢复。`,
      '危险操作',
      { type: 'error', confirmButtonText: '彻底删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await purgeBook(row.book_id)
    ElMessage.success('已彻底删除')
    await load()
  } catch (e) {
    /* 已提示 */
  }
}

/** 重试构建：不传 from_batch，由后端从断点续跑 */
async function onRetry (row) {
  try {
    await retryBuild(row.book_id)
    ElMessage.success('已提交续跑，从上次断点继续')
    if (drawer.value) await refreshDetail(row.book_id)
    await load()
  } catch (e) {
    /* 已提示 */
  }
}

/** 取消构建：已完成的批次保留，之后仍可重试继续 */
async function onCancel (row) {
  try {
    await cancelBuild(row.book_id)
    ElMessage.success('已取消，已完成的批次会保留')
    if (drawer.value) await refreshDetail(row.book_id)
    await load()
  } catch (e) {
    /* 已提示 */
  }
}

/** 构建前试算结果（dry_run=true 才有值），仅用于重建弹窗里的预览数字 */
const previewStat = ref(null)
const previewLoading = ref(false)

/** 按当前选中的粒度试算：不落库、不调 Embedding，切换粒度时自动刷新 */
async function loadPreview () {
  if (!rebuildTarget.value) return
  previewLoading.value = true
  previewStat.value = null
  try {
    const data = await getBuildPreview(rebuildTarget.value.book_id, { granularity: granularity.value })
    if (data?.dry_run) previewStat.value = data
  } catch {
    previewStat.value = null
  } finally {
    previewLoading.value = false
  }
}

/** 粒度切换即重新试算：后端有解析缓存，切换是三档里最便宜的操作 */
watch(granularity, () => { if (rebuildDialog.value) loadPreview() })

/** 打开重建弹窗：默认中粒度并立即试算一次 */
function onRebuild (row) {
  rebuildTarget.value = row
  granularity.value = 'medium'
  rebuildDialog.value = true
  loadPreview()
}

/**
 * 三档粒度对应的后端参数（与需求 F2.3 一致）。
 * 注意 fine 的 overlap 是 60 而不是 100：片长只有 300，100 字重叠占比过高会重复召回。
 */
const REBUILD_PARAMS = {
  coarse: { chunk_size: 1000, overlap: 100 },
  medium: { chunk_size: 600, overlap: 100 },
  fine: { chunk_size: 300, overlap: 60 }
}

/** 提交重建：重建是异步的，接口 202 返回后靠列表轮询看进度 */
async function confirmRebuild () {
  acting.value = true
  try {
    const p = REBUILD_PARAMS[granularity.value]
    await rebuildBook(rebuildTarget.value.book_id, p)
    rebuildDialog.value = false
    ElMessage.success('已开始重建')
    if (drawer.value && current.value?.book_id === rebuildTarget.value.book_id) {
      await refreshDetail(current.value.book_id)
    }
    await load()
  } catch (e) {
    /* 已提示 */
  } finally {
    acting.value = false
  }
}

// ---------------------------------------------------------------------------
// 生命周期：进入即加载；离开必须清掉轮询定时器，否则切页后仍在后台请求
// ---------------------------------------------------------------------------
onMounted(load)
onBeforeUnmount(() => clearTimeout(pollTimer))
</script>

<style scoped>
.library { padding: 4px 2px; }

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.toolbar .left { display: flex; align-items: center; gap: 10px; }
.toolbar .left h2 { margin: 0; font-size: 18px; }
.toolbar .right { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }

.upload-card { margin-bottom: 14px; background: #f8fafc; }
.upload-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-size: 13px;
  color: #475569;
}

.title-cell { display: flex; flex-direction: column; }
.title-text { cursor: pointer; color: #1d4ed8; font-weight: 500; }
.title-text:hover { text-decoration: underline; }
.author { font-size: 12px; color: #94a3b8; }

.progress-cell { display: flex; flex-direction: column; gap: 2px; }
.stage { font-size: 11px; color: #64748b; }
.rate { font-size: 11px; color: #64748b; }

.err { color: #dc2626; font-size: 12px; }
.ok { color: #16a34a; font-size: 12px; }
.muted { color: #94a3b8; font-size: 12px; }
.small { font-size: 12px; }

.pager { display: flex; justify-content: flex-end; margin-top: 12px; }

.detail-tabs { margin-top: 16px; }
.drawer-actions { margin-top: 14px; display: flex; gap: 8px; flex-wrap: wrap; }

.tree-node { display: flex; gap: 10px; align-items: baseline; }
.tree-title { font-size: 13px; }
.tree-meta { font-size: 11px; color: #94a3b8; }

.chunk-head { margin-bottom: 8px; font-size: 12px; }
.filter-chip {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  background: #eef2ff;
  color: #3730a3;
  padding: 2px 8px;
  border-radius: 4px;
}
.chunk-list { max-height: 46vh; overflow-y: auto; }
.chunk-item {
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 10px;
  background: #fcfcfd;
}
.chunk-meta {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  font-size: 11px;
  color: #64748b;
  margin-bottom: 6px;
}
.chunk-path { color: #7c3aed; }
.chunk-content {
  font-size: 13px;
  line-height: 1.65;
  color: #1f2937;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 160px;
  overflow: hidden;
}
.chunk-pager { margin-top: 6px; }

.gran { margin: 12px 0; }
.preview-stat {
  margin: 12px 0;
  padding: 10px 12px;
  border-radius: 6px;
  background: rgba(148, 163, 184, .1);
  font-size: 13px;
  line-height: 1.7;
}
.preview-stat .warn { color: #f59e0b; margin-top: 4px; }
</style>
