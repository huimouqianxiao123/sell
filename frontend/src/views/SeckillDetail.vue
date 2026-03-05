<template>
  <div class="seckill-detail-container" v-loading="loading">
    <div class="seckill-detail-card" v-if="seckill">
      <!-- 左侧图片区域 -->
      <div class="left-section">
        <div class="image-box">
          <el-image :src="seckill.imageUrl" fit="cover" class="detail-image">
            <template #error>
              <div class="image-slot">
                <el-icon><Picture /></el-icon>
              </div>
            </template>
          </el-image>
          <!-- 状态标签 -->
          <div class="status-tag" :class="getStatusClass(seckill.status)">
            {{ getStatusText(seckill.status) }}
          </div>
        </div>
      </div>
      
      <!-- 右侧信息区域 -->
      <div class="right-section">
        <h1 class="product-title">{{ seckill.name }}</h1>
        <p class="product-desc">{{ seckill.description || '限时特惠，抢完即止！' }}</p>
        
        <!-- 价格区域 -->
        <div class="price-section">
          <div class="seckill-price-row">
            <span class="label">秒杀价</span>
            <div class="seckill-price">
              <span class="currency">￥</span>
              <span class="price">{{ seckill.seckillPrice }}</span>
            </div>
          </div>
          <div class="original-price-row" v-if="seckill.originalPrice">
            <span class="label">原价</span>
            <span class="original-price">￥{{ seckill.originalPrice }}</span>
          </div>
        </div>
        
        <!-- 倒计时区域 -->
        <div class="countdown-section">
          <div class="countdown-label">
            <el-icon><Timer /></el-icon>
            <span>{{ seckill.status === 0 ? '距开始' : '距结束' }}</span>
          </div>
          <div class="countdown-timer">
            <span class="time-block">{{ countdown.hours }}</span>
            <span class="separator">:</span>
            <span class="time-block">{{ countdown.minutes }}</span>
            <span class="separator">:</span>
            <span class="time-block">{{ countdown.seconds }}</span>
          </div>
        </div>
        
        <!-- 库存进度 -->
        <div class="stock-section">
          <div class="stock-header">
            <span>库存</span>
            <span>剩余 {{ seckill.seckillStock }} 件</span>
          </div>
          <el-progress 
            :percentage="stockPercentage" 
            :show-text="false" 
            color="#f56c6c"
            :stroke-width="12"
          />
          <div class="stock-text">
            {{ getStockText() }}
          </div>
        </div>
        
        <!-- 活动时间信息 -->
        <div class="time-info">
          <div class="time-row">
            <el-icon><Clock /></el-icon>
            <span>开始时间：{{ formatTime(seckill.startTime) }}</span>
          </div>
          <div class="time-row">
            <el-icon><Clock /></el-icon>
            <span>结束时间：{{ formatTime(seckill.endTime) }}</span>
          </div>
        </div>
        
        <!-- 操作按钮 -->
        <div class="action-section">
          <el-button 
            type="danger" 
            size="large" 
            class="seckill-btn"
            :disabled="!canSeckill"
            :loading="seckillLoading"
            @click="handleSeckill"
          >
            <template #icon v-if="!seckillLoading">
              <el-icon><Lightning /></el-icon>
            </template>
            {{ getSeckillBtnText() }}
          </el-button>
          <el-button 
            type="default" 
            size="large" 
            class="back-btn"
            @click="goBack"
          >
            返回商城
          </el-button>
        </div>
        
        <!-- 秒杀结果提示 -->
        <div class="result-section" v-if="seckillResult">
          <el-alert 
            :title="seckillResult.title" 
            :type="seckillResult.type" 
            :description="seckillResult.description"
            show-icon 
            :closable="false"
          />
        </div>
      </div>
    </div>
    
    <el-empty v-else-if="!loading" description="秒杀活动不存在或已结束">
      <el-button type="primary" @click="goBack">返回商城</el-button>
    </el-empty>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { seckillApi } from '../utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Picture, Timer, Clock, Lightning } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const seckillId = route.params.id

