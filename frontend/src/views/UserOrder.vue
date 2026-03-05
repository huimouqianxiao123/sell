<template>
  <div class="order-page">
    <!-- 页面标题 -->
    <div class="page-header">
      <h2>我的订单</h2>
      <p>查看和管理您的订单</p>
    </div>

    <!-- 支付状态提示 -->
    <el-alert
      v-if="paymentStatus"
      :title="paymentStatus.title"
      :type="paymentStatus.type"
      :description="paymentStatus.description"
      show-icon
      closable
      class="payment-alert"
      @close="clearPaymentStatus"
    />

    <!-- 订单查询中提示 -->
    <el-alert
      v-if="isCheckingPayment"
      title="正在确认支付结果..."
      type="info"
      :description="`订单号: ${checkingOrderNo}，正在等待支付宝确认支付结果，请稍候...`"
      show-icon
      class="payment-alert"
    />

    <!-- 订单列表 -->
    <div class="order-list">
      <el-card v-for="order in orderList" :key="order.id" class="order-card">
        <div class="order-header">
          <span class="order-no">订单号: {{ order.orderNo }}</span>
          <el-tag :type="getStatusType(order.status)">
            {{ getStatusText(order.status) }}
          </el-tag>
        </div>

        <div class="order-info">
          <p><strong>订单金额:</strong> ¥{{ order.totalAmount }}</p>
          <p><strong>创建时间:</strong> {{ formatDate(order.createTime) }}</p>
          <p v-if="order.payTime"><strong>支付时间:</strong> {{ formatDate(order.payTime) }}</p>
        </div>

        <div class="order-actions">
          <el-button
            v-if="order.status === 0"
            type="primary"
            size="small"
            @click="handlePay(order)"
          >
            立即支付
          </el-button>
          <el-button
            v-if="order.status === 1"
            type="success"
            size="small"
            @click="viewOrderDetail(order)"
          >
            查看详情
          </el-button>
          <el-button
            v-if="order.status === 0"
            type="danger"
            size="small"
            @click="handleCancel(order)"
          >
            取消订单
          </el-button>
        </div>
      </el-card>

      <!-- 空状态 -->
      <el-empty v-if="orderList.length === 0" description="暂无订单" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../utils/request'

// 路由实例
const route = useRoute()
const router = useRouter()

// 订单列表数据
const orderList = ref([])

// 支付状态提示
const paymentStatus = ref(null)

// 是否正在检查支付状态
const isCheckingPayment = ref(false)

// 正在检查的订单号
const checkingOrderNo = ref('')

// 轮询定时器
let checkTimer = null

/**
 * 获取订单列表
 */
const fetchOrderList = async () => {
  try {
    const res = await request.get('/order/list')
    if (res.code === 200) {
      orderList.value = res.data || []
    } else {
      ElMessage.error(res.msg || '获取订单列表失败')
    }
  } catch (error) {
    ElMessage.error('获取订单列表失败')
    console.error('获取订单列表失败:', error)
  }
}

/**
 * 查询单个订单状态
 * @param {string} orderNo - 订单号
 * @returns {Promise<Object|null>} 订单信息
 */
const fetchOrderStatus = async (orderNo) => {
  try {
    const res = await request.get('/order/detail', {
      params: { orderNo }
    })
    if (res.code === 200 && res.data) {
      return res.data
    }
    return null
  } catch (error) {
    console.error('查询订单状态失败:', error)
    return null
  }
}

/**
 * 轮询检查订单支付状态
 * @param {string} orderNo - 订单号
 * @param {number} maxAttempts - 最大尝试次数
 * @param {number} interval - 轮询间隔（毫秒）
 */
