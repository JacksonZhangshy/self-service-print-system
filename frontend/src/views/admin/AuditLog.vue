<template>
  <div class="page">
    <div class="page-wide">
      <div class="card">
        <div class="toolbar">
          <h2 style="margin: 0">审计日志</h2>
          <div class="spacer"></div>
          <span class="hint">记录改价、退款、补打、封禁、改口令等敏感操作</span>
          <el-button @click="load(page)">刷新</el-button>
        </div>

        <el-table :data="list" v-loading="loading" size="small" empty-text="暂无记录">
          <el-table-column label="时间" width="170">
            <template #default="{ row }"><span class="mono hint">{{ dt(row.createdAt) }}</span></template>
          </el-table-column>
          <el-table-column prop="actor" label="操作人" width="110" />
          <el-table-column label="动作" width="140">
            <template #default="{ row }">
              <span class="chip chip-mute">{{ ACTION_LABEL[row.action] || row.action }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="target" label="对象" width="170" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono">{{ row.target }}</span></template>
          </el-table-column>
          <el-table-column prop="detail" label="详情" min-width="240" show-overflow-tooltip />
          <el-table-column prop="ip" label="IP" width="130">
            <template #default="{ row }"><span class="mono hint">{{ row.ip || '-' }}</span></template>
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
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { adminApi } from '../../api'
import { dt } from '../../utils/status'

const ACTION_LABEL = {
  UPDATE_PRICE: '修改价格',
  REFUND: '退款',
  REFUND_FAIL: '退款失败',
  AUTO_REFUND: '自动退款',
  REPRINT: '补打',
  USER_STATUS: '用户状态',
  CHANGE_PASSWORD: '修改口令',
  ADMIN_LOGIN: '管理员登录'
}

const list = ref([])
const total = ref(0)
const page = ref(0)
const size = 20
const loading = ref(false)

async function load(p = 0) {
  loading.value = true
  try {
    const data = await adminApi.audit({ page: p, size })
    list.value = data.list || []
    total.value = data.total || 0
    page.value = p
  } finally {
    loading.value = false
  }
}

onMounted(() => load(0))
</script>

<style scoped>
.page-wide { max-width: 1200px; margin: 0 auto; }
</style>
