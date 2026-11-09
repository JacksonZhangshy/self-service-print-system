<template>
  <div class="page">
    <div class="card">
      <h2>经营看板</h2>
      <p class="sub">口径：已支付订单（按支付时间归档），净收入 = 收款金额 − 已成功退款金额</p>

      <div class="metrics">
        <div class="metric">
          <div class="k">今日收款</div>
          <div class="v">¥{{ money(summary.todayAmount) }}</div>
          <div class="x">净 ¥{{ money(summary.todayNet) }} · {{ summary.todayOrders || 0 }} 单</div>
        </div>
        <div class="metric">
          <div class="k">本月收款</div>
          <div class="v">¥{{ money(summary.monthAmount) }}</div>
          <div class="x">净 ¥{{ money(summary.monthNet) }} · {{ summary.monthOrders || 0 }} 单</div>
        </div>
        <div class="metric">
          <div class="k">今日退款</div>
          <div class="v">¥{{ money(summary.todayRefunded) }}</div>
          <div class="x">含打印失败自动退款</div>
        </div>
        <div class="metric">
          <div class="k">本月退款</div>
          <div class="v">¥{{ money(summary.monthRefunded) }}</div>
          <div class="x">退款率 {{ refundRate }}%</div>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="toolbar">
        <h2 style="margin: 0">近 30 天趋势</h2>
        <div class="spacer"></div>
        <el-radio-group v-model="range" size="small" @change="loadTrend">
          <el-radio-button :value="7">7 天</el-radio-button>
          <el-radio-button :value="30">30 天</el-radio-button>
          <el-radio-button :value="90">90 天</el-radio-button>
        </el-radio-group>
      </div>
      <div ref="dailyEl" class="chart"></div>
    </div>

    <div class="card">
      <h2>近 {{ months }} 个月</h2>
      <p class="sub">月度收款与净收入对比</p>
      <div ref="monthlyEl" class="chart"></div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
// 按需引入 echarts，避免把整个 echarts（约 1MB）打进看板 chunk
import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { adminApi } from '../../api'
import { money } from '../../utils/status'

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const summary = ref({})
const range = ref(30)
const months = ref(12)
const dailyEl = ref(null)
const monthlyEl = ref(null)
let dailyChart = null
let monthlyChart = null

const refundRate = computed(() => {
  const amt = Number(summary.value.monthAmount || 0)
  if (!amt) return '0.0'
  return (((Number(summary.value.monthRefunded || 0) / amt) * 100) || 0).toFixed(1)
})

const baseOption = (labels, series) => ({
  grid: { left: 48, right: 16, top: 28, bottom: 34 },
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'line', lineStyle: { color: '#d2d2d7' } },
    backgroundColor: '#fff',
    borderColor: '#e6e6ea',
    textStyle: { color: '#1d1d1f', fontSize: 12 }
  },
  legend: { right: 0, top: 0, icon: 'roundRect', itemWidth: 8, itemHeight: 8, textStyle: { color: '#6e6e73', fontSize: 12 } },
  xAxis: {
    type: 'category',
    data: labels,
    boundaryGap: false,
    axisLine: { lineStyle: { color: '#e6e6ea' } },
    axisTick: { show: false },
    axisLabel: { color: '#6e6e73', fontSize: 11 }
  },
  yAxis: {
    type: 'value',
    axisLabel: { color: '#6e6e73', fontSize: 11, formatter: (v) => `¥${v}` },
    splitLine: { lineStyle: { color: '#f0f0f3' } }
  },
  series
})

function shortLabel(s, isMonth) {
  if (!s) return ''
  return isMonth ? s.slice(2) : s.slice(5)
}

async function loadTrend() {
  const list = await adminApi.statsDaily(range.value)
  if (!dailyChart) dailyChart = echarts.init(dailyEl.value)
  dailyChart.setOption(
    baseOption(
      list.map((b) => shortLabel(b.label, false)),
      [
        {
          name: '收款',
          type: 'line',
          smooth: 0.25,
          symbol: 'none',
          lineStyle: { width: 2, color: '#0071e3' },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(0,113,227,.16)' },
              { offset: 1, color: 'rgba(0,113,227,0)' }
            ])
          },
          data: list.map((b) => Number(b.amount || 0))
        },
        {
          name: '净收入',
          type: 'line',
          smooth: 0.25,
          symbol: 'none',
          lineStyle: { width: 2, color: '#34a853', type: 'dashed' },
          data: list.map((b) => Number(b.net || 0))
        }
      ]
    ),
    true
  )
}

async function loadMonthly() {
  const list = await adminApi.statsMonthly(months.value)
  if (!monthlyChart) monthlyChart = echarts.init(monthlyEl.value)
  monthlyChart.setOption(
    baseOption(
      list.map((b) => shortLabel(b.label, true)),
      [
        {
          name: '收款',
          type: 'bar',
          barMaxWidth: 18,
          itemStyle: { color: '#0071e3', borderRadius: [4, 4, 0, 0] },
          data: list.map((b) => Number(b.amount || 0))
        },
        {
          name: '退款',
          type: 'bar',
          barMaxWidth: 18,
          itemStyle: { color: '#e6e6ea', borderRadius: [4, 4, 0, 0] },
          data: list.map((b) => Number(b.refunded || 0))
        }
      ]
    ),
    true
  )
}

function resize() {
  dailyChart?.resize()
  monthlyChart?.resize()
}

onMounted(async () => {
  summary.value = await adminApi.statsSummary()
  await Promise.all([loadTrend(), loadMonthly()])
  window.addEventListener('resize', resize)
})

onUnmounted(() => {
  window.removeEventListener('resize', resize)
  dailyChart?.dispose()
  monthlyChart?.dispose()
})
</script>

<style scoped>
.metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
@media (max-width: 780px) { .metrics { grid-template-columns: repeat(2, 1fr); } }
.metric { border: 1px solid var(--line); border-radius: 12px; padding: 14px 16px; }
.metric .k { font-size: 12.5px; color: var(--muted); }
.metric .v { font-size: 26px; font-weight: 600; letter-spacing: -.03em; margin: 6px 0 2px; font-variant-numeric: tabular-nums; }
.metric .x { font-size: 12px; color: var(--muted); }
.chart { width: 100%; height: 300px; }
</style>