const loading = ref(false)
const seckillLoading = ref(false)
const seckill = ref(null)
const seckillResult = ref(null)
const countdown = reactive({ hours: '00', minutes: '00', seconds: '00' })
let timerInterval = null
let pollingInterval = null

// 计算库存百分比
const stockPercentage = computed(() => {
  if (!seckill.value) return 0
  const total = seckill.value.seckillStock + (seckill.value.soldCount || 0)
  if (total === 0) return 100
  return Math.round((seckill.value.soldCount || 0) / total * 100)
})

// 是否可以秒杀
const canSeckill = computed(() => {
  if (!seckill.value) return false
  return seckill.value.status === 1 && seckill.value.seckillStock > 0
})

// 获取秒杀活动详情
const fetchSeckillDetail = async () => {
  loading.value = true
  try {
    const res = await seckillApi.list()
    if (res.data) {
      // 从秒杀列表中找到对应的秒杀活动
      const found = res.data.find(item => item.id == seckillId)
      if (found) {
        seckill.value = found
        startCountdown()
      }
    }
  } catch (error) {
    console.error('Failed to fetch seckill detail', error)
  } finally {
    loading.value = false
  }
}

// 开始倒计时
const startCountdown = () => {
  if (timerInterval) clearInterval(timerInterval)
  
  const updateTimer = () => {
    if (!seckill.value) return
    
    const now = new Date().getTime()
    let targetTime
    
    if (seckill.value.status === 0) {
      // 未开始，倒计时到开始时间
      targetTime = new Date(seckill.value.startTime).getTime()
    } else if (seckill.value.status === 1) {
      // 进行中，倒计时到结束时间
      targetTime = new Date(seckill.value.endTime).getTime()
    } else {
      // 已结束或已售罄
      countdown.hours = '00'
      countdown.minutes = '00'
      countdown.seconds = '00'
      return
    }
    
    const diff = targetTime - now
    
    if (diff <= 0) {
      clearInterval(timerInterval)
      countdown.hours = '00'
      countdown.minutes = '00'
      countdown.seconds = '00'
      // 刷新数据
      fetchSeckillDetail()
      return
    }
    
    const h = Math.floor((diff / (1000 * 60 * 60)) % 24) + Math.floor(diff / (1000 * 60 * 60 * 24)) * 24
    const m = Math.floor((diff / (1000 * 60)) % 60)
    const s = Math.floor((diff / 1000) % 60)
    
    countdown.hours = h.toString().padStart(2, '0')
    countdown.minutes = m.toString().padStart(2, '0')
    countdown.seconds = s.toString().padStart(2, '0')
  }
  
  updateTimer()
  timerInterval = setInterval(updateTimer, 1000)
}

