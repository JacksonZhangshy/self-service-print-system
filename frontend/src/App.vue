<template>
  <div class="shell">
    <header class="nav">
      <div class="brand" @click="$router.push(loggedIn ? homePath : '/user/login')">
        自助打印
        <span class="terminal" v-if="terminalId">终端 {{ terminalId }}</span>
      </div>
      <nav class="links" v-if="loggedIn">
        <router-link v-if="!isAdmin" to="/user/print">打印</router-link>
        <router-link v-if="!isAdmin" to="/user/orders">我的订单</router-link>
        <template v-if="isAdmin">
          <router-link to="/admin/dashboard">看板</router-link>
          <router-link to="/admin/orders">订单</router-link>
          <router-link to="/admin/tasks">打印任务</router-link>
          <router-link to="/admin/prices">价格</router-link>
          <router-link to="/admin/users">用户</router-link>
          <router-link to="/admin/audit">审计</router-link>
        </template>
      </nav>
      <div class="right">
        <span class="idle-hint" v-if="loggedIn">{{ idleLeft }} 秒后自动退出</span>
        <button v-if="loggedIn" class="btn-ghost" @click="logout">退出登录</button>
      </div>
    </header>
    <main class="main">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from './api'
import { getTerminalId, initTerminal } from './terminal'

const router = useRouter()
const route = useRoute()
const userToken = ref(localStorage.getItem('userToken'))
const adminToken = ref(localStorage.getItem('adminToken'))
const idleSeconds = ref(15 * 60)
const idleLeft = ref(0)
const terminalId = ref('')

const loggedIn = computed(() => !!(userToken.value || adminToken.value))
const isAdmin = computed(() => !!adminToken.value && route.path.startsWith('/admin'))
const homePath = computed(() => (localStorage.getItem('adminToken') ? '/admin/dashboard' : '/user/print'))

let timer = null
let lastActivity = Date.now()

function refreshTokenState() {
  userToken.value = localStorage.getItem('userToken')
  adminToken.value = localStorage.getItem('adminToken')
}

function touch() {
  lastActivity = Date.now()
}

function tick() {
  refreshTokenState()
  if (!loggedIn.value) {
    idleLeft.value = 0
    return
  }
  const idleMs = idleSeconds.value * 1000
  const left = Math.max(0, Math.round((idleMs - (Date.now() - lastActivity)) / 1000))
  idleLeft.value = left
  if (left <= 0) {
    logout(true)
  }
}

async function logout(auto = false) {
  if (auto === true) {
    ElMessage.warning('长时间无操作，已自动退出登录')
  }
  try {
    await authApi.logout()
  } catch (e) {
    // 会话可能已失效，忽略
  }
  localStorage.removeItem('userToken')
  localStorage.removeItem('adminToken')
  refreshTokenState()
  router.push('/user/login')
}

onMounted(async () => {
  terminalId.value = initTerminal()
  ;['mousemove', 'keydown', 'click', 'touchstart', 'scroll'].forEach((e) =>
    window.addEventListener(e, touch, { passive: true })
  )
  timer = setInterval(tick, 1000)
  if (localStorage.getItem('userToken')) {
    try {
      const me = await authApi.me()
      if (me && me.idleMinutes) idleSeconds.value = Math.max(1, me.idleMinutes) * 60
    } catch (e) {
      // 401 已由拦截器处理
    }
  }
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  ;['mousemove', 'keydown', 'click', 'touchstart', 'scroll'].forEach((e) =>
    window.removeEventListener(e, touch)
  )
})
</script>

<style>
:root {
  --ink: #1d1d1f;
  --muted: #6e6e73;
  --line: #e6e6ea;
  --bg: #f5f5f7;
  --accent: #0071e3;
}
* { box-sizing: border-box; }
body {
  margin: 0;
  background: var(--bg);
  color: var(--ink);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  -webkit-font-smoothing: antialiased;
}
.shell { min-height: 100vh; display: flex; flex-direction: column; }
.nav {
  height: 56px; display: flex; align-items: center; gap: 24px;
  padding: 0 28px; background: rgba(255,255,255,.86);
  backdrop-filter: saturate(180%) blur(20px);
  border-bottom: 1px solid var(--line); position: sticky; top: 0; z-index: 20;
}
.brand { font-weight: 600; font-size: 16px; letter-spacing: -.01em; cursor: pointer; display: flex; align-items: center; gap: 10px; }
.terminal { font-weight: 400; font-size: 12px; color: var(--muted); border: 1px solid var(--line); border-radius: 999px; padding: 2px 8px; }
.links { display: flex; gap: 22px; flex: 1; }
.links a { color: var(--ink); text-decoration: none; font-size: 14px; opacity: .72; }
.links a.router-link-active { opacity: 1; font-weight: 500; }
.right { margin-left: auto; display: flex; align-items: center; gap: 14px; }
.idle-hint { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
.btn-ghost {
  border: 1px solid var(--line); background: #fff; color: var(--ink);
  border-radius: 999px; padding: 6px 14px; font-size: 13px; cursor: pointer;
}
.btn-ghost:hover { border-color: #c8c8cd; }
.main { flex: 1; padding: 28px 20px 60px; }
.page { max-width: 960px; margin: 0 auto; }
.card {
  background: #fff; border: 1px solid var(--line); border-radius: 14px;
  padding: 24px; margin-bottom: 18px;
}
.card h2 { margin: 0 0 4px; font-size: 19px; font-weight: 600; letter-spacing: -.01em; }
.card .sub { color: var(--muted); font-size: 13px; margin: 0 0 18px; }
.row { display: flex; gap: 14px; flex-wrap: wrap; }
.mono { font-variant-numeric: tabular-nums; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }

/* ---------- 通用状态胶囊 ---------- */
.chip {
  display: inline-flex; align-items: center; height: 22px; padding: 0 9px;
  border-radius: 999px; font-size: 12px; line-height: 1; white-space: nowrap;
  border: 1px solid transparent;
}
.chip-ok   { background: #eef7ff; color: #0071e3; border-color: #d3e7fb; }
.chip-done { background: #eff9f1; color: #1d7a3c; border-color: #d6ecdc; }
.chip-warn { background: #fff7e8; color: #9a6100; border-color: #f6e4bf; }
.chip-bad  { background: #fdf0ef; color: #b3261e; border-color: #f6d9d6; }
.chip-mute { background: #f2f2f5; color: #6e6e73; border-color: #e6e6ea; }

/* ---------- 表格页通用 ---------- */
.toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 14px; }
.toolbar .spacer { flex: 1; }
.hint { color: var(--muted); font-size: 12.5px; }
.empty { text-align: center; color: var(--muted); padding: 42px 0; font-size: 14px; }
.pager { display: flex; justify-content: flex-end; margin-top: 14px; }

/* ---------- Element Plus 局部收敛（保持浅色克制风格） ---------- */
.el-table { --el-table-border-color: var(--line); --el-table-header-bg-color: #fafafc; }
.el-table th.el-table__cell { font-weight: 500; color: var(--muted); }
.el-button--primary { --el-button-bg-color: var(--accent); --el-button-border-color: var(--accent); }
</style>
