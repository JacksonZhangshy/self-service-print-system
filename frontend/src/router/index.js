import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/user/print' },
  // ---------------- 用户端 ----------------
  { path: '/user/login', component: () => import('../views/user/Login.vue') },
  { path: '/user/print', component: () => import('../views/user/PrintHome.vue'), meta: { userAuth: true } },
  { path: '/user/pay/:orderNo', component: () => import('../views/user/Pay.vue'), meta: { userAuth: true } },
  { path: '/user/orders', component: () => import('../views/user/MyOrders.vue'), meta: { userAuth: true } },
  // ---------------- 管理端 ----------------
  { path: '/admin/login', component: () => import('../views/admin/AdminLogin.vue') },
  { path: '/admin/dashboard', component: () => import('../views/admin/Dashboard.vue'), meta: { adminAuth: true } },
  { path: '/admin/prices', component: () => import('../views/admin/Prices.vue'), meta: { adminAuth: true } },
  { path: '/admin/orders', component: () => import('../views/admin/AdminOrders.vue'), meta: { adminAuth: true } },
  { path: '/admin/tasks', component: () => import('../views/admin/AdminTasks.vue'), meta: { adminAuth: true } },
  { path: '/admin/users', component: () => import('../views/admin/AdminUsers.vue'), meta: { adminAuth: true } },
  { path: '/admin/audit', component: () => import('../views/admin/AuditLog.vue'), meta: { adminAuth: true } },
  { path: '/:pathMatch(.*)*', redirect: '/user/print' }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  if (to.meta.userAuth && !localStorage.getItem('userToken')) {
    return { path: '/user/login', query: to.query }
  }
  if (to.meta.adminAuth && !localStorage.getItem('adminToken')) {
    return { path: '/admin/login' }
  }
  return true
})

export default router
