<template>
  <div class="page">
    <div class="card">
      <h2>我的订单</h2>
      <p class="sub">仅显示本手机号下的订单，文件在打印完成后 {{ retentionDays }} 天内自动清理。</p>

      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!orders.length" class="empty">
        还没有订单，<router-link to="/user/print">去打印</router-link>
      </div>

      <div v-else class="order-list">
        <div class="order" v-for="o in orders" :key="o.orderNo">
          <div class="order-main">
            <div class="line1">
              <span class="fname" :title="o.fileName">{{ o.fileName }}</span>
              <span class="chip" :class="'chip-' + orderStatus(o.status).tone">
                {{ orderStatus(o.status).label }}
              </span>
              <span v-if="o.refundStatus && o.refundStatus !== 'NONE'" class="chip chip-mute">
                退款{{ refundStatus(o.refundStatus).label }}
              </span>
            </div>
            <div class="line2">
              <span>{{ COLOR_LABEL[o.colorMode] || o.colorMode }}</span>
              <i>·</i>
              <span>{{ DUPLEX_LABEL[o.duplex] || o.duplex }}</span>
              <i>·</i>
              <span>{{ o.paperSize }}</span>
              <i>·</i>
              <span>{{ o.pageCount }} 页 × {{ o.copies }} 份</span>
              <i>·</i>
              <span v-if="o.payMethod">{{ PAY_METHOD_LABEL[o.payMethod] || o.payMethod }}</span>
              <span v-else>未支付</span>
            </div>
            <div class="line3 mono">
              {{ o.orderNo }}
              <i>·</i>
              {{ dt(o.createdAt) }}
              <template v-if="o.terminalId"><i>·</i>终端 {{ o.terminalId }}</template>
            </div>
            <div class="line3" v-if="o.cancelReason">
              <span class="hint">原因：{{ o.cancelReason }}</span>
            </div>
          </div>

          <div class="order-side">
            <div class="amount">¥{{ money(o.amount) }}</div>
            <div class="ops">
              <el-button
                v-if="payable(o)"
                type="primary"
                size="small"
                round
                @click="$router.push(`/user/pay/${o.orderNo}`)"
              >
                去支付
              </el-button>
              <el-button
                v-if="payable(o)"
                size="small"
                round
                :loading="cancelling === o.orderNo"
                @click="cancel(o)"
              >
                取消
              </el-button>
              <el-button
                v-if="o.fileId"
                size="small"
                round
                text
                @click="preview(o)"
              >
                预览文件
              </el-button>
            </div>
          </div>
        </div>
      </div>

      <div class="pager" v-if="total > size">
        <el-pagination
          layout="prev, pager, next"
          :total="total"
          :page-size="size"
          :current-page="page + 1"
          @current-change="(p) => load(p - 1)"
        />
      </div>
    </div>

    <el-dialog v-model="previewOpen" title="文件预览" width="min(880px, 92vw)" destroy-on-close>
      <iframe v-if="previewUrl" :src="previewUrl" class="preview-frame" />
      <template #footer>
        <el-button @click="previewOpen = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userApi } from '../../api'
import {
  COLOR_LABEL,
  DUPLEX_LABEL,
  PAY_METHOD_LABEL,
  dt,
  money,
  orderStatus,
  payable,
  refundStatus
} from '../../utils/status'

const orders = ref([])
const total = ref(0)
const page = ref(0)
const size = 10
const loading = ref(false)
const cancelling = ref('')
const retentionDays = ref(7)

const previewOpen = ref(false)
const previewUrl = ref('')

async function load(p = 0) {
  loading.value = true
  try {
    const data = await userApi.myOrders(p, size)
    orders.value = data.list || []
    total.value = data.total || 0
    page.value = p
    // 文件保留天数由后端配置决定，取不到就用默认值展示
    if (data.retentionDays) retentionDays.value = data.retentionDays
  } finally {
    loading.value = false
  }
}

async function cancel(o) {
  try {
    await ElMessageBox.confirm(`确定取消订单 ${o.orderNo} 吗？取消后需要重新下单。`, '取消订单', {
      confirmButtonText: '取消订单',
      cancelButtonText: '返回',
      type: 'warning'
    })
  } catch (e) {
    return
  }
  cancelling.value = o.orderNo
  try {
    await userApi.cancel(o.orderNo)
    ElMessage.success('订单已取消')
    await load(page.value)
  } finally {
    cancelling.value = ''
  }
}

async function preview(o) {
  try {
    // 预览接口需要 Bearer token，用 axios 自己拉 blob 再交给 iframe
    const token = localStorage.getItem('userToken')
    const resp = await fetch(userApi.previewUrl(o.fileId), {
      headers: token ? { Authorization: `Bearer ${token}` } : {}
    })
    if (!resp.ok) throw new Error('预览失败')
    const blob = await resp.blob()
    if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = URL.createObjectURL(blob)
    previewOpen.value = true
  } catch (e) {
    ElMessage.error('文件预览失败，可能已被清理')
  }
}

onMounted(() => load(0))
</script>

<style scoped>
.order-list { display: flex; flex-direction: column; }
.order {
  display: flex; gap: 18px; align-items: flex-start;
  padding: 16px 0; border-top: 1px solid var(--line);
}
.order:first-child { border-top: none; }
.order-main { flex: 1; min-width: 0; }
.line1 { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.fname {
  font-size: 15px; font-weight: 500; max-width: 420px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.line2 { font-size: 13px; color: #3a3a3c; display: flex; gap: 7px; flex-wrap: wrap; }
.line3 { font-size: 12px; color: var(--muted); margin-top: 4px; display: flex; gap: 7px; }
.line2 i, .line3 i { font-style: normal; color: #c8c8cd; }
.order-side { text-align: right; min-width: 130px; }
.amount { font-size: 19px; font-weight: 600; letter-spacing: -.02em; font-variant-numeric: tabular-nums; }
.ops { margin-top: 8px; display: flex; flex-direction: column; gap: 6px; align-items: flex-end; }
.ops :deep(.el-button + .el-button) { margin-left: 0; }
.preview-frame { width: 100%; height: 68vh; border: 1px solid var(--line); border-radius: 10px; }
</style>
