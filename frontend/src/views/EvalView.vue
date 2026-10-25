<template>
  <div>
    <!-- ===== 顶栏 ===== -->
    <div class="eval-head">
      <div>
        <div class="eval-title">RAG 评估</div>
        <div class="eval-sub">内置 50 题评测集 · 一键回归验证检索与回答质量（比赛亮点）</div>
      </div>
      <el-button type="primary" size="large" :loading="running" @click="runEval">
        <el-icon style="margin-right:4px"><VideoPlay /></el-icon>{{ running ? '回归中…' : '一键回归' }}
      </el-button>
    </div>

    <!-- ===== 指标卡 ===== -->
    <div class="metric-grid">
      <div v-for="m in metrics" :key="m.key" class="metric-card" :class="m.pass ? 'pass' : 'fail'">
        <div class="metric-label">{{ m.label }}</div>
        <div class="metric-value">{{ m.value }}</div>
        <div class="metric-foot">
          <span class="metric-target">目标 {{ m.target }}</span>
          <span class="metric-badge">{{ m.pass ? '达标' : '未达标' }}</span>
        </div>
      </div>
    </div>

    <!-- ===== 图表区 ===== -->
    <div class="chart-grid">
      <div class="chart-card">
        <div class="chart-title">Recall@k 召回率曲线</div>
        <svg viewBox="0 0 460 180" class="recall-chart" preserveAspectRatio="xMidYMid meet">
          <!-- 网格 -->
          <g stroke="#e8eee9" stroke-width="1">
            <line x1="40" y1="20" x2="440" y2="20" />
            <line x1="40" y1="70" x2="440" y2="70" />
            <line x1="40" y1="120" x2="440" y2="120" />
            <line x1="40" y1="170" x2="440" y2="170" />
          </g>
          <!-- 纵轴标签 -->
          <g fill="#94a3b8" font-size="9">
            <text x="34" y="23" text-anchor="end">1.0</text>
            <text x="34" y="73" text-anchor="end">0.75</text>
            <text x="34" y="123" text-anchor="end">0.5</text>
            <text x="34" y="173" text-anchor="end">0.25</text>
          </g>
          <!-- 折线（示例数据） -->
          <polyline
            :points="linePoints" fill="none" stroke="#14532d" stroke-width="2.5"
            stroke-linejoin="round" stroke-linecap="round"
          />
          <!-- 数据点 -->
          <circle v-for="(p, i) in recallPoints" :key="i" :cx="p.x" :cy="p.y" r="3" fill="#14532d">
            <title>k={{ p.k }}: {{ p.v }}</title>
          </circle>
          <!-- 横轴 -->
          <g fill="#94a3b8" font-size="9" text-anchor="middle">
            <text x="40" y="178">k=1</text>
            <text x="120" y="178">3</text>
            <text x="200" y="178">5</text>
            <text x="280" y="178">7</text>
            <text x="360" y="178">9</text>
            <text x="435" y="178">10</text>
          </g>
        </svg>
        <div class="chart-note">Recall@5 = 0.84 ≥ 目标 0.80 ✓（示例数据，接口接入后按真实结果绘制）</div>
      </div>

      <div class="chart-card">
        <div class="chart-title">分项正确率</div>
        <div class="bars">
          <div v-for="(b, i) in barItems" :key="i" class="bar-row">
            <span class="bar-label">{{ b.label }}</span>
            <div class="bar-track">
              <div class="bar-fill" :class="b.ok ? 'ok' : 'no'" :style="{ width: b.value * 100 + '%' }"></div>
            </div>
            <span class="bar-value">{{ b.value.toFixed(2) }}</span>
          </div>
        </div>
        <div class="chart-note">问答与拒答是「AI 含量」的关键指标；拒答率待提升（示例数据）</div>
      </div>
    </div>

    <!-- ===== 用例明细 ===== -->
    <div class="cases-card">
      <div class="chart-title" style="display:flex;align-items:center;justify-content:space-between;">
        <span>用例明细</span>
        <el-radio-group v-model="caseFilter" size="small">
          <el-radio-button value="all">全部</el-radio-button>
          <el-radio-button value="pass">通过</el-radio-button>
          <el-radio-button value="fail">未达标</el-radio-button>
        </el-radio-group>
      </div>
      <el-table :data="filteredCases" size="small" style="width:100%">
        <el-table-column prop="question" label="问题" min-width="220" show-overflow-tooltip />
        <el-table-column label="类型" width="86">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" round>{{ row.type }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="expected" label="预期" width="110" show-overflow-tooltip />
        <el-table-column prop="hit" label="引用命中" width="96">
          <template #default="{ row }"><span :class="row.hit ? 'hit-on' : 'hit-off'">{{ row.hit ? '✓ 命中' : '✗ 未命中' }}</span></template>
        </el-table-column>
        <el-table-column prop="latency" label="耗时" width="80">
          <template #default="{ row }">{{ row.latency }}s</template>
        </el-table-column>
        <el-table-column label="结果" width="86">
          <template #default="{ row }">
            <span :class="row.pass ? 'hit-on' : 'hit-off'">{{ row.pass ? '通过' : '未达标' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { VideoPlay } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const running = ref(false)
const caseFilter = ref('all')

// 示例指标（演示数据，接口接入后替换为 /eval/run 真实结果）
const metrics = ref([
  { key: 'recall5', label: 'Recall@5', value: '0.84', target: '≥ 0.80', pass: true },
  { key: 'answer', label: 'Answer-Acc', value: '0.78', target: '≥ 0.75', pass: true },
  { key: 'refusal', label: 'Refusal-Acc', value: '0.93', target: '≥ 0.95', pass: false },
  { key: 'latency', label: '平均首字延迟', value: '1.2s', target: '≤ 1.5s', pass: true }
])

// Recall@k 曲线（k=1..10 示例值）
const recallValues = [0.55, 0.66, 0.74, 0.80, 0.84, 0.87, 0.89, 0.91, 0.92, 0.93]
const recallPoints = recallValues.map((v, i) => ({
  k: i + 1, v,
  x: 40 + (i * 400) / 9,
  y: 170 - (v - 0.25) * (150 / 0.75)
}))
const linePoints = recallPoints.map((p) => `${p.x},${p.y}`).join(' ')

const barItems = [
  { label: '全书摘要', value: 0.86, ok: true },
  { label: '书本问答', value: 0.78, ok: true },
  { label: '拒答正确', value: 0.93, ok: false },
  { label: '引用准确', value: 0.88, ok: true }
]

const cases = ref([
  { question: '本书第三章的核心观点是什么？', type: '问答', expected: '框架先行再验证', hit: true, latency: 0.9, pass: true },
  { question: '作者提出的分析方法分几步？', type: '问答', expected: '四步法', hit: true, latency: 1.1, pass: true },
  { question: '总结本书的三大主题。', type: '摘要', expected: '三大主题概述', hit: true, latency: 1.4, pass: true },
  { question: '这本书的作者是谁？', type: '问答', expected: '作者姓名', hit: false, latency: 1.3, pass: false },
  { question: '与本书无关的问题（应拒答）', type: '拒答', expected: '拒绝回答', hit: false, latency: 0.7, pass: false },
  { question: '第二章有哪些关键概念？', type: '问答', expected: '概念清单', hit: true, latency: 1.0, pass: true },
  { question: '给出案例A的具体数据。', type: '问答', expected: '案例数据', hit: true, latency: 1.2, pass: true },
  { question: '生成全书要点脑图', type: '导图', expected: '结构树', hit: true, latency: 1.6, pass: true }
])

const filteredCases = computed(() =>
  caseFilter.value === 'all' ? cases.value
    : cases.value.filter((c) => (caseFilter.value === 'pass') === c.pass)
)

function runEval() {
  running.value = true
  setTimeout(() => {
    running.value = false
    ElMessage.success('回归完成：50/50 用例执行完毕，指标已更新（演示数据）')
  }, 2600)
}
</script>

<style scoped>
.eval-head {
  background: #fff; border: 1px solid #e8eee9; border-radius: 12px;
  padding: 16px 20px; margin-bottom: 14px;
  display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px;
}
.eval-title { font-size: 19px; font-weight: 700; color: #14532d; }
.eval-sub { font-size: 12px; color: #94a3b8; margin-top: 4px; }

.metric-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 12px; margin-bottom: 14px; }
.metric-card {
  background: #fff; border: 1px solid #e8eee9; border-radius: 12px; padding: 16px 18px;
  border-left: 4px solid #38a169;
}
.metric-card.fail { border-left-color: #e53e3e; }
.metric-label { font-size: 13px; color: #64748b; }
.metric-value { font-size: 30px; font-weight: 800; color: #14532d; margin: 6px 0; }
.metric-card.fail .metric-value { color: #e53e3e; }
.metric-foot { display: flex; align-items: center; justify-content: space-between; }
.metric-target { font-size: 12px; color: #94a3b8; }
.metric-badge {
  font-size: 11px; padding: 2px 10px; border-radius: 999px; font-weight: 600;
  background: #e6f7ee; color: #18794e;
}
.metric-card.fail .metric-badge { background: #fdecec; color: #c53030; }

.chart-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 12px; margin-bottom: 14px; }
.chart-card { background: #fff; border: 1px solid #e8eee9; border-radius: 12px; padding: 16px; }
.chart-title { font-size: 14px; font-weight: 700; color: #1f2937; margin-bottom: 10px; }
.recall-chart { width: 100%; }
.chart-note { font-size: 12px; color: #94a3b8; margin-top: 8px; }

.bars { display: flex; flex-direction: column; gap: 12px; padding-top: 6px; }
.bar-row { display: flex; align-items: center; gap: 10px; }
.bar-label { width: 70px; font-size: 12px; color: #475569; flex-shrink: 0; }
.bar-track { flex: 1; background: #eef2f0; border-radius: 999px; height: 12px; overflow: hidden; }
.bar-fill { height: 12px; border-radius: 999px; background: #38a169; transition: width .4s; }
.bar-fill.no { background: #e53e3e; }
.bar-value { width: 36px; font-size: 12px; font-weight: 600; color: #334155; }

.cases-card { background: #fff; border: 1px solid #e8eee9; border-radius: 12px; padding: 16px; }
.hit-on { color: #18794e; font-weight: 600; }
.hit-off { color: #c53030; font-weight: 600; }
</style>
