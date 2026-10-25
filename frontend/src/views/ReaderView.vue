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
      <div class="demo-tag">演示模式：AI 输出为内置示例，接口接入后切换真实</div>
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
import { listBooks, createNote, aiQa } from '../api'

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

const DEMO = false // 后端 /api/v1/ai/qa 已接入，走真实 RAG 问答

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

// ===== 演示数据生成 =====
function demoAnswer(question) {
  const t = currentBook.value?.title || '本书'
  if (mode.value === 'summary') {
    return `<p><b>《${t}》全书摘要</b></p><p>本书围绕核心主题，构建了「概念界定 → 问题分析 → 方法框架 → 实践验证」四层结构：</p><p>· <b>第 1-2 章</b>：交代背景与核心概念，厘清问题的边界；<br/>· <b>第 3-4 章</b>：提出分析框架与关键方法，附典型场景拆解；<br/>· <b>第 5-6 章</b>：案例实证与对比研究，给出可复用的结论；<br/>· <b>结语</b>：总结观点、局限与未来方向。</p><p>一句话概括：用结构化方法把复杂问题拆成可执行步骤，是本书的核心价值。</p>`
  }
  if (mode.value === 'mindmap') {
    return `<pre class="mm">《${t}》 思维导图
├─ 1 背景与问题
│   ├─ 1.1 时代背景
│   └─ 1.2 核心痛点
├─ 2 概念与框架
│   ├─ 2.1 关键概念界定
│   └─ 2.2 分析框架
├─ 3 方法与路径
│   ├─ 3.1 方法一
│   ├─ 3.2 方法二
│   └─ 3.3 组合策略
├─ 4 案例与验证
│   ├─ 4.1 案例 A
│   └─ 4.2 案例 B
└─ 5 结论与展望
    ├─ 5.1 核心结论
    └─ 5.2 局限与未来</pre>`
  }
  // qa
  return `<p>针对你的问题「${question}」，结合书中第 3 章的分析框架：</p><p>作者首先界定问题边界，然后从<b>现状、成因、影响</b>三个维度展开；书中给出的关键方法是<b>先建模再验证</b>——先提出假设框架，再用案例数据检验，避免直接跳到结论。</p><p>引用原文见右侧面板。</p>`
}

// 演示引用：按当前书与模式动态生成（接口接入后替换为真实检索片段）
function demoCitationsFor() {
  const t = currentBook.value?.title || '本书'
  if (mode.value === 'qa') {
    return [
      { book: t, page: 23, text: '书中第 3 章指出：分析复杂问题时应先建立结构化的拆解框架，再逐层验证假设，而非直接套用经验结论。' },
      { book: t, page: 47, text: '作者强调案例的价值在于可复现性：一个完整的案例应包含背景、决策、结果与复盘四个环节，缺一不可。' },
      { book: t, page: 61, text: '关于方法组合：单一方法易陷入局部最优，跨方法的交叉验证能显著提升结论的稳健性。' }
    ]
  }
  if (mode.value === 'mindmap') {
    return [
      { book: t, page: 1, text: '全书结构：第 1-2 章为背景与概念界定，第 3-4 章为方法框架，第 5-6 章为案例验证，结语为结论与展望。' },
      { book: t, page: 35, text: '框架部分：作者主张先界定问题边界，再分现状、成因、影响三个维度展开分析。' }
    ]
  }
  return [
    { book: t, page: 12, text: '引言章节：交代了本书的研究背景与核心问题，明确「结构化拆解 + 逐层验证」的主线。' },
    { book: t, page: 58, text: '案例章节：通过多个实证案例验证方法的可复现性，并给出复用的四步流程。' }
  ]
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

// 生成一条 AI 回答（真实 RAG：后端检索 + 豆包生成；打字机模拟流式展示）
async function generateAnswer(question) {
  busy.value = true
  let full = ''
  let citations = []
  if (!DEMO) {
    try {
      const data = await aiQa({ book_id: currentBook.value.book_id, question })
      full = data.answer || '（未返回内容）'
      citations = (data.citations || []).map((c) => ({
        book: currentBook.value?.title || '本书',
        page: c.page || 0,
        text: c.snippet || '',
        similarity: c.similarity,
        chapter_path: c.chapter_path,
        chunk_id: c.chunk_id
      }))
    } catch (e) {
      busy.value = false
      ElMessage.error(e?.message || 'AI 问答暂不可用，请稍后再试')
      return
    }
  } else {
    await new Promise((r) => setTimeout(r, 500))
    full = demoAnswer(question)
    citations = demoCitationsFor()
  }
  busy.value = false

  const m = { role: 'ai', content: full, citations, streaming: true }
  messages.push(m)
  scrollToBottom()

  // 打字机效果
  streamingText.value = ''
  const plain = full.replace(/<[^>]+>/g, '').replace(/^\s*[|├└─]\s*/gm, '')
  let i = 0
  const timer = setInterval(() => {
    i += 3
    streamingText.value = plain.slice(0, i)
    scrollToBottom()
    if (i >= plain.length) {
      clearInterval(timer)
      m.streaming = false
      m.content = full
      streamingText.value = ''
      activeCitations.value = citations
      citeOpen.value = true   // 回答完成自动展开引用溯源
      scrollToBottom()
    }
  }, 40)
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
.mm { font-family: 'SF Mono', Consolas, monospace; font-size: 13px; line-height: 1.8; color: #334155; margin: 0; }
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
