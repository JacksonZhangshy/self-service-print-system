<template>
  <div class="page" style="max-width: 400px">
    <div class="card">
      <h2>管理端登录</h2>
      <p class="sub">仅限运维人员，登录行为会记入审计日志</p>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="用户名">
          <el-input v-model="username" placeholder="管理员用户名" @keyup.enter="login" />
        </el-form-item>
        <el-form-item label="口令">
          <el-input
            v-model="password"
            type="password"
            show-password
            placeholder="登录口令"
            @keyup.enter="login"
          />
        </el-form-item>
      </el-form>

      <el-button type="primary" style="width: 100%" :loading="loading" @click="login">登录</el-button>

      <p class="sub" style="margin: 16px 0 0">
        <router-link to="/user/login">返回用户登录</router-link>
      </p>
      <p class="hint" style="margin: 8px 0 0">
        默认口令在首次启动时由 <span class="mono">ADMIN_PASSWORD</span> 环境变量写入，请尽快修改。
      </p>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { adminApi } from '../../api'

const router = useRouter()
const username = ref('admin')
const password = ref('')
const loading = ref(false)

async function login() {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入用户名与口令')
    return
  }
  loading.value = true
  try {
    const r = await adminApi.login(username.value.trim(), password.value)
    localStorage.setItem('adminToken', r.token)
    localStorage.removeItem('userToken')
    ElMessage.success('登录成功')
    router.push('/admin/dashboard')
  } catch (e) {
    // 已提示
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (localStorage.getItem('adminToken')) router.replace('/admin/dashboard')
})
</script>
