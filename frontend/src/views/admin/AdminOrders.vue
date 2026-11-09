<template>
  <div class="page page-xwide">
    <div class="page-wide">
      <div class="card">
        <div class="toolbar">
          <h2 style="margin: 0">订单管理</h2>
          <div class="spacer"></div>
          <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 130px" @change="search">
            <el-option v-for="o in ORDER_STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-input
            v-model="filter.phone"
            placeholder="按手机号搜索"
            clearable
            maxlength="11"
            style="width: 170px"
            @keyup.enter="search"
            @clear="search"
          />
          <el-button type="primary" @click="search">查询</el-button>
        </div>

        <el-table :data="list" v-loading="loading" size="small" empty-text="暂无数据" :fit="false">
          <el-table-column prop="orderNo" label="订单号" width="186" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="88">
            <template #default="{ row }">
              <span class="chip" :class="'chip-' + orderStatus(row.status).tone">
                {{ orderStatus(row.status).label }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="userPhone" label="手机号" width="114" />
          <el-table-column prop="fileName" label="文件" width="164" show-overflow-tooltip />
          <el-table-column label="规格 / 页数" width="168">
            <template #default="{ row }">
              {{ COLOR_LABEL[row.colorMode] }}{{ row.paperSize }}{{ DUPLEX_LABEL[row.duplex] }}
              <div class="hint">
                {{ row.pageCount }} 页 × {{ row.copies }} 份<i v-if="row.pageSource === 'ESTIMATE'" class="est" title="页数为估算值">*</i>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="96" align="right">
            <template #default="{ row }">
              <span class="mono">¥{{ money(row.amount) }}</span>
              <div v-if="row.refundAmount" class="hint mono">退 ¥{{ money(row.refundAmount) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="支付" width="100">
            <template #default="{ row }">
              <span v-if="row.payMethod">{{ PAY_METHOD_LABEL[row.payMethod] }}</span>
              <span v-else class="hint">-</span>
              <div class="hint" v-if="row.payMode === 'MOCK'">模拟</div>
            </template>
          </el-table-column>
          <el-table-column label="下单时间" width="152">
            <template #default="{ row }"><span class="hint">{{ dt(row.createdAt) }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="148" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
              <el-button
                v-if="canRefund(row)"
                link
                type="danger"
                size="small"
                @click="openRefund(row)"
              >
                退款
              </el-button>
              <el-button v-if="canReprint(row)" link size="small" @click="openReprint(row)">补打</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            layout="total, prev, pager, next, sizes"
            :total="total"
            :page-size="size"
            :current-page="page + 1"
            :page-sizes="[20, 50, 100]"
            @current-change="(p) => load(p - 1)"
            @size-change="onSizeChange"
          />
        </div>
      </div>
    </div>

    <!-- 详情 -->
    <el-dialog v-model="detailOpen" title="订单详情" width="min(640px, 92vw)">
      <el-descriptions :column="2" border size="small" v-if="current">
        <el-descriptions-item label="订单号"><span class="mono">{{ current.orderNo }}</span></el-descriptions-item>
        <el-descriptions-item label="状态">{{ orderStatus(current.status).label }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ current.userPhone }}</el-descriptions-item>
        <el-descriptions-item label="终端">{{ current.terminalId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="文件" :span="2">{{ current.fileName }}</el-descriptions-item>
        <el-descriptions-item label="规格">
          {{ COLOR_LABEL[current.colorMode] }}{{ current.paperSize }}{{ DUPLEX_LABEL[current.duplex] }}
        </el-descriptions-item>
        <el-descriptions-item label="页数">
          {{ current.pageCount }}
          <span class="hint">（{{ current.pageSource === 'ESTIMATE' ? '估算' : '实际' }}）</span>
        </el-descriptions-item>
        <el-descriptions-item label="金额">¥{{ money(current.amount) }}</el-descriptions-item>
        <el-descriptions-item label="份数">{{ current.copies }}</el-descriptions-item>
        <el-descriptions-item label="支付方式">
          {{ PAY_METHOD_LABEL[current.payMethod] || '-' }} / {{ current.payMode || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="渠道单号"><span class="mono">{{ current.tradeNo || '-' }}</span></el-descriptions-item>
        <el-descriptions-item label="下单时间">{{ dt(current.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="支付时间">{{ dt(current.paidAt) }}</el-descriptions-item>
        <el-descriptions-item label="超时时间">{{ dt(current.expireAt) }}</el-descriptions-item>
        <el-descriptions-item label="退款状态">
          {{ refundStatus(current.refundStatus).label }}
          <span v-if="current.refundAmount">¥{{ money(current.refundAmount) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="备注/原因" :span="2">{{ current.cancelReason || '-' }}</el-descriptions-item>
      </el-descriptions>

      <div style="margin-top: 16px" v-if="refunds.length">
        <div class="hint" style="margin-bottom: 6px">退款流水</div>
        <el-table :data="refunds" size="small">
          <el-table-column prop="amount" label="金额" width="88">
            <template #default="{ row }">¥{{ money(row.amount) }}</template>
          </el-table-column>
          <el-table-column prop="channel" label="渠道" width="80" />
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column prop="operator" label="操作人" width="80" />
          <el-table-column prop="reason" label="原因" show-overflow-tooltip />
          <el-table-column label="时间" width="146">
            <template #default="{ row }"><span class="hint">{{ dt(row.createdAt) }}</span></template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <!-- 退款 -->
    <el-dialog v-model="refundOpen" title="整单退款" width="min(460px, 92vw)">
      <p class="hint" style="margin-top: 0">
        订单 <span class="mono">{{ current?.orderNo }}</span> 将按原支付渠道退回
        <b>¥{{ money(current?.amount) }}</b>。退款成功后订单状态变为「已退款」。
      </p>
      <el-input
        v-model="refundReason"
        type="textarea"
        :rows="3"
        maxlength="180"
        show-word-limit
        placeholder="退款原因（记入审计日志，建议写清问题）"
      />
      <template #footer>
        <el-button @click="refundOpen = false">取消</el-button>
        <el-button type="danger" :loading="acting" @click="doRefund">确认退款</el-button>
      </template>
    </el-dialog>

    <!-- 补打 -->
    <el-dialog v-model="reprintOpen" title="补打" width="min(460px, 92vw)">
      <p class="hint" style="margin-top: 0">
        将为订单 <span class="mono">{{ current?.orderNo }}</span> 重新生成一条打印任务，由对应终端助手领取。
      </p>
      <el-input
        v-model="reprintReason"
        type="textarea"
        :rows="2"
        maxlength="180"
        placeholder="补打原因（如卡纸、墨粉不足）"
      />
      <template #footer>
        <el-button @click="reprintOpen = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="doReprint">确认补打</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../../api'
import {
  COLOR_LABEL,
  DUPLEX_LABEL,
  ORDER_STATUS_OPTIONS,
  PAY_METHOD_LABEL,
  dt,
  money,
  orderStatus,
  refundStatus
} from '../../utils/status'

const list = ref([])
const total = ref(0)
const page = ref(0)
const size = ref(20)
const loading = ref(false)
const filter = reactive({ status: '', phone: '' })

const current = ref(null)
const refunds = ref([])
const detailOpen = ref(false)
const refundOpen = ref(false)
const reprintOpen = ref(false)
const refundReason = ref('')
const reprintReason = ref('')
const acting = ref(false)

const REFUNDABLE = new Set(['PAID', 'PRINTED', 'PRINT_FAILED', 'PRINTING'])
const REPRINTABLE = new Set(['PAID', 'PRINTED', 'PRINT_FAILED'])

function canRefund(row) {
  return REFUNDABLE.has(row.status) && row.refundStatus !== 'SUCCESS' && row.refundStatus !== 'PROCESSING'
}
function canReprint(row) {
  return REPRINTABLE.has(row.status) && row.refundStatus !== 'SUCCESS'
}

async function load(p = 0) {
  loading.value = true
  try {
    const data = await adminApi.orders({
      status: filter.status || undefined,
      phone: filter.phone || undefined,
      page: p,
      size: size.value
    })
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

function onSizeChange(s) {
  size.value = s
  load(0)
}

async function openDetail(row) {
  current.value = row
  detailOpen.value = true
  refunds.value = []
  try {
    refunds.value = await adminApi.refundsOf(row.orderNo)
  } catch (e) {
    // 无退款流水时忽略
  }
}

function openRefund(row) {
  current.value = row
  refundReason.value = ''
  refundOpen.value = true
}

async function doRefund() {
  if (!refundReason.value.trim()) return ElMessage.warning('请填写退款原因')
  acting.value = true
  try {
    const r = await adminApi.refund(current.value.orderNo, refundReason.value.trim())
    if (r.status === 'SUCCESS') {
      ElMessage.success('退款成功')
    } else if (r.status === 'PROCESSING') {
      ElMessage.warning('渠道已受理，退款结果以后台异步通知为准')
    } else {
      ElMessage.error(r.errorMsg || '退款失败，请查看流水')
    }
    refundOpen.value = false
    await load(page.value)
  } finally {
    acting.value = false
  }
}

function openReprint(row) {
  current.value = row
  reprintReason.value = ''
  reprintOpen.value = true
}

async function doReprint() {
  try {
    await ElMessageBox.confirm('确认生成新的打印任务？', '补打确认', {
      confirmButtonText: '确认',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  acting.value = true
  try {
    await adminApi.reprint(current.value.orderNo, reprintReason.value.trim())
    ElMessage.success('已生成补打任务')
    reprintOpen.value = false
    await load(page.value)
  } finally {
    acting.value = false
  }
}

onMounted(() => load(0))
</script>

<style scoped>
.page-wide { max-width: 1300px; margin: 0 auto; }
.est { font-style: normal; color: #9a6100; margin-left: 2px; }
</style>
