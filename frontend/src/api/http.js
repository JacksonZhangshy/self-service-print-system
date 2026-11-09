import axios from 'axios'
import { ElMessage } from 'element-plus'

// 统一 axios 实例：用户端 token 与管理端 token 分开存
const http = axios.create({ baseURL: '/api', timeout: 30000 })

http.interceptors.request.use((config) => {
  const url = config.url || ''
  const isAdminApi = url.startsWith('/admin') && !url.startsWith('/admin/login')
  const token = isAdminApi ? localStorage.getItem('adminToken') : localStorage.getItem('userToken')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

let redirecting = false

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body.code !== 'undefined') {
      if (body.code === 0) return body.data
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(new Error(body.msg || '请求失败'))
    }
    return body
  },
  (err) => {
    const status = err.response?.status
    const msg = err.response?.data?.msg || err.message || '网络错误'
    if (status === 401 && !redirecting) {
      redirecting = true
      const isAdmin = (err.config?.url || '').startsWith('/admin')
      localStorage.removeItem(isAdmin ? 'adminToken' : 'userToken')
      ElMessage.warning('登录已失效，请重新登录')
      setTimeout(() => {
        window.location.href = isAdmin ? '/admin/login' : '/user/login'
        redirecting = false
      }, 600)
    } else if (status !== 401) {
      ElMessage.error(msg)
    }
    return Promise.reject(err)
  }
)

export default http
