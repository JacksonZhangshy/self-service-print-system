<template>
  <div class="page">
    <div class="page-wide">
      <div class="card">
        <div class="toolbar">
          <h2 style="margin: 0">打印任务</h2>
          <div class="spacer"></div>
          <el-select v-model="status" placeholder="全部状态" clearable style="width: 124px" @change="load(0)">
            <el-option v-for="o in TASK_STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-switch v-model="auto" active-text="自动刷新" size="small" />
          <el-button @click="load(page)">刷新</el-button>
        </div>

        <el-table :data="list" v-loading="loading" size="small" empty-text="暂无任务">
          <el-table-column label="任务" width="72">
            <template #default="{ row }"><span class="mono">#{{ row.taskId }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="92">
            <template #default="{ row }">
              <span class="chip" :class="'chip-' + taskStatus(row.status).tone">
                {{ taskStatus(row.status).label }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="orderNo" label="订单号" width="180" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
          </el-table-column>
          <el-table-column prop="fileName" label="文件" min-width="150" show-overflow-tooltip />
          <el-table-column label="规格" width="150">
            <template #default="{ row }">
              {{ COLOR_LABEL[row.colorMode] }}{{ row.paperSize }}{{ DUPLEX_LABEL[row.duplex] }}
            </template>
          </el-table-column>
          <el-table-column label="页数×份" width="96" align="right">
            <template #default="{ row }">
              <span class="mono">{{ row.pageCount }}×{{ row.copies }}</span>
              <div v-if="row.actualPages" class="hint mono">实打 {{ row.actualPages }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="terminalId" label="终端" width="88">
            <template #default="{ row }">{{ row.terminalId || '-' }}</template>
          </el-table-column>
          <el-table-column prop="agentId" label="助手" width="110" show-overflow-tooltip>
            <template #default="{ row }">{{ row.agentId || '-' }}</template>
          </el-table-column>
          <el-table-column label="重试" width="62" align="center">
            <template #default="{ row }">
              <span :class="{ warn: row.retryCount > 0 }" class="mono">{{ row.retryCount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="超时时间" width="146">
            <template #default="{ row }">
              <span class="hint" :class="{ overdue: isOverdue(row) }">{{ dt(row.deadlineAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建 / 完成" width="180">
            <template #default="{ row }">
              <div class="hint">{{ dt(row.createdAt) }}</div>
              <div class="hint" v-if="row.finishedAt">→ {{ dt(row.finishedAt) }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="failReason" label="失败原因" min-width="140" show-overflow-tooltip />
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

      <div class="card">
        <h2>异常说明</h2>
        <p class="sub" style="margin-bottom: 0">
          任务被助手领取后 {{ claimTimeout }} 分钟内没有回报结果，超时任务会被自动回收重排；
          超过最大重试次数（{{ maxRetry }} 次）后置为失败，并触发订单自动退款。
          标红的时间表示该任务已超过处理期限。
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { adminApi } from '../../api'
import { COLOR_LABEL, DUPLEX_LABEL, TASK_STATUS_OPTIONS, dt, taskStatus } from '../../utils/status'

const list = ref([])
const total = ref(0)
const page = ref(0)
const size = 20
const loading = ref(false)
const status = ref('')
const auto = ref(true)
const claimTimeout = ref(15)
const maxRetry = ref(2)
let timer = null

async function load(p = 0) {
  loading.value = true
  try {
    const data = await adminApi.tasks({
      status: status.value || undefined,
      page: p,
      size
    })
    list.value = data.list || []
    total.value = data.total || 0
    page.value = p
  } finally {
    loading.value = false
  }
}

function isOverdue(row) {
  if (!row.deadlineAt) return false
  if (row.status === 'SUCCESS' || row.status === 'FAILED' || row.status === 'CANCELLED') return false
  return new Date(row.deadlineAt.replace(' ', 'T')).getTime() < Date.now()
}

function startAuto() {
  stopAuto()
  timer = setInterval(() => {
    if (auto.value && !loading.value) load(page.value)
  }, 5000)
}
function stopAuto() {
  if (timer) clearInterval(timer)
  timer = null
}

watch(auto, (v) => {
  if (v) load(page.value)
})

onMounted(() => {
  load(0)
  startAuto()
})
onUnmounted(stopAuto)
</script>

<style scoped>
.page-wide { max-width: 1400px; margin: 0 auto; }
.warn { color: #9a6100; }
.overdue { color: #b3261e; }
</style>
