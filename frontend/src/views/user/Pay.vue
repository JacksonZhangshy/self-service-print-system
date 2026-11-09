<template>
  <div class="page" style="max-width: 560px">
    <div class="card" v-if="order">
      <h2>扫码支付</h2>
      <p class="sub">
        {{ order.fileName }} · {{ order.pageCount }} 页 × {{ order.copies }} 份 ·
        {{ order.colorMode === 'COLOR' ? '彩色' : '黑白' }}/{{ order.duplex === 'DUPLEX' ? '双面' : '单面' }}/{{ order.paperSize }}
      </p>

      <div style="display: flex; align-items: baseline; gap: 10px; margin-bottom: 6px">
        <span class="sub" style="margin: 0">应付金额</span>
        <strong class="mono" style="font-size: 30px; font-weight: 600">¥{{ money(order.amount) }}</strong>
      </div>
      <p class="sub mono" style="margin-top: 0">
        订单号 {{ order.orderNo }}
        <span v-if="paying"> · 剩余 {{ mmss }} 自动关闭</span>
      </p>

      <!-- 支付成功 -->
      <div v-if="paid" style="text-align: center; padding: 18px 0">
        <el-result :icon="printed ? 'success' : 'success'"
          :title="printed ? '打印完成，请取件' : '支付成功'"
          :sub-title="printed ? '如未出纸请联系工作人员' : '已生成打印任务，打印机将自动出纸'">
          <template #extra>
            <el-button @click="$router.push('/user/orders')">查看我的订单</el-button>
            <el-button type="primary" @click="$router.push('/user/print')">再打印一份</el-button>
          </template>
        </el-result>
      </div>

      <!-- 已关闭 -->
      <div v-else-if="closed" style="text-align: center; padding: 18px 0">
        <el-result icon="warning" title="订单已关闭" :sub-title="closedReason">
          <template #extra>
            <el-button type="primary" @click="$router.push('/user/print')">重新下单</el-button>
          </template>
        </el-result>
      </div>

      <!-- 待支付 -->
      <div v-else>
        <!-- 未出二维码时可选渠道；出码后渠道锁定，改成一句话说明，避免一个禁用的选择器让人误以为可以改 -->
        <el-radio-group v-if="!qrUrl" v-model="method" style="margin: 10px 0 16px">
          <el-radio-button value="ALIPAY">支付宝</el-radio-button>
          <el-radio-button value="WECHAT">微信支付</el-radio-button>
        </el-radio-group>
        <p v-else class="sub" style="margin: 0 0 12px">
          支付方式：<b>{{ PAY_METHOD_LABEL[method] }}</b>（重新选择请关闭订单后重新下单）
        </p>

        <div v-if="qrUrl" style="text-align: center">
          <img :src="qrUrl" alt="支付二维码" style="width: 230px; height: 230px" />
          <p class="sub" style="margin: 10px 0 0">
            请使用{{ method === 'ALIPAY' ? '支付宝' : '微信' }}扫码支付，支付后本页自动刷新
          </p>
          <el-alert v-if="payMode === 'MOCK'" type="warning" :closable="false" style="margin-top: 12px; text-align: left"
            title="当前为模拟支付：扫码打开的是本系统模拟收银台，不会产生真实扣款" />
          <div class="row" style="justify-content: center; margin-top: 14px">
            <el-button v-if="payMode === 'MOCK'" type="primary" plain @click="openMock">打开模拟收银台</el-button>
            <el-button @click="cancelOrder">取消订单</el-button>
          </div>
        </div>

        <div v-else style="text-align: center; padding: 24px 0">
          <el-button type="primary" size="large" :loading="starting" @click="startPay">生成支付二维码</el-button>
          <p class="sub" style="margin-top: 12px">二维码有效期 {{ expireMinutes }} 分钟，过期自动关闭订单</p>
        </div>
      </div>
    </div>

    <div class="card" v-else style="text-align: center; color: #6e6e73">加载中…</div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import QRCode from 'qrcode'
