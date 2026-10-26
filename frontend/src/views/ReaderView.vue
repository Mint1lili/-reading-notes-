<template>
  <div class="reader">
    <!-- ===== 左：书单 ===== -->
    <aside class="book-side">
      <div class="side-head">
        <span class="side-title">书库</span>
        <el-button link size="small" :loading="bookLoading" @click="loadBooks"><el-icon><Refresh /></el-icon></el-button>
      </div>
      <div
        v-for="b in books"
        :key="b.book_id"
        class="book-item"
        :class="{ active: currentBook?.book_id === b.book_id }"
        @click="pickBook(b)"
      >
        <div class="book-cover" :style="{ background: coverColor(b.title) }">{{ (b.title || '书')[0] }}</div>
        <div class="book-meta">
          <div class="book-title">{{ b.title }}</div>
          <div class="book-status">
            <span class="st-dot" :class="b.status" />{{ statusText(b.status) }}
          </div>
        </div>
      </div>
      <el-empty v-if="!bookLoading && !books.length" :image-size="48" description="暂无书籍，去书库上传" style="padding: 20px 0" />
      <div class="demo-tag">AI 输出由豆包大模型实时生成 · 引用溯源自动关联原书</div>
    </aside>

    <!-- ===== 中：对话工作台 ===== -->
    <main class="chat-side">
      <div class="chat-head">
        <el-radio-group v-model="mode" size="default">
          <el-radio-button value="summary">全书摘要</el-radio-button>
          <el-radio-button value="qa">书本问答</el-radio-button>
          <el-radio-button value="mindmap">思维导图</el-radio-button>
        </el-radio-group>
        <div class="chat-book" v-if="currentBook">正在读《{{ currentBook.title }}》</div>
        <div class="chat-book" v-else>请先在左侧选择一本书</div>
      </div>

      <div class="chat-body" ref="bodyRef">
        <div
          v-for="(m, i) in messages"
          :key="i"
          class="msg-row"
          :class="m.role"
        >
          <div v-if="m.role === 'ai'" class="ai-avatar">AI</div>
          <div class="msg-content">
            <div v-if="m.role === 'ai' && m.streaming" class="bubble ai">{{ streamingText }}<span class="cursor">▍</span></div>
            <div v-else-if="m.role === 'ai' && m.markdown" class="bubble ai md"><pre class="mm">{{ m.content }}</pre></div>
            <div v-else-if="m.role === 'ai'" class="bubble ai" v-html="m.content"></div>
            <div v-else class="bubble user">{{ m.content }}</div>

            <!-- 引用胶囊 -->
            <div v-if="m.role === 'ai' && m.citations && m.citations.length" class="cite-caps">
              <el-tag
                v-for="(c, ci) in m.citations" :key="ci" size="small" effect="plain"
                class="cite-tag" @click="openCitation(m, ci)"
              >
                《{{ c.book }}》P{{ c.page }}
              </el-tag>
            </div>
            <!-- 操作条 -->
            <div v-if="m.role === 'ai' && !m.streaming" class="msg-actions">
              <el-button link size="small" type="primary" @click="saveAsNote(m)">存为笔记</el-button>
              <el-button link size="small" @click="regenerate(m)">重新生成</el-button>
            </div>
          </div>
        </div>

        <div v-if="busy" class="msg-row ai">
          <div class="ai-avatar">AI</div>
          <div class="msg-content">
            <div class="bubble ai">思考中…</div>
          </div>
        </div>

        <el-empty v-if="!messages.length" description="选择模式，向这本书提问" :image-size="64" style="margin-top: 60px" />
      </div>

      <div class="chat-input">
        <div class="quick-chips" v-if="!busy">
          <el-tag
            v-for="q in quickQuestions" :key="q" size="small" round effect="plain"
            class="chip" @click="ask(q)"
          >{{ q }}</el-tag>
        </div>
        <div class="input-row">
          <el-input
            v-model="draft"
            type="textarea"
            :rows="2"
            resize="none"
            placeholder="输入你的问题，或点击上方快捷问题…"
            :disabled="!currentBook || busy"
            @keydown.enter.exact.prevent="send"
          />
          <el-button type="primary" :disabled="!currentBook || busy || !draft.trim()" @click="send">
            <el-icon style="margin-right:4px"><Promotion /></el-icon>发送
          </el-button>
        </div>
      </div>
    </main>

    <!-- ===== 右：引用面板 ===== -->
    <aside class="cite-side" :class="{ folded: !citeOpen }">
      <div class="side-head">
        <span class="side-title">引用溯源</span>
        <el-button link size="small" @click="citeOpen = !citeOpen">
          <el-icon><component :is="citeOpen ? 'Fold' : 'Expand'" /></el-icon>
        </el-button>
      </div>
      <div v-if="citeOpen" class="cite-list">
        <div v-if="!activeCitations.length" class="cite-empty">回答中的引用会显示在这里<br />（点击对话里的引用胶囊查看原文）</div>
        <div v-for="(c, i) in activeCitations" :key="i" class="cite-card">
          <div class="cite-src">《{{ c.book }}》 · P{{ c.page }}</div>
          <div class="cite-text">{{ c.text }}</div>
        </div>
      </div>
    </aside>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Promotion, Fold, Expand } from '@element-plus/icons-vue'
