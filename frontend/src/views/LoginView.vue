<template>
  <div class="login-wrap">
    <!-- 左：品牌区 -->
    <div class="brand-side">
      <div class="brand-logo">
        <div class="brand-mark">智</div>
        <div class="brand-name">智读 · 阅读笔记</div>
      </div>
      <div class="brand-slogan">知识库驱动的个人阅读<br />读书笔记智能分析系统</div>
      <ul class="brand-features">
        <li><span class="feat-dot" />AI 读书：全书摘要 · 书本问答 · 思维导图</li>
        <li><span class="feat-dot" />知识回流：笔记向量入库，越读越懂你</li>
        <li><span class="feat-dot" />双向链接：笔记互联，构建你的知识网络</li>
      </ul>
      <div class="brand-foot">2026 东莞市工业 AI 应用创新挑战「百景大赛」</div>
    </div>

    <!-- 右：登录卡 -->
    <div class="form-side">
      <el-card class="login-card" shadow="never">
        <el-tabs v-model="tab" stretch>
          <!-- 验证码登录（未注册自动注册） -->
          <el-tab-pane label="验证码登录" name="code">
            <el-form ref="codeFormRef" :model="codeForm" :rules="codeRules" label-position="top" size="large">
              <el-form-item label="邮箱" prop="email">
                <el-input v-model="codeForm.email" placeholder="输入邮箱，未注册将自动创建账号" clearable />
              </el-form-item>
              <el-form-item label="验证码" prop="code">
                <div class="code-row">
                  <el-input v-model="codeForm.code" placeholder="6 位验证码" maxlength="6" @keyup.enter="doCodeLogin" />
                  <el-button :disabled="counting > 0 || !codeForm.email" @click="sendCode">
                    {{ counting > 0 ? `${counting}s` : '获取验证码' }}
                  </el-button>
                </div>
              </el-form-item>
              <el-button type="primary" class="submit-btn" size="large" :loading="loading" @click="doCodeLogin">
                登录 / 注册
              </el-button>
              <div class="form-tip">新邮箱验证码通过后自动注册并登录，无需单独注册</div>
            </el-form>
          </el-tab-pane>

          <!-- 密码登录 -->
          <el-tab-pane label="密码登录" name="password">
            <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-position="top" size="large">
              <el-form-item label="邮箱" prop="email">
                <el-input v-model="pwdForm.email" placeholder="请输入邮箱" clearable />
              </el-form-item>
              <el-form-item label="密码" prop="password">
                <el-input v-model="pwdForm.password" type="password" placeholder="请输入密码" show-password @keyup.enter="doPwdLogin" />
              </el-form-item>
              <el-button type="primary" class="submit-btn" size="large" :loading="loading" @click="doPwdLogin">
                登 录
              </el-button>
              <div class="form-tip">未设置过密码的账号请先使用验证码登录，再到「系统设置 → 账号与安全」设置密码</div>
            </el-form>
          </el-tab-pane>
        </el-tabs>

        <div class="tip">验证码邮件发送至填写的邮箱（SMTP 发件服务）</div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { sendCaptcha, loginByCode, loginByPassword, getMe } from '../api/auth'
import { setToken, setStoredUser } from '../utils/token'

const router = useRouter()
const route = useRoute()

const tab = ref('code')
const loading = ref(false)
const counting = ref(0)
let timer = null

const codeFormRef = ref()
const pwdFormRef = ref()

const codeForm = reactive({ email: '', code: '' })
const pwdForm = reactive({ email: '', password: '' })

const emailRule = { required: true, message: '请输入邮箱', trigger: 'blur' }
const codeRules = {
  email: [emailRule, { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  code: [{ required: true, message: '请输入验证码', trigger: 'blur' }]
}
const pwdRules = {
  email: [emailRule, { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function sendCode() {
  const email = codeForm.email
  if (!email) {
    ElMessage.warning('请先填写邮箱')
    return
  }
  try {
    await sendCaptcha(email.trim(), 'login')
    ElMessage.success('验证码已发送，请查收邮箱')
    counting.value = 60
    if (timer) clearInterval(timer)
    timer = setInterval(() => {
      counting.value -= 1
      if (counting.value <= 0) clearInterval(timer)
    }, 1000)
  } catch (e) { /* 错误提示已由 http.js 统一弹出 */ }
}

async function afterLogin(token) {
  setToken(token)
  try {
    const me = await getMe()
    setStoredUser(me)
  } catch (e) { /* me 拉取失败不阻塞登录 */ }
  router.replace(route.query.redirect || '/library')
}

function doCodeLogin() {
  codeFormRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      const token = await loginByCode(codeForm.email.trim(), codeForm.code.trim())
      await afterLogin(token.token)
      ElMessage.success('登录成功')
    } catch (e) { /* 已由 http.js 提示 */ } finally {
      loading.value = false
    }
  })
}

function doPwdLogin() {
  pwdFormRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      const token = await loginByPassword(pwdForm.email.trim(), pwdForm.password)
      await afterLogin(token.token)
      ElMessage.success('登录成功')
    } catch (e) { /* 已由 http.js 提示 */ } finally {
      loading.value = false
    }
  })
}

onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  background: #fff;
}
/* ===== 左品牌区 ===== */
.brand-side {
  flex: 1.25;
  background: linear-gradient(160deg, #14532d 0%, #1e6a3f 55%, #2f855a 100%);
  color: #fff;
  padding: 9vh 7vw;
  display: flex;
  flex-direction: column;
}
.brand-logo { display: flex; align-items: center; gap: 12px; }
.brand-mark {
  width: 44px; height: 44px; border-radius: 12px;
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.28);
  font-size: 22px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
}
.brand-name { font-size: 20px; font-weight: 700; letter-spacing: 1px; }
.brand-slogan {
  margin-top: 56px;
  font-size: 30px; font-weight: 700; line-height: 1.45; letter-spacing: 1px;
}
.brand-features { list-style: none; margin-top: 40px; padding: 0; font-size: 14px; color: rgba(255,255,255,.88); line-height: 2.2; }
.feat-dot {
  display: inline-block; width: 8px; height: 8px; border-radius: 50%;
  background: #86efac; margin-right: 12px; vertical-align: 1px;
}
.brand-foot { margin-top: auto; font-size: 12px; color: rgba(255,255,255,.55); }

/* ===== 右表单区 ===== */
.form-side {
  flex: 1;
  background: #f6f8f7;
  display: flex; align-items: center; justify-content: center;
  padding: 32px;
}
.login-card {
  width: 400px; max-width: 100%;
  border-radius: 16px;
  box-shadow: 0 8px 32px rgba(16, 42, 28, 0.10);
  border: none;
  padding: 8px 6px;
}
.code-row { display: flex; gap: 8px; width: 100%; }
.code-row .el-input { flex: 1; }
.code-row .el-button { width: 118px; }
.submit-btn { width: 100%; margin-top: 6px; }
.form-tip { margin-top: 10px; text-align: center; font-size: 12px; color: #94a3b8; }
.tip { margin-top: 18px; text-align: center; font-size: 12px; color: #94a3b8; }

/* 窄屏：隐藏品牌区 */
@media (max-width: 900px) {
  .brand-side { display: none; }
  .form-side { flex: 1; }
}
</style>
