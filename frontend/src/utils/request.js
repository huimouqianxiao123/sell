import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import router from '../router'

// Create axios instance
const service = axios.create({
  baseURL: '/api', // Set base URL to /api, which matches the vite proxy
  timeout: 5000 // Request timeout
})

const rawService = axios.create({
  baseURL: '/api',
  timeout: 5000
})

// Request interceptor
service.interceptors.request.use(
  config => {
    // Get token from localStorage
    const token = localStorage.getItem('token')
    if (token) {
      // Set Authorization header
      // Backend expects 'Authorization' header
      config.headers['Authorization'] = token
    }
    return config
  },
  error => {
    console.error(error)
    return Promise.reject(error)
  }
)

rawService.interceptors.request.use(
  config => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers['Authorization'] = token
    }
    return config
  },
  error => {
    console.error(error)
    return Promise.reject(error)
  }
)

// Response interceptor
service.interceptors.response.use(
  response => {
    const res = response.data

    // If the custom code is not 200, it is judged as an error.
    // Note: The backend might return HTTP 200 with business code != 200
    // OR it might return HTTP 401/500 etc. which are caught in the error block below.
    // Based on R.java, SUCCESS is 200.
    if (res.code !== 200) {
      ElMessage({
        message: res.msg || res.message || 'Error',
        type: 'error',
        duration: 5 * 1000
      })
      return Promise.reject(new Error(res.msg || res.message || 'Error'))
    } else {
      return res
    }
  },
  error => {
    console.error('err' + error) // for debug
    const { response } = error

    if (response) {
      // Handle HTTP status codes
      if (response.status === 401) {
        // Token expired or not logged in
        ElMessageBox.confirm(
          '登录状态已过期，您可以继续留在该页面，或者重新登录',
          '系统提示',
          {
            confirmButtonText: '重新登录',
            cancelButtonText: '取消',
            type: 'warning'
          }
        ).then(() => {
          localStorage.removeItem('token')
          localStorage.removeItem('user')
          router.push('/login')
        }).catch(() => {
          // Stay on page
        })
      } else {
        ElMessage({
          message: response.data.message || response.data.msg || error.message,
          type: 'error',
          duration: 5 * 1000
        })
      }
    } else {
      ElMessage({
        message: error.message,
        type: 'error',
        duration: 5 * 1000
      })
    }
    return Promise.reject(error)
  }
)

export default service

export const productApi = {
  list: () => service.get('/product/list'),
  page: (params) => service.get('/product/page', { params }),
  getById: (id) => service.get(`/product/${id}`),
  save: (payload) => service.post('/product/save', payload),
  update: (payload) => service.post('/product/update', payload),
  removeById: (id) => service.delete(`/product/${id}`)
}

export const orderApi = {
  create: (payload) => service.post('/order/create', payload),
  list: () => service.get('/order/list'),
  detail: (orderId) => service.get(`/order/detail/${orderId}`),
  cancel: (orderId) => service.post('/order/cancel', null, { params: { orderId } }),
  confirmReceive: (orderId) => service.post('/order/confirmReceive', null, { params: { orderId } })
}

export const shoppingCartApi = {
  add: (productId, count) => service.post('/shoppingCart/add', null, { params: { productId, count } }),
  update: (productId, count) => service.post('/shoppingCart/update', null, { params: { productId, count } }),
  remove: (productIds) => service.post('/shoppingCart/delete', productIds),
  list: () => service.post('/shoppingCart/list')
}

export const fileApi = {
  upload: (formData) => rawService.post('/file/file', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
}

export const seckillApi = {
  list: () => service.get('/miaoshalist'),
  start: (id) => service.post('/startSeckill', null, { params: { id } }),
  result: () => service.get('/result'),
  add: (payload) => service.post('/addSeckillProduct', payload)
}

export const alipayApi = {
  pay: (orderNo, amount) => service.post('/alipay/pay', null, { params: { orderNo, amount } }),
  mockPay: (orderNo) => service.post('/alipay/mock-pay', null, { params: { orderNo } })
}