import { listBooks, createNote, qaStream, submitSummary, getSummary, submitMindmap, getMindmap, getAiTask } from '../api'

const route = useRoute()

const books = ref([])
const bookLoading = ref(false)
const currentBook = ref(null)
const mode = ref('summary')
const draft = ref('')
const busy = ref(false)
const messages = reactive([])
const streamingText = ref('')
const bodyRef = ref()
const citeOpen = ref(true)
const activeCitations = ref([])
/** 多轮会话：book_id → session_id（书本内问答透传，保持上下文） */
const sessionIdMap = reactive({})

const coverColors = ['#14532d', '#2f855a', '#5b8c6a', '#86b59b', '#3e6b4f', '#1f7a54']
function coverColor(title) {
  let h = 0
  for (const ch of (title || '书')) h = (h * 31 + ch.charCodeAt(0)) % 997
  return coverColors[h % coverColors.length]
}
const statusText = (s) => ({ READY: '可阅读', BUILDING: '构建中', FAILED: '构建失败' }[s] || s || '未知')

const quickQuestions = computed(() => ({
  summary: ['这本书讲了什么？', '核心观点有哪些？', '适合谁读？'],
  qa: ['第三章的核心思想是什么？', '作者如何论证主要观点？', '有哪些经典案例？'],
  mindmap: ['生成这本书的思维导图', '按章节梳理知识结构']
}[mode.value] || []))

// ===== 书加载 =====
async function loadBooks() {
  bookLoading.value = true
  try {
    const data = await listBooks({ page: 1, page_size: 50 })
    books.value = (data.items || []).filter((b) => b.status === 'READY')
    if (!books.value.length) books.value = data.items || []
    const qb = route.query.book
    if (qb) {
      const hit = books.value.find((b) => String(b.book_id) === String(qb))
      if (hit) pickBook(hit)
    } else if (!currentBook.value && books.value.length) {
      pickBook(books.value[0])
    }
  } catch { books.value = [] } finally { bookLoading.value = false }
}

function pickBook(b) { currentBook.value = b; messages.splice(0); }

// ===== 异步任务（F3.1 全书摘要 / F3.5 思维导图）：提交 202 → 轮询任务 → 拉取内容 =====
function sleep(ms) { return new Promise((r) => setTimeout(r, ms)) }

async function generateTaskAnswer(question, modeNow) {
  const isSummary = modeNow === 'summary'
  const bookId = currentBook.value.book_id
  try {
    const resp = isSummary
      ? await submitSummary({ book_id: bookId })
      : await submitMindmap({ book_id: bookId })
    const taskId = resp.task_id
    if (!taskId) throw new Error('任务创建失败')

    const m = { role: 'ai', content: '', citations: [], streaming: true, markdown: true, tasking: true }
    messages.push(m)
    streamingText.value = isSummary ? '正在生成全书摘要…' : '正在生成思维导图…'
    scrollToBottom()

    let status = 'RUNNING'
    const deadline = Date.now() + 5 * 60 * 1000
    while (Date.now() < deadline) {
      await sleep(1500)
      const t = await getAiTask(taskId)
      status = t.status
      const hint = t.progress_hint
      if (hint) {
        streamingText.value = isSummary ? `全书摘要生成中：${hint}` : `思维导图生成中：${hint}`
        scrollToBottom()
      }
      if (status === 'COMPLETED' || status === 'FAILED') break
    }

    if (status !== 'COMPLETED') {
      messages.splice(messages.indexOf(m), 1)
      ElMessage.error(status === 'FAILED' ? '生成失败，请稍后重试' : '生成超时，请稍后重试')
      return
    }
    const data = isSummary ? await getSummary(bookId) : await getMindmap(bookId)
    const content = (data.content || '').trim()
    m.streaming = false
    m.tasking = false
    m.content = content || '（未生成内容）'
    streamingText.value = ''
    scrollToBottom()
  } catch (e) {
    ElMessage.error(e?.message || (isSummary ? '摘要生成失败' : '导图生成失败'))
  }
}

// ===== 发送与流式 =====
async function ask(q) { draft.value = q; send() }

function scrollToBottom() {
  nextTick(() => {
    if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
  })
}

