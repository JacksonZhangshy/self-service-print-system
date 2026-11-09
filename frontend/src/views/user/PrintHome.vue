<template>
  <div class="page">
    <div class="card">
      <h2>1. 上传文件</h2>
      <p class="sub">支持 Word / Excel / PPT / PDF / 图片 / TXT，单个文件最大 {{ maxMb }} MB</p>

      <el-upload drag :auto-upload="true" :http-request="doUpload" :show-file-list="false"
        :accept="accept" :disabled="uploading">
        <div style="padding: 18px">
          <div style="font-size: 15px">{{ uploading ? '上传处理中…' : '点击或拖拽文件到此处' }}</div>
          <div class="sub" style="margin: 6px 0 0">
            Office 文档会在服务端转换为 PDF，确保「看到多少页就收多少页的钱」
          </div>
        </div>
      </el-upload>

      <el-progress v-if="uploading" :percentage="progress" :stroke-width="4" style="margin-top: 14px" />

      <div v-if="fileInfo" style="margin-top: 18px">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="文件名">{{ fileInfo.fileName }}</el-descriptions-item>
          <el-descriptions-item label="页数">{{ fileInfo.pageCount }} 页</el-descriptions-item>
          <el-descriptions-item label="大小">{{ (fileInfo.sizeBytes / 1024 / 1024).toFixed(2) }} MB</el-descriptions-item>
        </el-descriptions>
        <div class="row" style="margin-top: 12px; align-items: center">
          <el-button @click="preview">预览打印效果</el-button>
          <span v-if="fileInfo.pageSource === 'ESTIMATE'" style="color: #b26a00; font-size: 13px">
            页数为估算值（服务端未启用文档转换），请预览确认后再下单
          </span>
        </div>
      </div>
    </div>

    <div class="card">
      <h2>2. 打印参数</h2>
      <p class="sub">金额由服务端按当前收费标准计算</p>

      <el-form label-width="88px" style="max-width: 560px">
        <el-form-item label="份数">
          <el-input-number v-model="form.copies" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="颜色">
          <el-radio-group v-model="form.colorMode">
            <el-radio-button value="BW">黑白 ¥{{ prices.bwA4 }}/页</el-radio-button>
            <el-radio-button value="COLOR">彩色 ¥{{ prices.colorA4 }}/页</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="单/双面">
          <el-radio-group v-model="form.duplex">
            <el-radio-button value="SINGLE">单面</el-radio-button>
            <el-radio-button value="DUPLEX">双面（×{{ prices.duplexDiscount }}）</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="纸张">
          <el-radio-group v-model="form.paperSize">
            <el-radio-button value="A4">A4</el-radio-button>
            <el-radio-button value="A3">A3</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <div style="display: flex; align-items: baseline; gap: 10px; margin: 8px 0 18px">
        <span class="sub" style="margin: 0">预计金额</span>
        <strong style="font-size: 26px; font-weight: 600" class="mono">¥{{ estimate }}</strong>
        <span class="sub" style="margin: 0" v-if="minChargeHint">（不足 ¥{{ prices.minCharge }} 按最低消费计）</span>
      </div>

      <el-button type="primary" size="large" :disabled="!fileInfo || submitting" :loading="submitting"
        @click="submitOrder">
        提交订单并去支付
      </el-button>
      <span v-if="terminalId" class="sub" style="margin-left: 12px">本机终端：{{ terminalId }}</span>
    </div>

    <el-dialog v-model="previewVisible" title="打印效果预览" width="820px" destroy-on-close>
      <iframe v-if="previewUrl" :src="previewUrl" style="width: 100%; height: 68vh; border: 0"></iframe>
      <div v-else style="padding: 40px; text-align: center; color: #6e6e73">预览加载中…</div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../../api/http'
import { userApi } from '../../api'
import { getTerminalId } from '../../terminal'

const router = useRouter()
const maxMb = 100
const accept = '.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.png,.jpg,.jpeg,.gif,.bmp,.webp'
const fileInfo = ref(null)
const uploading = ref(false)
const progress = ref(0)
const submitting = ref(false)
const previewVisible = ref(false)
const previewUrl = ref('')
const terminalId = ref(getTerminalId())
const prices = reactive({ bwA4: '0.30', bwA3: '0.60', colorA4: '1.00', colorA3: '2.00', duplexDiscount: '0.90', minCharge: '0.30' })
const form = reactive({ copies: 1, colorMode: 'BW', duplex: 'SINGLE', paperSize: 'A4' })

const estimate = computed(() => {
  if (!fileInfo.value) return '0.00'
  const unit = form.colorMode === 'COLOR'
    ? (form.paperSize === 'A3' ? Number(prices.colorA3) : Number(prices.colorA4))
    : (form.paperSize === 'A3' ? Number(prices.bwA3) : Number(prices.bwA4))
  let total = unit * fileInfo.value.pageCount * form.copies
  if (form.duplex === 'DUPLEX') total *= Number(prices.duplexDiscount)
  const min = Number(prices.minCharge || 0)
  if (total < min) total = min
  return total.toFixed(2)
})

const minChargeHint = computed(() => {
  if (!fileInfo.value) return false
  const unit = form.colorMode === 'COLOR'
    ? (form.paperSize === 'A3' ? Number(prices.colorA3) : Number(prices.colorA4))
    : (form.paperSize === 'A3' ? Number(prices.bwA3) : Number(prices.bwA4))
  let total = unit * fileInfo.value.pageCount * form.copies
  if (form.duplex === 'DUPLEX') total *= Number(prices.duplexDiscount)
  return total < Number(prices.minCharge || 0)
})

async function doUpload({ file }) {
  uploading.value = true
  progress.value = 0
  const fd = new FormData()
  fd.append('file', file)
  try {
    fileInfo.value = await userApi.upload(fd, (e) => {
      if (e.total) progress.value = Math.min(95, Math.round((e.loaded / e.total) * 100))
    })
    progress.value = 100
    ElMessage.success(`上传成功，共 ${fileInfo.value.pageCount} 页`)
  } catch (e) {
    fileInfo.value = null
  } finally {
    uploading.value = false
  }
}

async function preview() {
  if (!fileInfo.value) return
  previewVisible.value = true
  previewUrl.value = ''
  try {
    const blob = await http.get(`/files/${fileInfo.value.fileId}/preview`, { responseType: 'blob' })
    previewUrl.value = URL.createObjectURL(blob)
  } catch (e) {
    previewVisible.value = false
    ElMessage.error('预览加载失败，请重试')
  }
}

async function submitOrder() {
  if (!fileInfo.value) return
  submitting.value = true
  try {
    const order = await userApi.createOrder({
      fileId: fileInfo.value.fileId,
      copies: form.copies,
      colorMode: form.colorMode,
      duplex: form.duplex,
      paperSize: form.paperSize,
      terminalId: getTerminalId() || null
    })
    router.push(`/user/pay/${order.orderNo}`)
  } catch (e) {
    // 已提示
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  try {
    Object.assign(prices, await userApi.prices())
  } catch (e) {
    // 已提示
  }
})

onUnmounted(() => {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
})
</script>
