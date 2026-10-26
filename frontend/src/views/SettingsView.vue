<template>
  <div class="settings">
    <div class="settings-title">系统设置</div>

    <!-- 账号与安全 -->
    <el-card class="set-card" shadow="never">
      <template #header><div class="set-head"><span>账号与安全</span><el-tag size="small" effect="plain" round>已完成</el-tag></div></template>
      <el-descriptions :column="1" style="margin-bottom: 14px">
        <el-descriptions-item label="登录邮箱">{{ user?.email || '—' }}</el-descriptions-item>
      </el-descriptions>

      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="80px" style="max-width: 480px">
        <el-form-item label="验证码">
          <div class="code-row">
            <el-input v-model="pwdForm.code" placeholder="6 位验证码（发送至登录邮箱）" maxlength="6" />
            <el-button :disabled="counting > 0" @click="sendPwdCode">{{ counting > 0 ? `${counting}s` : '获取验证码' }}</el-button>
          </div>
        </el-form-item>
        <el-form-item label="新密码" prop="password">
          <el-input v-model="pwdForm.password" type="password" placeholder="至少 6 位" show-password />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirm">
          <el-input v-model="pwdForm.confirm" type="password" placeholder="再次输入新密码" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="submitPwd">保存新密码</el-button>
          <span class="pwd-tip">设置后可用「邮箱 + 密码」登录</span>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- RAG 参数 -->
    <el-card class="set-card" shadow="never">
      <template #header><div class="set-head"><span>RAG 检索参数</span><el-tag size="small" effect="plain" round>本地生效</el-tag></div></template>
      <div class="slider-row">
        <div class="slider-label">召回条数 recall-top-k <span class="slider-val">{{ rag.recallTopK }}</span></div>
        <el-slider v-model="rag.recallTopK" :min="1" :max="20" :step="1" />
      </div>
      <div class="slider-row">
        <div class="slider-label">相似度阈值 similarity-threshold <span class="slider-val">{{ rag.similarityThreshold }}</span></div>
        <el-slider v-model="rag.similarityThreshold" :min="0.1" :max="0.9" :step="0.05" />
      </div>
      <div class="slider-row">
        <div class="slider-label">多轮上下文 multi-turn-n <span class="slider-val">{{ rag.multiTurnN }}</span></div>
        <el-slider v-model="rag.multiTurnN" :min="0" :max="5" :step="1" />
      </div>
      <div class="rag-actions">
        <el-button type="primary" @click="saveRag">保存参数</el-button>
        <span class="pwd-tip">保存到本地浏览器（后端设置接口实现后接入云端）</span>
      </div>
    </el-card>

    <!-- 规划区 -->
    <el-card class="set-card" shadow="never">
      <template #header><div class="set-head"><span>规划中</span><el-tag size="small" effect="plain" round>占位</el-tag></div></template>
      <div class="plan-grid">
        <div class="plan-item">
          <div class="plan-name">用量统计</div>
          <div class="plan-desc">日/周 Token 折线图 + 预算告警（依赖后端用量接口）</div>
        </div>
        <div class="plan-item">
          <div class="plan-name">Prompt 模板</div>
          <div class="plan-desc">YAML 在线编辑 + 10 版本 diff / 回滚</div>
        </div>
        <div class="plan-item">
          <div class="plan-name">备份恢复</div>
          <div class="plan-desc">一键 zip + SHA-256 校验 + 自动周备</div>
        </div>
        <div class="plan-item">
          <div class="plan-name">日志查询</div>
          <div class="plan-desc">按 trace_id 查询全链路调用日志</div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { sendCaptcha, setPassword } from '../api/auth'
import { getStoredUser } from '../utils/token'

const user = computed(() => getStoredUser())

// ===== 设置密码 =====
const pwdFormRef = ref()
const pwdForm = reactive({ code: '', password: '', confirm: '' })
const saving = ref(false)
const counting = ref(0)
let timer = null

const pwdRules = {
  password: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ],
  confirm: [{
    validator: (rule, value, callback) => {
      if (!value) callback(new Error('请再次输入新密码'))
      else if (value !== pwdForm.password) callback(new Error('两次输入的密码不一致'))
      else callback()
    },
    trigger: 'blur'
  }]
}

async function sendPwdCode() {
  const email = user.value?.email
  if (!email) { ElMessage.warning('未获取到登录邮箱'); return }
  try {
    await sendCaptcha(email, 'set_password')
    ElMessage.success('验证码已发送至登录邮箱')
    counting.value = 60
    if (timer) clearInterval(timer)
    timer = setInterval(() => { counting.value -= 1; if (counting.value <= 0) clearInterval(timer) }, 1000)
  } catch (e) { /* 已由 http.js 提示 */ }
}

function submitPwd() {
  pwdFormRef.value.validate(async (valid) => {
    if (!valid) return
    const email = user.value?.email
    if (!email) return
    saving.value = true
    try {
      await setPassword(email, pwdForm.code.trim(), pwdForm.password)
      ElMessage.success('密码设置成功，下次可用邮箱+密码登录')
      pwdForm.code = ''; pwdForm.password = ''; pwdForm.confirm = ''
    } catch (e) { /* 已提示 */ } finally { saving.value = false }
  })
}

// ===== RAG 参数（本地 localStorage，演示用） =====
const RAG_KEY = 'reading_notes_rag_params'
const rag = reactive({ recallTopK: 10, similarityThreshold: 0.5, multiTurnN: 3 })

function loadRag() {
  try {
    const raw = localStorage.getItem(RAG_KEY)
    if (raw) Object.assign(rag, JSON.parse(raw))
  } catch (e) { /* 忽略 */ }
}

function saveRag() {
  localStorage.setItem(RAG_KEY, JSON.stringify({ ...rag }))
  ElMessage.success('RAG 参数已保存（本地生效）')
}

onMounted(loadRag)
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.settings { max-width: 860px; }
.settings-title { font-size: 19px; font-weight: 700; color: #14532d; margin-bottom: 14px; }
.set-card {
  border-radius: 12px; border: 1px solid #e8eee9; margin-bottom: 14px;
  box-shadow: none;
}
.set-card :deep(.el-card__header) { border-bottom: 1px solid #eef2f0; }
.set-head { display: flex; align-items: center; justify-content: space-between; font-weight: 700; color: #1f2937; }
.code-row { display: flex; gap: 8px; width: 100%; }
.code-row .el-input { flex: 1; }
.code-row .el-button { width: 108px; }
.pwd-tip { font-size: 12px; color: #94a3b8; margin-left: 12px; }

.slider-row { margin-bottom: 14px; }
.slider-label { display: flex; justify-content: space-between; font-size: 13px; color: #475569; margin-bottom: 2px; }
.slider-val { font-weight: 700; color: #14532d; }
.rag-actions { display: flex; align-items: center; }

.plan-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; }
.plan-item { background: #f6f8f7; border: 1px solid #eef2f0; border-radius: 10px; padding: 14px; }
.plan-name { font-weight: 600; font-size: 14px; color: #1f2937; }
.plan-desc { font-size: 12px; color: #94a3b8; margin-top: 6px; line-height: 1.6; }
</style>