async function send() {
  const q = draft.value.trim()
  if (!q || !currentBook.value || busy.value) return
  draft.value = ''
  messages.push({ role: 'user', content: q })
  scrollToBottom()
  await generateAnswer(q)
}

// 生成一条 AI 回答：
// - 书本问答（qa）：SSE 流式（POST /api/v1/ai/qa/stream，meta/delta/done/error）
// - 全书摘要（summary）/ 思维导图（mindmap）：异步任务（submit → 轮询 tasks → 拉取 Markdown）
async function generateAnswer(question) {
  const modeNow = mode.value
  if (modeNow === 'summary' || modeNow === 'mindmap') {
    await generateTaskAnswer(question, modeNow)
    return
  }

  // ===== qa：SSE 流式 =====
  busy.value = true
  const bookId = currentBook.value.book_id
  const m = { role: 'ai', content: '', citations: [], streaming: true, sessionId: undefined }
  messages.push(m)
  let full = ''
  streamingText.value = ''
  scrollToBottom()

  const stream = qaStream(
    {
      book_id: bookId,
      question,
      session_id: sessionIdMap[bookId] || undefined,
      // 当前向量化走豆包 multimodal 模型（文本检索区分度弱，top1≈0.3），
      // 覆盖后端默认拒答阈值 0.5，避免检索命中却被拒答
      similarity_threshold: 0.25
    },
    {
      onMeta: (meta) => {
        if (meta?.session_id) {
          m.sessionId = meta.session_id
          sessionIdMap[bookId] = meta.session_id
        }
        if (Array.isArray(meta?.retrieved_chunks)) {
          m.citations = meta.retrieved_chunks.map((c) => ({
            book: currentBook.value?.title || '本书',
            page: c.page || 0,
            text: `检索命中 · 相似度 ${Math.round((c.score || 0) * 100)}%`,
            similarity: c.score,
            chunk_id: c.chunk_id
          }))
        }
      },
      onDelta: (d) => {
        if (d && typeof d.content === 'string') {
          full += d.content
          streamingText.value = full
          scrollToBottom()
        }
      },
      onDone: (d) => {
        if (d && d.refused) {
          finishAnswer(m, d.message || '本书中未提及相关内容', true)
        } else if (d) {
          finishAnswer(m, full, false)
        }
      },
      onError: (e) => {
        failAnswer(m, e)
      }
    }
  )
  try {
    const result = await stream.done
    // done 事件可能已处理；此处兜底：异常/取消路径
    if (result.error && !result.done) failAnswer(m, result.error)
  } catch (e) {
    failAnswer(m, e)
  } finally {
    busy.value = false
  }
}

function finishAnswer(m, content, refused) {
  m.streaming = false
  m.content = content || '（未返回内容）'
  streamingText.value = ''
  if (refused) {
    ElMessage.warning(content || '本书中未提及相关内容')
  }
  activeCitations.value = m.citations
  citeOpen.value = m.citations.length > 0
  scrollToBottom()
}

function failAnswer(m, e) {
  const idx = messages.indexOf(m)
  if (idx >= 0) messages.splice(idx, 1)
  ElMessage.error(e?.message || 'AI 问答暂不可用，请稍后再试')
}

// 重新生成：删除旧回答，用原问题重新触发一次生成
function regenerate(m) {
  const i = messages.indexOf(m)
  if (i < 0 || busy.value) return
  const userMsg = i > 0 ? messages[i - 1] : null
  messages.splice(i, 1)
  activeCitations.value = []
  if (userMsg && userMsg.role === 'user') {
    generateAnswer(userMsg.content)
  }
}

function openCitation(m, ci) {
  activeCitations.value = m.citations
  citeOpen.value = true
}

// ===== 存为笔记 =====
async function saveAsNote(m) {
  if (!currentBook.value) return
  try {
    const title = `AI ${mode.value === 'summary' ? '摘要' : mode.value === 'mindmap' ? '导图' : '问答'} · 《${currentBook.value.title}》`
    const content = m.content.replace(/<[^>]+>/g, '')
    const sourceType = mode.value === 'summary' ? 'AI_SUMMARY' : mode.value === 'mindmap' ? 'AI_MINDMAP' : 'AI_QA'
    // 统一走通用创建（后端 /ai/qa 不落 AI 产物表，from-ai 会因缺产物记录 400）
    await createNote({ title, content, source_type: sourceType })
    ElMessage.success('已保存到我的笔记')
  } catch { /* 已提示 */ }
}

onMounted(loadBooks)
</script>

<style scoped>
.reader {
  display: flex;
  gap: 14px;
  height: 100%;
  min-height: 0;
}

