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
  </div>
</template>

<script setup>
import { computed, reactive, ref, onBeforeUnmount } from 'vue'
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

onBeforeUnmount(() => { if (timer) clearInterval(timer) })

// ===== 设置密码 =====
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
</style>