import { userApi } from '../../api'
import { PAY_METHOD_LABEL, money } from '../../utils/status'

const route = useRoute()
const router = useRouter()
const orderNo = route.params.orderNo
const order = ref(null)
const method = ref('ALIPAY')
const qrUrl = ref('')
const qrContent = ref('')
const payMode = ref('')
const paid = ref(false)
const printed = ref(false)
const closed = ref(false)
const closedReason = ref('')
const starting = ref(false)
const expireMinutes = ref(10)
const secondsLeft = ref(0)
let timer = null
let countdownTimer = null

const paying = computed(() => !!qrUrl.value && secondsLeft.value > 0)
const mmss = computed(() => {
  const s = Math.max(0, secondsLeft.value)
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
})

function applyStatus(status) {
  if (status === 'PRINTED') {
    paid.value = true
    printed.value = true
    stopTimers()
  } else if (['PAID', 'PRINTING'].includes(status)) {
    paid.value = true
    stopTimers()
  } else if (['EXPIRED', 'CANCELLED'].includes(status)) {
    closed.value = true
    closedReason.value = status === 'EXPIRED' ? '支付超时，订单已自动关闭' : '订单已取消'
    stopTimers()
  } else if (status === 'REFUNDED') {
    closed.value = true
    closedReason.value = '订单已退款'
    stopTimers()
  }
}

function stopTimers() {
  if (timer) clearInterval(timer)
  if (countdownTimer) clearInterval(countdownTimer)
  timer = null
  countdownTimer = null
}

async function startPay() {
  starting.value = true
  try {
    const r = await userApi.startPay(orderNo, method.value)
    payMode.value = r.payMode
    qrContent.value = r.qrContent
    qrUrl.value = await QRCode.toDataURL(r.qrContent, { width: 460, margin: 1 })
    if (r.expireAt) {
      const left = Math.max(0, Math.floor((new Date(r.expireAt.replace('T', ' ')) - new Date()) / 1000))
      secondsLeft.value = left || 600
    } else {
      secondsLeft.value = 600
    }
    startCountdown()
    startPolling()
  } catch (e) {
    // 已提示
  } finally {
    starting.value = false
  }
}

function startCountdown() {
  if (countdownTimer) clearInterval(countdownTimer)
  countdownTimer = setInterval(() => {
    secondsLeft.value -= 1
    if (secondsLeft.value <= 0) {
      closed.value = true
      closedReason.value = '支付超时，订单已自动关闭'
      stopTimers()
    }
  }, 1000)
}

function startPolling() {
  if (timer) clearInterval(timer)
  timer = setInterval(async () => {
    try {
      const r = await userApi.payStatus(orderNo)
      applyStatus(r.status)
    } catch (e) {
      // 轮询失败忽略，下一轮继续
    }
  }, 2500)
}

function openMock() {
  window.open(qrContent.value, '_blank')
}

async function cancelOrder() {
  try {
    await ElMessageBox.confirm('取消后需要重新下单，确定取消？', '取消订单', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await userApi.cancel(orderNo)
    closed.value = true
    closedReason.value = '订单已取消'
    stopTimers()
    ElMessage.success('订单已取消')
  } catch (e) {
    // 已提示
  }
}

onMounted(async () => {
  try {
    order.value = await userApi.orderDetail(orderNo)
    method.value = order.value.payMethod || 'ALIPAY'
    if (order.value.paySecondsLeft) secondsLeft.value = order.value.paySecondsLeft
    applyStatus(order.value.status)
    // 未支付则直接出码；已出过码的订单（PAYING）重新出码会拿到同渠道二维码
    if (!paid.value && !closed.value) {
      if (order.value.status === 'PAYING' && order.value.payMethod) {
        await startPay()
      }
    }
  } catch (e) {
    // 已提示
  }
})

onUnmounted(stopTimers)
</script>
