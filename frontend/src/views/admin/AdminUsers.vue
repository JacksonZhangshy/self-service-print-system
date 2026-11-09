<template>
  <div class="page">
    <div class="card">
      <div class="toolbar">
        <h2 style="margin: 0">用户管理</h2>
        <div class="spacer"></div>
        <el-input
          v-model="phone"
          placeholder="按手机号搜索"
          clearable
          maxlength="11"
          style="width: 180px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" @click="search">查询</el-button>
      </div>

      <el-table :data="list" v-loading="loading" size="small" empty-text="暂无用户">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <span class="chip" :class="row.status === 'BLOCKED' ? 'chip-bad' : 'chip-done'">
              {{ row.status === 'BLOCKED' ? '已封禁' : '正常' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="170">
          <template #default="{ row }"><span class="hint">{{ dt(row.createdAt) }}</span></template>
        </el-table-column>
        <el-table-column label="最后登录" width="170">
          <template #default="{ row }"><span class="hint">{{ dt(row.lastLoginAt) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" min-width="120">
          <template #default="{ row }">
            <el-button
              v-if="row.status !== 'BLOCKED'"
              link
              type="danger"
              size="small"
              @click="toggle(row, 'BLOCKED')"
            >
              封禁
            </el-button>
            <el-button v-else link type="primary" size="small" @click="toggle(row, 'ACTIVE')">
              解封
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="total"
          :page-size="size"
          :current-page="page + 1"
          @current-change="(p) => load(p - 1)"
        />
      </div>

      <p class="hint">
        封禁会立即吊销该用户的全部登录会话（公共电脑上正在使用的页面下一次请求即被踢出）。
      </p>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../../api'
import { dt } from '../../utils/status'

const list = ref([])
const total = ref(0)
const page = ref(0)
const size = 20
const loading = ref(false)
const phone = ref('')

async function load(p = 0) {
  loading.value = true
  try {
    const data = await adminApi.users({ phone: phone.value || undefined, page: p, size })
    list.value = data.list || []
    total.value = data.total || 0
    page.value = p
  } finally {
    loading.value = false
  }
}

function search() {
  load(0)
}

async function toggle(row, status) {
  const isBlock = status === 'BLOCKED'
  try {
    await ElMessageBox.confirm(
      isBlock
        ? `封禁 ${row.phone} 后将立即强制其退出登录，确定继续？`
        : `解除 ${row.phone} 的封禁？`,
      isBlock ? '封禁用户' : '解除封禁',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) {
    return
  }
  await adminApi.updateUserStatus(row.id, status)
  ElMessage.success(isBlock ? '已封禁' : '已解封')
  await load(page.value)
}

onMounted(() => load(0))
</script>
