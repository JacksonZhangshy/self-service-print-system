import http from './http'

export const authApi = {
  sendCode: (phone) => http.post('/auth/send-code', { phone }),
  login: (phone, code) => http.post('/auth/login', { phone, code }),
  logout: () => http.post('/auth/logout'),
  me: () => http.get('/auth/me')
}

export const userApi = {
  upload: (formData, onProgress) =>
    http.post('/files/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 300000,
      onUploadProgress: onProgress
    }),
  previewUrl: (fileId) => `/api/files/${fileId}/preview`,
  prices: () => http.get('/prices'),
  createOrder: (payload) => http.post('/orders', payload),
  startPay: (orderNo, payMethod) => http.post(`/orders/${orderNo}/pay`, { payMethod }),
  payStatus: (orderNo) => http.get(`/orders/${orderNo}/pay-status`),
  orderDetail: (orderNo) => http.get(`/orders/${orderNo}`),
  cancel: (orderNo) => http.post(`/orders/${orderNo}/cancel`),
  myOrders: (page = 0, size = 10) => http.get('/orders/my', { params: { page, size } })
}

export const adminApi = {
  login: (username, password) => http.post('/admin/login', { username, password }),
  logout: () => http.post('/admin/logout'),
  changePassword: (oldPassword, newPassword) => http.post('/admin/password', { oldPassword, newPassword }),
  getPrices: () => http.get('/admin/prices'),
  updatePrices: (payload) => http.put('/admin/prices', payload),
  orders: (params) => http.get('/admin/orders', { params }),
  tasks: (params) => http.get('/admin/tasks', { params }),
  refund: (orderNo, reason) => http.post(`/admin/orders/${orderNo}/refund`, { reason }),
  refundsOf: (orderNo) => http.get(`/admin/orders/${orderNo}/refunds`),
  reprint: (orderNo, reason) => http.post(`/admin/orders/${orderNo}/reprint`, { reason }),
  users: (params) => http.get('/admin/users', { params }),
  updateUserStatus: (userId, status) => http.post(`/admin/users/${userId}/status`, { status }),
  audit: (params) => http.get('/admin/audit', { params }),
  statsSummary: () => http.get('/admin/stats/summary'),
  statsDaily: (days = 30) => http.get('/admin/stats/daily', { params: { days } }),
  statsMonthly: (months = 12) => http.get('/admin/stats/monthly', { params: { months } })
}