const pollOrderStatus = async (orderNo, maxAttempts = 10, interval = 2000) => {
  isCheckingPayment.value = true
  checkingOrderNo.value = orderNo

  let attempts = 0

  const checkStatus = async () => {
    attempts++
    console.log(`[支付状态检查] 第 ${attempts} 次查询订单: ${orderNo}`)

    const order = await fetchOrderStatus(orderNo)

    if (!order) {
      console.log('[支付状态检查] 订单不存在')
      if (attempts >= maxAttempts) {
        stopPolling()
        paymentStatus.value = {
          title: '支付状态查询超时',
          type: 'warning',
          description: `订单号: ${orderNo}，无法确认支付结果，请稍后刷新页面查看。`
        }
      }
      return
    }

    // 检查订单状态
    if (order.status === 1) {
      // 订单已支付
      stopPolling()
      paymentStatus.value = {
        title: '支付成功',
        type: 'success',
        description: `订单号: ${orderNo}，您的订单已支付成功！`
      }
      // 刷新订单列表
      await fetchOrderList()
    } else if (order.status === 0) {
      // 尝试调用 mock-pay 接口更新订单状态
      try {
        console.log('[支付状态检查] 调用 mock-pay 接口更新订单状态')
        const mockPayRes = await request.post('/alipay/mock-pay', null, {
          params: { orderNo: order.orderNo }
        })
        console.log('[支付状态检查] mock-pay 结果:', mockPayRes)
        
        // 如果 mock-pay 成功，停止轮询并显示成功
        if (mockPayRes.code === 200) {
          stopPolling()
          paymentStatus.value = {
            title: '支付成功',
            type: 'success',
            description: `订单号: ${orderNo}，您的订单已支付成功！`
          }
          await fetchOrderList()
          return
        }
      } catch (e) {
        console.error('[支付状态检查] mock-pay 调用失败:', e)
      }

      // 订单仍待支付，继续轮询
      if (attempts >= maxAttempts) {
        stopPolling()
        paymentStatus.value = {
          title: '支付确认中',
          type: 'warning',
          description: `订单号: ${orderNo}，支付宝确认可能存在延迟，请稍后刷新页面查看最新状态。`
        }
        // 刷新订单列表
        await fetchOrderList()
      }
    } else {
      // 其他状态（已取消等）
      stopPolling()
      await fetchOrderList()
    }
  }

  // 立即执行第一次查询
  await checkStatus()

  // 如果还需要继续轮询，设置定时器
  if (isCheckingPayment.value) {
    checkTimer = setInterval(checkStatus, interval)
  }
}

/**
 * 停止轮询
 */
const stopPolling = () => {
  isCheckingPayment.value = false
  checkingOrderNo.value = ''
  if (checkTimer) {
    clearInterval(checkTimer)
    checkTimer = null
  }
}

/**
 * 获取状态对应的标签类型
 * @param {number} status - 订单状态
 * @returns {string} 标签类型
 */
const getStatusType = (status) => {
  const statusMap = {
    0: 'warning',    // 待支付
    1: 'success',    // 已支付
    2: 'info',       // 已发货
    3: 'success',    // 已完成
    4: 'danger'      // 已取消
  }
  return statusMap[status] || 'info'
}

/**
 * 获取状态文本
 * @param {number} status - 订单状态
 * @returns {string} 状态文本
 */
const getStatusText = (status) => {
  const statusMap = {
    0: '待支付',
    1: '已支付',
    2: '已发货',
    3: '已完成',
    4: '已取消'
  }
  return statusMap[status] || '未知状态'
}

/**
 * 格式化日期
 * @param {string} dateStr - 日期字符串
 * @returns {string} 格式化后的日期
 */
const formatDate = (dateStr) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

/**
 * 处理支付
 * @param {Object} order - 订单对象
 */
const handlePay = async (order) => {
  try {
    const res = await request.post('/alipay/pay', null, {
      params: {
        orderNo: order.orderNo,
        amount: order.totalAmount
      }
    })
    if (res.code === 200) {
      // 创建临时表单并提交
      const div = document.createElement('div')
      div.innerHTML = res.data
      document.body.appendChild(div)
      const form = div.querySelector('form')
      if (form) {
        form.submit()
      }
      document.body.removeChild(div)
    } else {
      ElMessage.error(res.msg || '支付请求失败')
    }
  } catch (error) {
    ElMessage.error('支付请求失败')
    console.error('支付请求失败:', error)
  }
}

/**
 * 查看订单详情
 * @param {Object} order - 订单对象
 */
const viewOrderDetail = (order) => {
  ElMessage.info(`查看订单详情: ${order.orderNo}`)
  // 可以在这里添加跳转到订单详情页的逻辑
}

/**
 * 处理取消订单
 * @param {Object} order - 订单对象
 */
const handleCancel = async (order) => {
  try {
    await ElMessageBox.confirm('确定要取消该订单吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    // 调用取消订单接口
    const res = await request.post('/order/cancel', { orderId: order.id })
    if (res.code === 200) {
      ElMessage.success('订单已取消')
      fetchOrderList()
    } else {
      ElMessage.error(res.msg || '取消订单失败')
    }
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('取消订单失败')
      console.error('取消订单失败:', error)
    }
  }
}

