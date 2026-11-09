<template>
  <div class="page">
    <div class="card">
      <h2>收费标准</h2>
      <p class="sub">
        总价 = 单价（彩色模式 × 纸张）× 页数 × 份数 ×（双面 ? 双面系数 : 1），再与最低消费取大。
        修改后对**新订单**立即生效，已下单订单金额不变。
      </p>

      <el-form label-position="top" style="max-width: 620px" v-loading="loading">
        <div class="grid2">
          <el-form-item label="黑白 A4（元/页）">
            <el-input-number v-model="form.bwA4" :min="0.01" :max="100" :step="0.05" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="黑白 A3（元/页）">
            <el-input-number v-model="form.bwA3" :min="0.01" :max="100" :step="0.05" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="彩色 A4（元/页）">
            <el-input-number v-model="form.colorA4" :min="0.01" :max="100" :step="0.05" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="彩色 A3（元/页）">
            <el-input-number v-model="form.colorA3" :min="0.01" :max="100" :step="0.05" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="双面系数（0.10 ~ 1.00）">
            <el-input-number v-model="form.duplexDiscount" :min="0.1" :max="1" :step="0.01" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="最低消费（元/单）">
            <el-input-number v-model="form.minCharge" :min="0.01" :max="100" :step="0.1" :precision="2" style="width: 100%" />
          </el-form-item>
        </div>

        <div class="hint" style="margin: 4px 0 16px">
          参考：一张 10 页黑白 A4 单面 = ¥{{ money(10 * form.bwA4) }}；
          10 页彩色 A4 双面 = ¥{{ money(10 * form.colorA4 * form.duplexDiscount) }}
        </div>

        <div class="toolbar" style="margin: 0">
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button @click="load">重置</el-button>
          <div class="spacer"></div>
          <span class="hint" v-if="form.updatedAt">最近更新 {{ dt(form.updatedAt) }}</span>
        </div>
      </el-form>
    </div>

    <div class="card">
      <h2>修改管理口令</h2>
      <p class="sub">口令以 BCrypt 存储，修改后建议重新登录</p>
      <el-form label-position="top" style="max-width: 360px">
        <el-form-item label="原口令">
          <el-input v-model="pwd.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新口令（至少 8 位）">
          <el-input v-model="pwd.newPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认新口令">
          <el-input v-model="pwd.confirm" type="password" show-password />
        </el-form-item>
        <el-button :loading="changing" @click="changePassword">提交</el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '../../api'
import { dt, money } from '../../utils/status'

const loading = ref(false)
const saving = ref(false)
const changing = ref(false)
const form = reactive({
  bwA4: 0.3,
  bwA3: 0.6,
  colorA4: 1,
  colorA3: 2,
  duplexDiscount: 0.9,
  minCharge: 0.3,
  updatedAt: ''
})
const pwd = reactive({ oldPassword: '', newPassword: '', confirm: '' })

async function load() {
  loading.value = true
  try {
    const r = await adminApi.getPrices()
    Object.assign(form, {
      bwA4: Number(r.bwA4),
      bwA3: Number(r.bwA3),
      colorA4: Number(r.colorA4),
      colorA3: Number(r.colorA3),
      duplexDiscount: Number(r.duplexDiscount),
      minCharge: Number(r.minCharge),
      updatedAt: r.updatedAt
    })
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const r = await adminApi.updatePrices({
      bwA4: form.bwA4,
      bwA3: form.bwA3,
      colorA4: form.colorA4,
      colorA3: form.colorA3,
      duplexDiscount: form.duplexDiscount,
      minCharge: form.minCharge
    })
    ElMessage.success('已保存')
    form.updatedAt = r.updatedAt
  } finally {
    saving.value = false
  }
}

async function changePassword() {
  if (pwd.newPassword.length < 8) return ElMessage.warning('新口令至少 8 位')
  if (pwd.newPassword !== pwd.confirm) return ElMessage.warning('两次输入不一致')
  changing.value = true
  try {
    await adminApi.changePassword(pwd.oldPassword, pwd.newPassword)
    ElMessage.success('口令已修改，请重新登录')
    localStorage.removeItem('adminToken')
    setTimeout(() => (window.location.href = '/admin/login'), 700)
  } finally {
    changing.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 18px; }
@media (max-width: 640px) { .grid2 { grid-template-columns: 1fr; } }
</style>
