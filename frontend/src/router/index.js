import { createRouter, createWebHistory } from 'vue-router'
import Login from '../views/Login.vue'
import Register from '../views/Register.vue'
import Layout from '../views/Layout.vue'
import Product from '../views/Product.vue'
import UserLayout from '../views/UserLayout.vue'
import UserProduct from '../views/UserProduct.vue'
import UserProductDetail from '../views/UserProductDetail.vue'
import UserOrder from '../views/UserOrder.vue'
import SeckillDetail from '../views/SeckillDetail.vue'

const routes = [
  {
    path: '/',
    redirect: '/login'
  },
  {
    path: '/login',
    name: 'Login',
    component: Login
  },
  {
    path: '/register',
    name: 'Register',
    component: Register
  },
  {
    path: '/admin',
    component: Layout,
    redirect: '/admin/product',
    meta: { requiresAuth: true, role: 'admin' },
    children: [
      {
        path: 'product',
        name: 'Product',
        component: Product
      }
    ]
  },
  {
    path: '/user',
    component: UserLayout,
    redirect: '/user/mall',
    meta: { requiresAuth: true, role: 'user' },
    children: [
      {
        path: 'mall',
        name: 'Mall',
        component: UserProduct
      },
      {
        path: 'product/:id',
        name: 'UserProductDetail',
        component: UserProductDetail
      },
      {
        path: 'seckill/:id',
        name: 'SeckillDetail',
        component: SeckillDetail
      },
      {
        path: 'order',
        name: 'UserOrder',
        component: UserOrder
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// Navigation guard
router.beforeEach((to, from, next) => {
  const publicPages = ['/login', '/register']
  const authRequired = !publicPages.includes(to.path)
  const userStr = localStorage.getItem('user')

  if (authRequired && !userStr) {
    return next('/login')
  }

  if (authRequired && userStr) {
    const user = JSON.parse(userStr)
    // 简单的权限检查
    if (to.meta.role && to.meta.role !== user.role) {
      // 如果尝试访问不匹配角色的页面，重定向到对应的首页
      if (user.role === 'admin') {
        return next('/admin/product')
      } else {
        return next('/user/mall')
      }
    }
  }

  next()
})

export default router
