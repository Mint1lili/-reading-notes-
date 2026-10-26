<template>
  <div class="notes-page">
    <!-- ==================== 顶部筛选工具条（借鉴 Notion 数据库筛选） ==================== -->
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索标题或正文…"
        clearable
        style="width: 230px"
        @input="onKeywordInput"
        @clear="reload"
      />
      <el-select v-model="filters.bookId" placeholder="全部书籍" clearable style="width: 170px" @change="reload">
        <el-option v-for="b in books" :key="b.book_id" :label="b.title" :value="b.book_id" />
      </el-select>
      <el-select v-model="filters.sourceType" placeholder="全部来源" clearable style="width: 130px" @change="reload">
        <el-option v-for="s in SOURCE_TYPES" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-select v-model="filters.indexed" placeholder="回流状态" clearable style="width: 120px" @change="reload">
        <el-option label="已回流" value="true" />
        <el-option label="未回流" value="false" />
      </el-select>
      <span class="spacer" />
      <el-button :disabled="view === 'trash' || (!selectedIds.length && !filters.bookId)" @click="openExport">
        导出{{ selectedIds.length ? ` (${selectedIds.length})` : '' }}
      </el-button>
      <el-button type="primary" :disabled="view === 'trash'" @click="openCreate">+ 新建笔记</el-button>
    </div>

    <!-- 导出对话框（§9.11：markdown / pdf，选中 / 整本书） -->
    <el-dialog v-model="exportDialog" title="导出笔记" width="440px">
      <el-form label-width="70px">
        <el-form-item label="范围">
          <el-radio-group v-model="exportForm.scope">
            <el-radio value="selected" :disabled="!selectedIds.length">选中笔记（{{ selectedIds.length }} 篇）</el-radio>
            <el-radio value="book" :disabled="!filters.bookId">
              整本书{{ filters.bookId ? `：${bookTitle}` : '（请先在顶部选择书籍）' }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="格式">
          <el-select v-model="exportForm.format" style="width: 100%">
            <el-option label="Markdown（每条一个 .md 文件）" value="markdown" />
            <el-option label="PDF（合并单文件，中文排版）" value="pdf" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="exportForm.format === 'markdown'" label="front-matter">
          <el-switch v-model="exportForm.includeFrontMatter" active-text="YAML 头部（书名/页码/标签）" />
        </el-form-item>
        <el-alert
          v-if="exportForm.format === 'pdf'"
          type="info" :closable="false" show-icon
          title="PDF 为一页一篇的合并文件；整本书导出时按书内所有未删除笔记生成。"
        />
      </el-form>
      <template #footer>
        <el-button @click="exportDialog = false">取消</el-button>
        <el-button type="primary" :loading="exporting" @click="onExport">导出</el-button>
      </template>
    </el-dialog>

    <div class="body">
      <!-- ==================== 左栏：视图 + 标签树（借鉴 Obsidian 标签面板） ==================== -->
      <aside class="sidebar">
        <div class="side-title">视图</div>
        <div class="side-item" :class="{ active: view === 'all' }" @click="setView('all')">
          <span>全部笔记</span><span class="count">{{ total }}</span>
        </div>
        <div class="side-item" :class="{ active: view === 'indexed' }" @click="setView('indexed')">
          <span><i class="dot on" />已回流</span>
        </div>
        <div class="side-item" :class="{ active: view === 'notIndexed' }" @click="setView('notIndexed')">
          <span><i class="dot off" />未回流</span>
        </div>
        <div class="side-item" :class="{ active: view === 'trash' }" @click="setView('trash')">
          <span>🗑 回收站</span><span class="count">{{ trashTotal }}</span>
        </div>

        <div class="side-title">标签</div>
        <div
          v-for="t in tags"
          :key="t.tag_id"
          class="side-item tag"
          :class="{ active: filters.tag === t.name }"
          @click="pickTag(t.name)"
        >
          <span><i class="dot" :style="{ background: t.color || '#409EFF' }" />{{ t.name }}</span>
          <span class="count">{{ t.note_count }}</span>
        </div>
        <el-input
          v-model="newTagName"
          size="small"
          placeholder="新建标签后回车"
          style="margin-top: 10px"
          @keyup.enter="addTag"
        />
      </aside>

      <!-- ==================== 中栏：卡片流（借鉴 Readwise / 微信读书想法列表） ==================== -->
      <main class="cards">
        <div v-loading="loading" class="card-grid">
          <div
            v-for="n in notes"
            :key="n.note_id"
            class="note-card"
            :class="{ picked: selectedIds.includes(n.note_id), trash: view === 'trash' }"
            @click="view !== 'trash' && openDetail(n.note_id)"
          >
            <el-checkbox
              v-if="view !== 'trash'"
              class="pick"
              :model-value="selectedIds.includes(n.note_id)"
              @click.stop
              @change="toggleSelect(n.note_id)"
            />
            <div class="card-title" v-html="highlight(n.title || '（无标题）')"></div>
            <div class="card-preview" v-html="highlight(n.content_preview || '（无正文）')"></div>

            <div class="card-tags" v-if="n.tags && n.tags.length">
              <el-tag v-for="t in n.tags" :key="t.tag_id" size="small" round effect="plain" :color="t.color">
                {{ t.name }}
              </el-tag>
            </div>

            <!-- 溯源：来自哪本书哪一页（借鉴微信读书划线笔记） -->
            <div class="card-source" v-if="n.source_book_title">
              《{{ n.source_book_title }}》<span v-if="n.source_page"> · P{{ n.source_page }}</span>
            </div>

            <div class="card-foot">
              <template v-if="view !== 'trash'">
                <span class="status" :class="n.is_indexed ? 'on' : 'off'">
                  <i class="dot" />{{ n.is_indexed ? '已回流' : '未回流' }}
                </span>
                <span>{{ n.word_count }} 字</span>
                <span v-if="n.link_count">🔗 {{ n.link_count }}</span>
                <span class="time">{{ fmtDate(n.updated_at) }}</span>
              </template>
              <template v-else>
                <span class="time trash-time">🗑 删除于 {{ fmtDate(n.deleted_at) }}</span>
                <span class="trash-actions" @click.stop>
                  <el-button size="small" type="primary" plain @click="restoreNoteConfirm(n.note_id)">恢复</el-button>
                  <el-button size="small" type="danger" plain @click="purgeCardNote(n.note_id)">永久删除</el-button>
                </span>
              </template>
            </div>
          </div>
        </div>

        <el-empty
          v-if="!loading && !notes.length"
          :description="view === 'trash' ? '回收站是空的，删除的笔记会出现在这里' : '还没有笔记，点右上角「新建笔记」写一条'"
        />

        <el-pagination
          v-if="total > 0"
          class="pager"
          layout="total, prev, pager, next"
          :total="total"
          :current-page="page"
          :page-size="pageSize"
          @current-change="gotoPage"
        />
      </main>
    </div>

    <!-- ==================== 右侧抽屉：详情 / 编辑 / 双链（借鉴 Obsidian 反链面板） ==================== -->
    <el-drawer
      v-model="drawerVisible"
      :title="isNew ? '新建笔记' : '笔记详情'"
      size="46%"
      :close-on-click-modal="false"
      @closed="resetForm"
    >
      <div v-loading="detailLoading">
        <el-form label-position="top">
          <el-form-item label="标题">
            <el-input v-model="form.title" placeholder="给笔记起个标题（可留空）" />
          </el-form-item>

          <el-form-item>
            <template #label>
              <div class="label-row">
                <span>正文（Markdown）</span>
                <el-radio-group v-model="mdTab" size="small">
                  <el-radio-button value="edit">编辑</el-radio-button>
                  <el-radio-button value="preview">预览</el-radio-button>
                </el-radio-group>
              </div>
            </template>
            <el-input
              v-show="mdTab === 'edit'"
              v-model="form.content"
              type="textarea"
              :rows="14"
              placeholder="在此记录你的思考…"
            />
            <div v-show="mdTab === 'preview'" class="md-preview" v-html="renderMd(form.content)" />
          </el-form-item>

          <el-form-item label="标签">
            <el-select
              v-model="form.tagNames"
              multiple
              filterable
              allow-create
              default-first-option
              placeholder="选择或输入新标签后回车"
              style="width: 100%"
            >
              <el-option v-for="t in tags" :key="t.tag_id" :label="t.name" :value="t.name" />
            </el-select>
          </el-form-item>

          <!-- 回流开关（§9.8）：开启=切片向量入库，关闭=删向量；失败自动弹回 -->
          <el-form-item v-if="!isNew" label="知识回流">
            <el-switch
              v-model="dummyIndexed"
              active-text="加入检索（notes_all）"
              inactive-text="不参与检索"
              :loading="indexToggling"
              @change="onToggleIndex"
            />
            <span v-if="indexStatus === 'INDEX_FAILED'" class="index-failed-hint">
              上次回流失败，可重试开启
            </span>
          </el-form-item>

          <!-- 双向链接 -->
          <el-form-item v-if="!isNew" label="双向链接">
            <div class="link-block">
              <div class="link-title">出链（本笔记指向）</div>
              <div v-if="!links.outgoing.length" class="link-empty">暂无</div>
              <div v-for="l in links.outgoing" :key="l.target_note_id" class="link-item" @click="jump(l.target_note_id)">
                {{ l.target_title || l.target_note_id }}
              </div>
            </div>
            <div class="link-block">
              <div class="link-title">入链（指向本笔记）</div>
              <div v-if="!links.incoming.length" class="link-empty">暂无</div>
              <div v-for="l in links.incoming" :key="l.source_note_id" class="link-item" @click="jump(l.source_note_id)">
                {{ l.source_title || l.source_note_id }}
              </div>
            </div>
            <el-select
              v-model="linkTarget"
              filterable
              remote
              clearable
              placeholder="搜索笔记标题以添加链接"
              style="width: 100%; margin-top: 8px"
              :remote-method="searchNotes"
              :loading="linkSearching"
            >
              <el-option v-for="o in linkOptions" :key="o.note_id" :label="o.title || o.note_id" :value="o.note_id" />
            </el-select>
            <el-button size="small" style="margin-top: 6px" :disabled="!linkTarget" @click="addLink">添加链接</el-button>
          </el-form-item>
        </el-form>
      </div>

      <template #footer>
        <div class="drawer-foot">
          <div>
            <el-button v-if="!isNew" type="danger" plain @click="removeNote">删除</el-button>
            <el-button v-if="!isNew" type="danger" @click="purgeNoteConfirm">永久删除</el-button>
          </div>
          <div>
            <el-button @click="drawerVisible = false">取消</el-button>
            <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          </div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

const route = useRoute()
import {
  createNote, createNoteLink, createTag, deleteNote, exportNotes,
  getNote, getNoteLinks, listBooks, listNotes, listTags, purgeNote,
  toggleNoteIndex, updateNote
} from '../api'

const SOURCE_TYPES = [
  { value: 'MANUAL', label: '手动' },
  { value: 'AI_SUMMARY', label: 'AI 摘要' },
  { value: 'AI_QA', label: 'AI 问答' },
  { value: 'AI_MINDMAP', label: 'AI 导图' },
  { value: 'BOOK_EXCERPT', label: '原文摘录' }
]

// ==================== 列表状态 ====================
const notes = ref([])
const tags = ref([])
const books = ref([])
const total = ref(0)
const trashTotal = ref(0)
const page = ref(1)
const pageSize = ref(12)
const loading = ref(false)
const keyword = ref('')
const view = ref('all')
const filters = reactive({ bookId: '', sourceType: '', tag: '', indexed: '' })
const selectedIds = ref([])

// ==================== 抽屉状态 ====================
const drawerVisible = ref(false)
const detailLoading = ref(false)
const saving = ref(false)
const isNew = ref(false)
const mdTab = ref('edit')
const currentId = ref('')
const dummyIndexed = ref(false)
const indexToggling = ref(false)
const indexStatus = ref('NOT_INDEXED')
const form = reactive({ title: '', content: '', tagNames: [], editorVersion: 1 })
const links = reactive({ outgoing: [], incoming: [] })
const linkTarget = ref('')
const linkOptions = ref([])
const linkSearching = ref(false)
const newTagName = ref('')

// ==================== 数据加载 ====================

const buildParams = () => ({
  book_id: filters.bookId || undefined,
  source_type: filters.sourceType || undefined,
  tag: filters.tag || undefined,
  keyword: keyword.value || undefined,
  indexed: view.value === 'all' ? (filters.indexed || undefined)
    : (view.value === 'indexed' ? 'true' : 'false'),
  soft_delete: view.value === 'trash',   // 回收站视图只查已软删笔记
  page: page.value,
  page_size: pageSize.value
})

async function reload() {
  loading.value = true
  try {
    const data = await listNotes(buildParams())
    notes.value = data.items || []
    total.value = data.total || 0
    if (view.value === 'trash') {
      trashTotal.value = data.total || 0
      selectedIds.value = []             // 回收站不可选中导出
    }
  } catch {
    notes.value = []
  } finally {
    loading.value = false
  }
}

async function loadTrashCount() {
  try {
    const data = await listNotes({ soft_delete: true, page: 1, page_size: 1 })
    trashTotal.value = data.total || 0
  } catch { /* 忽略 */ }
}

async function loadTags() {
  try {
    const data = await listTags()
    tags.value = data.items || []
  } catch { /* 拦截器已提示 */ }
}

async function loadBooks() {
  try {
    const data = await listBooks({ page: 1, page_size: 100 })
    books.value = data.items || []
  } catch { /* 忽略：书籍下拉为空不影响主流程 */ }
}

const gotoPage = (p) => { page.value = p; reload() }
const setView = (v) => { view.value = v; filters.indexed = ''; page.value = 1; reload() }
const pickTag = (name) => {
  filters.tag = filters.tag === name ? '' : name
  page.value = 1
  reload()
}

// 搜索防抖 300ms，避免每敲一个字打一次后端
let timer = null
function onKeywordInput() {
  clearTimeout(timer)
  timer = setTimeout(() => { page.value = 1; reload() }, 300)
}

// ==================== 选中与导出（§9.11：markdown / pdf，选中 / 整本书） ====================

const toggleSelect = (id) => {
  const i = selectedIds.value.indexOf(id)
  if (i >= 0) selectedIds.value.splice(i, 1)
  else selectedIds.value.push(id)
}

const exportDialog = ref(false)
const exporting = ref(false)
const exportForm = reactive({ scope: 'selected', format: 'markdown', includeFrontMatter: true })
const bookTitle = computed(() =>
  books.value.find((b) => b.book_id === filters.bookId)?.title || '')

function openExport() {
  // 默认范围跟随当前可用项：选中了笔记优先 selected，否则整本书
  exportForm.scope = selectedIds.value.length ? 'selected' : 'book'
  exportDialog.value = true
}

async function onExport() {
  exporting.value = true
  try {
    const payload = {
      scope: exportForm.scope,
      format: exportForm.format,
      include_front_matter: exportForm.includeFrontMatter
    }
    if (exportForm.scope === 'selected') payload.note_ids = selectedIds.value
    else payload.book_id = filters.bookId
    const data = await exportNotes(payload)
    // download_url 形如 /api/v1/files/exports/xxx.zip，走 Vite 代理
    window.location.href = data.download_url
    ElMessage.success(`已导出 ${data.note_count} 条笔记`)
    exportDialog.value = false
    if (exportForm.scope === 'selected') selectedIds.value = []
  } catch { /* 拦截器已提示 */ } finally {
    exporting.value = false
  }
}

// ==================== 抽屉：新建 / 详情 / 保存 ====================

function resetForm() {
  isNew.value = false
  currentId.value = ''
  mdTab.value = 'edit'
  Object.assign(form, { title: '', content: '', tagNames: [], editorVersion: 1 })
  links.outgoing = []
  links.incoming = []
  linkTarget.value = ''
  linkOptions.value = []
}

function openCreate() {
  resetForm()
  isNew.value = true
  drawerVisible.value = true
}

async function openDetail(id) {
  resetForm()
  isNew.value = false
  currentId.value = id
  drawerVisible.value = true
  detailLoading.value = true
  try {
    const d = await getNote(id)
    form.title = d.title || ''
    form.content = d.content || ''
    form.editorVersion = d.editor_version || 1
    form.tagNames = (d.tags || []).map((t) => t.name)
    dummyIndexed.value = !!d.is_indexed
    indexStatus.value = d.index_status || 'NOT_INDEXED'
    const l = await getNoteLinks(id)
    links.outgoing = l.outgoing || []
    links.incoming = l.incoming || []
  } catch {
    drawerVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

const jump = (id) => { if (id && id !== currentId.value) openDetail(id) }

// ==================== 知识回流开关（§9.8） ====================

/** 切换回流：成功后回显切片数；失败自动弹回开关并提示（后端 INDEX_FAILED 语义） */
async function onToggleIndex(val) {
  if (!currentId.value) { dummyIndexed.value = !val; return }
  indexToggling.value = true
  try {
    const r = await toggleNoteIndex(currentId.value, val)
    dummyIndexed.value = !!r.is_indexed
    indexStatus.value = r.index_status || ''
    ElMessage.success(val
      ? `已加入知识回流（${r.indexed_chunk_count} 片切片入库）`
      : '已移出知识回流')
    reload()
  } catch (e) {
    dummyIndexed.value = !val // 失败弹回（需求 §3.5）
    ElMessage.error(e?.response?.data?.message || '回流操作失败，请重试')
  } finally {
    indexToggling.value = false
  }
}

async function save() {
  if (!form.content.trim()) {
    ElMessage.warning('正文不能为空')
    return
  }
  saving.value = true
  try {
    if (isNew.value) {
      await createNote({
        title: form.title,
        content: form.content,
        source_type: 'MANUAL',
        tag_names: form.tagNames
      })
      ElMessage.success('笔记已创建')
    } else {
      await updateNote(currentId.value, {
        title: form.title,
        content: form.content,
        editor_version: form.editorVersion,
        tag_names: form.tagNames
      })
      ElMessage.success('笔记已保存')
    }
    drawerVisible.value = false
    await Promise.all([reload(), loadTags()])
  } catch (e) {
    // 50004 乐观锁冲突：拦截器已提示，这里补"重新加载最新版本"入口
    if (e?.response?.data?.code === 50004 && !isNew.value) {
      try {
        await ElMessageBox.confirm('笔记已被他人更新，是否重新加载最新版本？你未保存的修改会被覆盖', '版本冲突', {
          type: 'warning',
          confirmButtonText: '重新加载',
          cancelButtonText: '保留我的修改'
        })
        await openDetail(currentId.value)
      } catch { /* 用户选择保留本地修改 */ }
    }
  } finally {
    saving.value = false
  }
}

// ==================== 删除 ====================

async function removeNote() {
  try {
    await ElMessageBox.confirm('删除后可到回收站恢复，确定删除？', '提示', { type: 'warning' })
  } catch { return }
  try {
    await deleteNote(currentId.value)
    ElMessage.success('已移入回收站')
    drawerVisible.value = false
    reload()
  } catch { /* 已提示 */ }
}

async function purgeNoteConfirm() {
  try {
    await ElMessageBox.confirm('永久删除不可恢复，且会置灰关联笔记的链接，确定？', '危险操作', {
      type: 'error',
      confirmButtonText: '永久删除'
    })
  } catch { return }
  try {
    await purgeNote(currentId.value)
    ElMessage.success('已永久删除')
    drawerVisible.value = false
    reload()
    loadTrashCount()
  } catch { /* 已提示 */ }
}

// ==================== 回收站（soft_delete=true 视图） ====================

async function restoreNoteConfirm(noteId) {
  try {
    await ElMessageBox.confirm('恢复后笔记将回到正常列表（曾回流的会自动重新入库）', '恢复笔记', { type: 'info' })
  } catch { return }
  try {
    await restoreNote(noteId)
    ElMessage.success('已恢复')
    reload()
    loadTrashCount()
  } catch { /* 已提示 */ }
}

async function purgeCardNote(noteId) {
  try {
    await ElMessageBox.confirm('永久删除不可恢复，且会置灰关联笔记的链接，确定？', '危险操作', {
      type: 'error',
      confirmButtonText: '永久删除'
    })
  } catch { return }
  try {
    await purgeNote(noteId)
    ElMessage.success('已永久删除')
    reload()
    loadTrashCount()
  } catch { /* 已提示 */ }
}

// ==================== 双链 ====================

async function searchNotes(q) {
  if (!q) { linkOptions.value = []; return }
  linkSearching.value = true
  try {
    const data = await listNotes({ keyword: q, page: 1, page_size: 20 })
    linkOptions.value = (data.items || []).filter((n) => n.note_id !== currentId.value)
  } catch { linkOptions.value = [] } finally { linkSearching.value = false }
}

async function addLink() {
  try {
    await createNoteLink(currentId.value, { target_note_id: linkTarget.value })
    ElMessage.success('链接已创建')
    linkTarget.value = ''
    const l = await getNoteLinks(currentId.value)
    links.outgoing = l.outgoing || []
    links.incoming = l.incoming || []
    reload()
  } catch { /* 已提示 */ }
}

// ==================== 标签 ====================

async function addTag() {
  const name = (newTagName.value || '').trim()
  if (!name) return
  try {
    await createTag({ name })
    newTagName.value = ''
    await loadTags()
  } catch { /* 已提示（含标签已存在） */ }
}

// ==================== 工具 ====================

const fmtDate = (iso) => (iso ? iso.slice(0, 16).replace('T', ' ') : '')

// ==================== 搜索高亮 ====================
// 搜索结果命中词标黄：先转义 HTML 防注入，再对关键词做大小写不敏感替换为 <mark>。
// keyword 为空时仅转义输出（与原先纯文本显示等价，但不引入注入风险）。
const escHtml = (s) =>
  String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;')

const highlight = (text) => {
  const safe = escHtml(text)
  const kw = keyword.value?.trim()
  if (!kw) return safe
  try {
    const escKw = escHtml(kw).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    return safe.replace(new RegExp(escKw, 'gi'), (m) => `<mark class="hl">${m}</mark>`)
  } catch {
    return safe // 正则异常（理论上不会发生）时降级为无高亮
  }
}

/** 极简 Markdown → HTML（不引第三方依赖，够用即可） */
function renderMd(src) {
  if (!src) return '<span class="md-empty">（无内容）</span>'
  let html = src.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  html = html
    .replace(/^### (.*)$/gim, '<h3>$1</h3>')
    .replace(/^## (.*)$/gim, '<h2>$1</h2>')
    .replace(/^# (.*)$/gim, '<h1>$1</h1>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/^- (.*)$/gim, '<li>$1</li>')
    .replace(/\n/g, '<br/>')
  return html
}

onMounted(() => {
  // 支持从书库跳转：/notes?bookId=xx 自动按书筛选
  const qb = route.query.bookId || route.query.book
  if (qb) filters.bookId = String(qb)
  reload()
  loadTags()
  loadBooks()
  loadTrashCount()
})
</script>

<style scoped>
.notes-page { display: flex; flex-direction: column; height: 100%; }

/* 顶部工具条：白底卡片 */
.toolbar {
  display: flex; align-items: center; gap: 10px;
  background: #fff; border: 1px solid #e8eee9; border-radius: 12px;
  padding: 12px 16px;
}
.spacer { flex: 1; }

.body { display: flex; flex: 1; min-height: 0; margin-top: 14px; }

/* 左栏：白底卡片 */
.sidebar {
  width: 200px; flex-shrink: 0;
  background: #fff; border: 1px solid #e8eee9; border-radius: 12px;
  padding: 10px 10px 14px; overflow-y: auto;
}
.side-title { font-size: 12px; color: #94a3b8; margin: 10px 6px 6px; }
.side-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 7px 10px; border-radius: 8px; cursor: pointer; font-size: 14px; color: #303133;
}
.side-item:hover { background: #f5f7fa; }
.side-item.active { background: #e8f5ec; color: #14532d; font-weight: 600; }
.count { font-size: 12px; color: #909399; }
.dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 7px; background: #c0c4cc; vertical-align: middle; }
.dot.on { background: #67c23a; box-shadow: 0 0 0 3px rgba(103, 194, 58, 0.18); }
.dot.off { background: #c0c4cc; }

/* 卡片流 */
.cards { flex: 1; min-width: 0; padding-left: 14px; display: flex; flex-direction: column; }
.card-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px; align-content: start; min-height: 120px;
}
.note-card {
  position: relative; border: 1px solid #ebeef5; border-radius: 10px; padding: 14px 14px 10px;
  cursor: pointer; background: #fff; transition: box-shadow .18s, border-color .18s;
}
.note-card:hover { box-shadow: 0 4px 14px rgba(20, 83, 45, 0.10); border-color: #b7d8c4; }
.note-card.picked { border-color: #14532d; background: #f6fbf8; }
.note-card.trash { background: #fafafa; opacity: .92; cursor: default; }
.note-card.trash:hover { box-shadow: none; border-color: #ebeef5; }
.trash-time { color: #909399; }
.trash-actions { margin-left: auto; display: flex; gap: 6px; }
.pick { position: absolute; top: 10px; right: 10px; }
.card-title { font-weight: 600; font-size: 15px; color: #1f2937; margin: 0 26px 7px 0; line-height: 1.4; }
.card-title :deep(.hl), .card-preview :deep(.hl) { background: #fde68a; color: #1f2937; border-radius: 2px; padding: 0 1px; }
.card-preview {
  font-size: 13px; color: #606266; line-height: 1.6; margin-bottom: 9px;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
}
.card-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-bottom: 8px; }
.card-source { font-size: 12px; color: #14532d; background: #f2f8f4; padding: 3px 8px; border-radius: 4px; display: inline-block; margin-bottom: 8px; }
.card-foot { display: flex; align-items: center; gap: 10px; font-size: 12px; color: #909399; border-top: 1px dashed #ebeef5; padding-top: 7px; }
.card-foot .time { margin-left: auto; }
.card-foot .trash-time { margin-left: 0; }
.status { display: flex; align-items: center; }
.status.on { color: #67c23a; }
.status.off { color: #c0c4cc; }
.pager { margin-top: 16px; justify-content: flex-end; }

/* 抽屉 */
.label-row { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.md-preview {
  border: 1px solid #ebeef5; border-radius: 6px; padding: 12px; min-height: 240px;
  background: #fafafa; line-height: 1.7; font-size: 14px;
}
.md-preview :deep(h1) { font-size: 20px; margin: 8px 0; }
.md-preview :deep(h2) { font-size: 17px; margin: 8px 0; }
.md-preview :deep(h3) { font-size: 15px; margin: 6px 0; }
.md-preview :deep(code) { background: #f0f0f0; padding: 1px 5px; border-radius: 3px; }
.md-preview :deep(li) { margin-left: 18px; list-style: disc; }
.md-empty { color: #c0c4cc; }
.link-block { margin-bottom: 10px; }
.link-title { font-size: 12px; color: #909399; margin-bottom: 4px; }
.link-item {
  font-size: 13px; color: #14532d; padding: 4px 8px; border-radius: 4px;
  cursor: pointer; background: #f6fbf8; margin-bottom: 4px; display: inline-block; margin-right: 6px;
}
.link-item:hover { background: #e8f5ec; }
.link-empty { font-size: 12px; color: #c0c4cc; }
.drawer-foot { display: flex; justify-content: space-between; width: 100%; }
</style>
