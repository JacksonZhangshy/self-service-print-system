<template>
  <div class="page" style="max-width: 420px">
    <div class="card">
      <h2>手机号登录</h2>
      <p class="sub">用于打印文件与订单查询，公共电脑使用完请退出登录</p>

      <el-alert v-if="demoMode" type="warning" :closable="false" style="margin-bottom:16px"
        :title="`演示模式：验证码固定为 ${demoCode}`" />

      <el-form label-position="top">
        <el-form-item label="手机号">
          <el-input v-model="phone" maxlength="11" placeholder="11 位手机号" @keyup.enter="login" />
        </el-form-item>
        <el-form-item label="验证码">
          <div class="row" style="flex-wrap: nowrap; width: 100%">
            <el-input v-model="code" maxlength="6" placeholder="6 位验证码" @keyup.enter="login" />
            <el-button :disabled="countdown > 0 || sending" :loading="sending" @click="sendCode"
              style="min-width: 118px">
              {{ countdown > 0 ? countdown + 's' : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>
      </el-form>

      <el-button type="primary" style="width: 100%; margin-top: 6px" :loading="logging" @click="login">
        登录 / 注册
      </el-button>

      <p class="sub" style="margin: 16px 0 0">
        <router-link to="/admin/login">管理员入口</router-link>
      </p>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../../api'

const router = useRouter()
const phone = ref('')
const code = ref('')
const countdown = ref(0)
const sending = ref(false)
const logging = ref(false)
const demoMode = ref(false)
const demoCode = ref('')

async function sendCode() {
  if (!/^1\d{10}$/.test(phone.value)) {
    ElMessage.warning('请输入正确的 11 位手机号')
    return
  }
  sending.value = true
  try {
    const r = await authApi.sendCode(phone.value)
    demoMode.value = !!r.demoMode
    demoCode.value = r.demoCode || ''
    ElMessage.success('验证码已发送')
    countdown.value = r.cooldownSeconds || 60
    const t = setInterval(() => {
      countdown.value -= 1
      if (countdown.value <= 0) clearInterval(t)
    }, 1000)
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    sending.value = false
  }
}

async function login() {
  if (!/^1\d{10}$/.test(phone.value)) return ElMessage.warning('请输入正确的手机号')
  if (!code.value) return ElMessage.warning('请输入验证码')
  logging.value = true
  try {
    const r = await authApi.login(phone.value, code.value)
    localStorage.setItem('userToken', r.token)
    localStorage.removeItem('adminToken')
    ElMessage.success('登录成功')
    router.push('/user/print')
  } catch (e) {
    // 已提示
  } finally {
    logging.value = false
  }
}

onMounted(() => {
  if (localStorage.getItem('userToken')) router.replace('/user/print')
})
</script>