/**
 * 清除支付状态提示
 */
const clearPaymentStatus = () => {
  paymentStatus.value = null
  // 清除URL中的参数
  router.replace({ path: '/user/order' })
}

/**
 * 处理支付成功回调
 * 在支付宝页面支付成功后，会重定向回来，此时需要更新订单状态
 * @param {string} orderNo - 订单号
 */
const handlePaymentSuccess = async (orderNo) => {
  console.log('[支付回调] 检测到支付成功，开始更新订单状态，订单号:', orderNo)
  isCheckingPayment.value = true
  checkingOrderNo.value = orderNo

  try {
    // 首先尝试调用 mock-pay 接口立即更新订单状态
    // 这是因为支付宝的异步通知可能有延迟，我们主动触发更新
    console.log('[支付回调] 调用 mock-pay 接口更新订单状态')
    const mockPayRes = await request.post('/alipay/mock-pay', null, {
      params: { orderNo }
    })
    console.log('[支付回调] mock-pay 响应:', mockPayRes)

    if (mockPayRes.code === 200) {
      // mock-pay 成功，订单状态已更新
      isCheckingPayment.value = false
      checkingOrderNo.value = ''
      paymentStatus.value = {
        title: '支付成功',
        type: 'success',
        description: `订单号: ${orderNo}，您的订单已支付成功！`
      }
      // 刷新订单列表
      await fetchOrderList()
      // 清除URL中的参数
      router.replace({ path: '/user/order' })
      return
    }
  } catch (error) {
    console.error('[支付回调] mock-pay 调用失败:', error)
  }

  // 如果 mock-pay 失败，可能是订单已经被支付宝异步通知更新了
  // 检查订单当前状态
  const order = await fetchOrderStatus(orderNo)
  if (order && order.status === 1) {
    // 订单已支付
    isCheckingPayment.value = false
    checkingOrderNo.value = ''
    paymentStatus.value = {
      title: '支付成功',
      type: 'success',
      description: `订单号: ${orderNo}，您的订单已支付成功！`
    }
    await fetchOrderList()
    router.replace({ path: '/user/order' })
    return
  }

  // 如果订单仍未支付，开始轮询等待支付宝异步通知
  console.log('[支付回调] 订单状态未更新，开始轮询等待')
  pollOrderStatus(orderNo)
}

// 监听路由参数变化（支付宝回调后会带参数跳转回来）
watch(
  () => route.query,
  (query) => {
    console.log('[路由变化] query:', query)
    
    // 支持两种情况：
    // 1. 通过后端 /return 跳转: ?status=success&orderNo=xxx
    // 2. 支付宝直接跳转: ?out_trade_no=xxx&trade_no=xxx&...
    
    const orderNo = query.orderNo || query.out_trade_no
    
    if (query.status === 'success' && orderNo) {
      // 后端跳转过来的格式
      handlePaymentSuccess(orderNo)
    } else if (query.out_trade_no && query.trade_no) {
      // 支付宝直接跳转过来的格式（表示支付成功）
      handlePaymentSuccess(query.out_trade_no)
    } else if (query.status === 'fail') {
      paymentStatus.value = {
        title: '支付失败',
        type: 'error',
        description: '支付过程中出现问题，请重新尝试支付。'
      }
    }
  },
  { immediate: true } // 立即执行一次，相当于 onMounted 时也检查
)

// 组件挂载时执行
onMounted(() => {
  fetchOrderList()
})

// 组件卸载时清理定时器
onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.order-page {
  padding: 20px;
}

.page-header {
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0 0 8px 0;
  font-size: 24px;
  color: #303133;
}

.page-header p {
  margin: 0;
  color: #909399;
  font-size: 14px;
}

.payment-alert {
  margin-bottom: 20px;
}

.order-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.order-card {
  border-radius: 8px;
}

.order-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 16px;
  border-bottom: 1px solid #ebeef5;
}

.order-no {
  font-size: 14px;
  color: #606266;
  font-weight: 500;
}

.order-info {
  margin-bottom: 16px;
}

.order-info p {
  margin: 8px 0;
  color: #606266;
  font-size: 14px;
}

.order-actions {
  display: flex;
  gap: 8px;
}
</style>