// 执行秒杀
const handleSeckill = async () => {
  try {
    await ElMessageBox.confirm(
      `确认立即抢购 ${seckill.value.name} 吗？秒杀价: ￥${seckill.value.seckillPrice}`,
      '秒杀确认',
      {
        confirmButtonText: '立即抢购',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch {
    return
  }
  
  seckillLoading.value = true
  seckillResult.value = null
  
  try {
    const res = await seckillApi.start(seckill.value.id)
    const msg = res.data
    
    if (msg && (msg.includes('失败') || msg.includes('错误'))) {
      seckillResult.value = {
        type: 'error',
        title: '抢购失败',
        description: msg
      }
      ElMessage.error(msg)
    } else if (msg && msg.includes('排队')) {
      seckillResult.value = {
        type: 'info',
        title: '排队中',
        description: '您的抢购请求正在处理中，请稍候...'
      }
      ElMessage.info('抢购请求已提交，正在排队处理...')
      // 开始轮询结果
      startPollingResult()
    } else {
      seckillResult.value = {
        type: 'success',
        title: '抢购成功',
        description: msg || '恭喜您，抢购成功！请前往订单页面查看。'
      }
      ElMessage.success('抢购成功！')
      // 刷新秒杀数据
      fetchSeckillDetail()
    }
  } catch (error) {
    seckillResult.value = {
      type: 'error',
      title: '抢购失败',
      description: error.message || '网络错误，请重试'
    }
  } finally {
    seckillLoading.value = false
  }
}

// 轮询秒杀结果
const startPollingResult = () => {
  if (pollingInterval) clearInterval(pollingInterval)
  
  let pollCount = 0
  const maxPolls = 10
  
  pollingInterval = setInterval(async () => {
    pollCount++
    
    try {
      const res = await seckillApi.result()
      if (res.data && res.data !== '') {
        clearInterval(pollingInterval)
        seckillResult.value = {
          type: 'success',
          title: '抢购成功',
          description: `订单号：${res.data}，请前往订单页面查看。`
        }
        ElMessage.success(`抢购成功！订单号：${res.data}`)
        fetchSeckillDetail()
      } else if (pollCount >= maxPolls) {
        clearInterval(pollingInterval)
        seckillResult.value = {
          type: 'warning',
          title: '结果待确认',
          description: '抢购结果正在处理中，请稍后在订单页面查看。'
        }
      }
    } catch (error) {
      console.error('Polling error:', error)
    }
  }, 1000)
}

// 格式化时间
const formatTime = (timeStr) => {
  if (!timeStr) return '-'
  return timeStr.replace('T', ' ')
}

// 获取状态文本
const getStatusText = (status) => {
  const statusMap = {
    0: '即将开始',
    1: '抢购中',
    2: '已结束',
    3: '已售罄'
  }
  return statusMap[status] || '未知'
}

// 获取状态样式类
const getStatusClass = (status) => {
  const classMap = {
    0: 'status-pending',
    1: 'status-active',
    2: 'status-ended',
    3: 'status-soldout'
  }
  return classMap[status] || ''
}

// 获取按钮文本
const getSeckillBtnText = () => {
  if (!seckill.value) return '立即秒杀'
  if (seckill.value.status === 0) return '即将开始'
  if (seckill.value.status === 1) return '立即秒杀'
  if (seckill.value.status === 2) return '已结束'
  if (seckill.value.status === 3 || seckill.value.seckillStock <= 0) return '已抢光'
  return '立即秒杀'
}

// 获取库存提示文本
const getStockText = () => {
  if (!seckill.value) return ''
  if (seckill.value.seckillStock <= 0) return '已抢光'
  if (seckill.value.seckillStock <= 5) return '库存紧张，抓紧抢购！'
  return '库存充足'
}

// 返回商城
const goBack = () => {
  router.push('/user/mall')
}

onMounted(() => {
  if (seckillId) {
    fetchSeckillDetail()
  } else {
    ElMessage.error('无效的秒杀活动')
    goBack()
  }
})

onUnmounted(() => {
  if (timerInterval) clearInterval(timerInterval)
  if (pollingInterval) clearInterval(pollingInterval)
})
</script>

<style scoped>
.seckill-detail-container {
  max-width: 1100px;
  margin: 40px auto;
  padding: 0 20px;
}

.seckill-detail-card {
  display: flex;
  background: linear-gradient(145deg, #fff 0%, #fafafa 100%);
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.08);
  min-height: 550px;
}

.left-section {
  width: 45%;
  background: linear-gradient(135deg, #ff6b6b 0%, #ee5a24 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 50px;
  position: relative;
}

.image-box {
  width: 100%;
  height: 0;
  padding-bottom: 100%;
  position: relative;
  background: #fff;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.2);
}

.detail-image {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
}

.image-slot {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
  background: #f5f7fa;
  color: #909399;
  font-size: 64px;
}

.status-tag {
  position: absolute;
  top: 16px;
  right: 16px;
  padding: 6px 16px;
  border-radius: 20px;
  font-size: 14px;
  font-weight: bold;
  color: #fff;
}

.status-pending {
  background: linear-gradient(135deg, #f39c12, #e74c3c);
}

.status-active {
  background: linear-gradient(135deg, #27ae60, #2ecc71);
  animation: pulse 1.5s infinite;
}

.status-ended {
  background: #95a5a6;
}

.status-soldout {
  background: #7f8c8d;
}

@keyframes pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.05); }
}

.right-section {
  width: 55%;
  padding: 50px;
  display: flex;
  flex-direction: column;
}

.product-title {
  font-size: 28px;
  color: #2c3e50;
  margin: 0 0 12px;
  line-height: 1.3;
  font-weight: 700;
}

.product-desc {
  font-size: 15px;
  color: #7f8c8d;
  line-height: 1.6;
  margin: 0 0 24px;
}

.price-section {
  background: linear-gradient(135deg, #fff5f5 0%, #ffe8e8 100%);
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 20px;
}

.seckill-price-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.seckill-price-row .label {
  background: #f56c6c;
  color: #fff;
  padding: 4px 10px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: bold;
}

.seckill-price {
  color: #f56c6c;
}

.seckill-price .currency {
  font-size: 20px;
}

.seckill-price .price {
  font-size: 36px;
  font-weight: bold;
}

.original-price-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.original-price-row .label {
  font-size: 12px;
  color: #999;
}

.original-price {
  color: #999;
  text-decoration: line-through;
  font-size: 16px;
}

.countdown-section {
  background: #2c3e50;
  border-radius: 12px;
  padding: 16px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.countdown-label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #fff;
  font-size: 14px;
}

.countdown-timer {
  display: flex;
  align-items: center;
}

.time-block {
  background: linear-gradient(135deg, #f56c6c, #e74c3c);
  color: #fff;
  padding: 8px 12px;
  border-radius: 8px;
  font-weight: bold;
  font-size: 18px;
  min-width: 40px;
  text-align: center;
}

.separator {
  color: #fff;
  margin: 0 6px;
  font-weight: bold;
  font-size: 18px;
}

.stock-section {
  margin-bottom: 20px;
}

.stock-header {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
  color: #606266;
  margin-bottom: 8px;
}

.stock-text {
  margin-top: 8px;
  font-size: 12px;
  color: #f56c6c;
  font-weight: 500;
}

.time-info {
  background: #f8f9fa;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 24px;
}

.time-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #606266;
}

.time-row:first-child {
  margin-bottom: 8px;
}

.time-row .el-icon {
  color: #409eff;
}

.action-section {
  display: flex;
  gap: 16px;
  margin-top: auto;
}

.seckill-btn {
  flex: 2;
  height: 54px;
  font-size: 18px;
  font-weight: bold;
  border-radius: 27px;
  background: linear-gradient(135deg, #f56c6c 0%, #e74c3c 100%);
  border: none;
  box-shadow: 0 4px 15px rgba(245, 108, 108, 0.4);
  transition: all 0.3s;
}

.seckill-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(245, 108, 108, 0.5);
}

.seckill-btn:disabled {
  background: #bdc3c7;
  box-shadow: none;
}

.back-btn {
  flex: 1;
  height: 54px;
  font-size: 16px;
  border-radius: 27px;
}

.result-section {
  margin-top: 20px;
}

@media (max-width: 900px) {
  .seckill-detail-card {
    flex-direction: column;
  }
  
  .left-section, .right-section {
    width: 100%;
  }
  
  .left-section {
    padding: 30px;
  }
  
  .right-section {
    padding: 30px;
  }
  
  .action-section {
    flex-direction: column;
  }
  
  .seckill-btn, .back-btn {
    flex: none;
    width: 100%;
  }
}
</style>