/* ===== 左栏 ===== */
.book-side {
  width: 216px; flex-shrink: 0;
  background: #fff;
  border: 1px solid #e8eee9;
  border-radius: 12px;
  padding: 10px;
  display: flex; flex-direction: column;
  overflow-y: auto;
}
.side-head { display: flex; align-items: center; justify-content: space-between; padding: 2px 4px 8px; }
.side-title { font-size: 13px; font-weight: 700; color: #14532d; }
.book-item {
  display: flex; gap: 9px; align-items: center;
  padding: 8px; border-radius: 10px; cursor: pointer;
  transition: background .15s;
}
.book-item:hover { background: #f2f7f4; }
.book-item.active { background: #e8f5ec; }
.book-cover {
  width: 40px; height: 52px; border-radius: 6px;
  color: #fff; font-size: 17px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 2px 6px rgba(16,42,28,.18);
}
.book-meta { min-width: 0; }
.book-title { font-size: 13px; font-weight: 600; color: #1f2937; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.book-status { font-size: 11px; color: #94a3b8; margin-top: 3px; display: flex; align-items: center; gap: 4px; }
.st-dot { width: 6px; height: 6px; border-radius: 50%; background: #cbd5e0; }
.st-dot.READY { background: #38a169; }
.st-dot.BUILDING { background: #d97706; }
.st-dot.FAILED { background: #e53e3e; }
.demo-tag { margin-top: auto; padding-top: 10px; font-size: 11px; color: #a0aec0; line-height: 1.6; }

/* ===== 中栏 ===== */
.chat-side {
  flex: 1; min-width: 0;
  background: #fff;
  border: 1px solid #e8eee9;
  border-radius: 12px;
  display: flex; flex-direction: column;
  overflow: hidden;
}
.chat-head {
  padding: 12px 16px;
  border-bottom: 1px solid #eef2f0;
  display: flex; align-items: center; gap: 14px;
}
.chat-head :deep(.el-radio-button__inner) { font-size: 13px; }
.chat-book { font-size: 13px; color: #64748b; }

.chat-body { flex: 1; overflow-y: auto; padding: 18px 20px; }
.msg-row { display: flex; gap: 10px; margin-bottom: 16px; }
.msg-row.user { justify-content: flex-end; }
.ai-avatar {
  width: 28px; height: 28px; border-radius: 8px; flex-shrink: 0;
  background: linear-gradient(150deg, #14532d, #2f855a); color: #fff;
  font-size: 11px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
}
.msg-content { max-width: 78%; }
.bubble {
  padding: 11px 14px; border-radius: 12px;
  font-size: 14px; line-height: 1.7; word-break: break-word;
}
.bubble.ai {
  background: #f6f8f7; color: #1f2937;
  border: 1px solid #eef2f0; border-top-left-radius: 4px;
}
.bubble.user {
  background: #14532d; color: #fff;
  border-top-right-radius: 4px;
}
.bubble.ai :deep(p) { margin: 0 0 8px; }
.bubble.ai :deep(p:last-child) { margin-bottom: 0; }
.bubble.ai :deep(b) { color: #14532d; }
.cursor { color: #2f855a; animation: blink 1s step-start infinite; }
@keyframes blink { 50% { opacity: 0; } }
.mm { font-family: 'SF Mono', Consolas, monospace; font-size: 13px; line-height: 1.8; color: #334155; margin: 0; white-space: pre-wrap; word-break: break-word; }
.cite-caps { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 6px; }
.cite-tag { cursor: pointer; border-radius: 999px; }
.msg-actions { margin-top: 6px; display: flex; gap: 4px; opacity: .8; }

.chat-input { padding: 12px 16px 14px; border-top: 1px solid #eef2f0; background: #fbfcfb; }
.quick-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
.chip { cursor: pointer; }
.input-row { display: flex; gap: 10px; align-items: flex-end; }
.input-row .el-input { flex: 1; }
.input-row .el-button { height: 54px; padding: 0 22px; }

/* ===== 右栏 ===== */
.cite-side {
  width: 264px; flex-shrink: 0;
  background: #fff;
  border: 1px solid #e8eee9;
  border-radius: 12px;
  padding: 10px;
  display: flex; flex-direction: column;
  overflow: hidden;
  transition: width .2s;
}
.cite-side.folded { width: 44px; padding: 10px 6px; }
.cite-list { overflow-y: auto; flex: 1; }
.cite-empty { font-size: 12px; color: #a0aec0; line-height: 1.8; padding: 12px 4px; }
.cite-card {
  background: #f6f8f7; border: 1px solid #eef2f0;
  border-radius: 10px; padding: 10px; margin-bottom: 8px;
}
.cite-src { font-size: 12px; font-weight: 600; color: #14532d; margin-bottom: 5px; }
.cite-text { font-size: 12px; color: #475569; line-height: 1.7; }
</style>
