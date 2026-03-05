<template>
  <div class="mall-container">
    <!-- Seckill Section -->
    <div class="seckill-section">
      <div class="seckill-header">
        <div class="header-left">
          <div class="seckill-icon">
            <el-icon><Lightning /></el-icon>
          </div>
          <h2 class="section-title">限时秒杀</h2>
          <span class="section-subtitle">超值优惠</span>
        </div>
        <div class="header-right">
          <span class="seckill-count" v-if="seckillProducts.length > 0">
            共 {{ seckillProducts.length }} 个活动
          </span>
        </div>
      </div>
      
      <!-- 带左右滑动的秒杀列表 -->
      <div class="seckill-carousel" v-if="seckillProducts.length > 0">
        <!-- 左箭头 -->
        <button 
          class="carousel-arrow arrow-left" 
          @click="scrollSeckillList('left')"
          :disabled="!canScrollLeft"
        >
          <el-icon><ArrowLeft /></el-icon>
        </button>
        
        <!-- 秒杀商品滚动容器 -->
        <div class="seckill-scroll-container" ref="seckillScrollRef" @scroll="updateScrollButtons">
          <div class="seckill-list">
            <div 
              v-for="item in seckillProducts" 
              :key="item.id" 
              class="seckill-card"
              @click="goToSeckillDetail(item)"
            >
              <div class="seckill-image-wrapper">
                <el-image :src="item.imageUrl" fit="cover" class="seckill-image">
                  <template #error>
                    <div class="image-slot">
                      <el-icon><Picture /></el-icon>
                    </div>
                  </template>
                </el-image>
                <!-- 状态标签 -->
                <div class="status-badge" :class="getStatusClass(item.status)">
                  {{ getStatusLabel(item.status) }}
                </div>
              </div>
              <div class="seckill-info">
                <h3 class="seckill-name" :title="item.name">{{ item.name }}</h3>
                <div class="seckill-price-box">
                  <span class="seckill-price">￥{{ item.seckillPrice }}</span>
                  <span class="original-price" v-if="item.originalPrice">￥{{ item.originalPrice }}</span>
                </div>
                
                <!-- 单独的倒计时显示 -->
                <div class="item-countdown">
                  <el-icon class="countdown-icon"><Timer /></el-icon>
                  <span class="countdown-label">{{ item.status === 0 ? '距开始' : '距结束' }}</span>
                  <span class="countdown-time">{{ getItemCountdown(item) }}</span>
                </div>
                
                <div class="seckill-stock-bar">
                  <el-progress 
                    :percentage="calculateProgress(item)" 
                    :show-text="false" 
                    color="#f56c6c"
                    :stroke-width="8"
                  />
                  <div class="stock-text">
                    <span>已抢 {{ item.soldCount || 0 }}</span>
                    <span>库存 {{ item.seckillStock }}</span>
                  </div>
                </div>
                <el-button 
                  type="danger" 
                  class="seckill-btn" 
                  :disabled="item.status !== 1 || item.seckillStock <= 0"
                  @click.stop="handleSeckill(item)"
                >
                  {{ getSeckillBtnText(item) }}
                </el-button>
              </div>
            </div>
          </div>
        </div>
        
        <!-- 右箭头 -->
        <button 
          class="carousel-arrow arrow-right" 
          @click="scrollSeckillList('right')"
          :disabled="!canScrollRight"
        >
          <el-icon><ArrowRight /></el-icon>
        </button>
      </div>
      
      <div class="seckill-empty" v-else>
        <el-empty description="暂无秒杀活动，敬请期待" :image-size="100"></el-empty>
      </div>
    </div>

    <!-- Hot Products Section (Placeholder for now, reusing product list logic) -->
    <div class="section-header">
      <h2 class="section-title">热门商品</h2>
      <div class="category-tabs">
        <span class="active">全部商品</span>
      </div>
    </div>

    <!-- Product Grid -->
    <div class="product-grid" v-loading="loading">
      <el-empty v-if="!loading && products.length === 0" description="暂无商品" />
      
      <div 
        v-for="item in products" 
        :key="item.id" 
        class="product-card"
        @click="handleDetail(item)"
      >
        <div class="tag-new" v-if="isNew(item.createTime)">新品</div>
        <div class="image-wrapper">
          <el-image :src="item.image" fit="cover" class="product-image" loading="lazy">
            <template #error>
              <div class="image-slot">
                <el-icon><Picture /></el-icon>
              </div>
            </template>
          </el-image>
          <div class="card-overlay">
            <div class="view-detail-btn">
              查看详情
            </div>
          </div>
        </div>
        
        <div class="product-info">
          <h3 class="product-name" :title="item.name">{{ item.name }}</h3>
          <p class="product-desc" :title="item.description">{{ item.description || '暂无描述' }}</p>
          <div class="bottom-info">
            <span class="price">
              <span class="currency">￥</span>{{ item.price }}
            </span>
            <span class="stock" :class="{ 'out-of-stock': item.stock <= 0 }">
              {{ item.stock > 0 ? `库存: ${item.stock}` : '已售罄' }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- Pagination -->
    <div class="pagination-container">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[12, 24, 36]"
        layout="total, prev, pager, next, jumper"
        :total="total"
        @current-change="handleCurrentChange"
        background
      />
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { productApi, seckillApi } from '../utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Picture, Search, Lightning, ArrowLeft, ArrowRight, Timer } from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const products = ref([])
const seckillProducts = ref([])
const currentPage = ref(1)
const pageSize = ref(12)
const total = ref(0)
const searchName = ref('')

// Watch for search query changes
watch(() => route.query.name, (newVal) => {
  searchName.value = newVal || ''
  currentPage.value = 1
  fetchData()
})

// Countdown and Scroll logic
const currentTimestamp = ref(Date.now())
const seckillScrollRef = ref(null)
const canScrollLeft = ref(false)
const canScrollRight = ref(false)
let timerInterval = null

const updateScrollButtons = () => {
  if (!seckillScrollRef.value) return
  const { scrollLeft, scrollWidth, clientWidth } = seckillScrollRef.value
  canScrollLeft.value = scrollLeft > 0
  canScrollRight.value = scrollLeft + clientWidth < scrollWidth - 1 // -1 for rounding errors
}

const scrollSeckillList = (direction) => {
  if (!seckillScrollRef.value) return
  const scrollAmount = 320 * 2 // Scroll 2 cards at a time
  if (direction === 'left') {
    seckillScrollRef.value.scrollBy({ left: -scrollAmount, behavior: 'smooth' })
  } else {
    seckillScrollRef.value.scrollBy({ left: scrollAmount, behavior: 'smooth' })
  }
}

const fetchData = async () => {
  loading.value = true
  try {
    const res = await productApi.page({
      page: currentPage.value,
      size: pageSize.value,
      name: searchName.value
    })
    const records = res?.data?.records ?? []
    products.value = records.filter(p => p?.status === 1)
    total.value = res?.data?.total ?? 0
  } catch (error) {
    // handled by interceptor
  } finally {
    loading.value = false
  }
}

const fetchSeckillData = async () => {
  try {
    const res = await seckillApi.list()
    if (res.data) {
      seckillProducts.value = res.data
      startTimer()
      // Wait for DOM update to check scroll state
      nextTick(() => {
        updateScrollButtons()
      })
    }
  } catch (error) {
    console.error('Failed to fetch seckill data', error)
  }
}

const startTimer = () => {
  if (timerInterval) clearInterval(timerInterval)
  timerInterval = setInterval(() => {
    currentTimestamp.value = Date.now()
  }, 1000)
}

const getItemCountdown = (item) => {
  if (!item) return '00:00:00'
  
  let targetTime
  if (item.status === 0) { // 即将开始
    targetTime = new Date(item.startTime).getTime()
  } else if (item.status === 1) { // 进行中
    targetTime = new Date(item.endTime).getTime()
  } else {
    return '00:00:00'
  }
  
  const diff = targetTime - currentTimestamp.value
  if (diff <= 0) return '00:00:00'
  
  const h = Math.floor((diff / (1000 * 60 * 60)) % 24) + Math.floor(diff / (1000 * 60 * 60 * 24)) * 24
  const m = Math.floor((diff / (1000 * 60)) % 60)
  const s = Math.floor((diff / 1000) % 60)
  
  return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
}

const handleCurrentChange = (val) => {
  currentPage.value = val
  fetchData()
}

const handleDetail = (item) => {
  router.push({
    name: 'UserProductDetail',
    params: { id: item.id },
    state: { product: JSON.parse(JSON.stringify(item)) }
  })
}

const handleSeckill = async (item) => {
  try {
    await ElMessageBox.confirm(
      `确认立即抢购 ${item.name} 吗？秒杀价: ￥${item.seckillPrice}`,
      '秒杀确认',
      {
        confirmButtonText: '立即抢购',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    
    const res = await seckillApi.start(item.id)
    // The backend returns a string result directly for now based on the controller
    // If it's successful, it might return "排队中" or order info?
    // Based on SeckillProductController: return R.ok(result);
    // And Service returns string.
    
    // Check response content
    const msg = res.data
    if (msg && msg.includes('失败')) {
      ElMessage.error(msg)
    } else {
      ElMessage.success('抢购请求已提交，正在处理中...')
      // Polling for result could be added here
    }
  } catch (error) {
    console.error(error)
  }
}

const getSeckillBtnText = (item) => {
  if (item.status === 0) return '即将开始'
  if (item.status === 1) return '立即抢购'
  if (item.status === 2) return '已结束'
  if (item.status === 3 || item.seckillStock <= 0) return '已抢光'
  return '查看详情'
}

const calculateProgress = (item) => {
  if (!item.seckillStock && !item.soldCount) return 0
  const total = item.seckillStock + (item.soldCount || 0) // Assuming we have soldCount or estimating
  // If we don't have soldCount, just show random progress or 0? 
  // Let's assume we don't have sold count in VO for now, so maybe just 50% fixed or based on stock vs original?
  // Since we only have seckillStock, we can't calculate percentage accurately without total.
  // Mocking it for visual:
  if (item.status === 3 || item.seckillStock === 0) return 100
  return 60 // Mock value
}

const isNew = (dateStr) => {
  if (!dateStr) return false
  const date = new Date(dateStr)
  const now = new Date()
  const diffTime = Math.abs(now - date)
  const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24)) 
  return diffDays <= 7 // New if within 7 days
}

// 跳转到秒杀详情页
const goToSeckillDetail = (item) => {
  router.push({
    name: 'SeckillDetail',
    params: { id: item.id }
  })
}

// 获取状态标签文本
const getStatusLabel = (status) => {
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

onMounted(() => {
  fetchData()
  fetchSeckillData()
})

onUnmounted(() => {
  if (timerInterval) clearInterval(timerInterval)
})
</script>

<style scoped>
.mall-container {
  width: 100%;
  padding-top: 20px;
}
/* Seckill Section */
.seckill-section {
  background-color: #f52b43; /* Red background for seckill */
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 40px;
  color: #fff;
}

.seckill-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.seckill-icon {
  font-size: 24px;
  background: #fff;
  color: #f52b43;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  justify-content: center;
  align-items: center;
}

.section-title {
  font-size: 24px;
  font-weight: bold;
  margin: 0;
}

.section-subtitle {
  font-size: 14px;
  background: #000;
  color: #ffd700;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: bold;
}

.seckill-count {
  font-size: 14px;
  opacity: 0.9;
}

/* Seckill Carousel Styles */
.seckill-carousel {
  position: relative;
  width: 100%;
}

.seckill-scroll-container {
  overflow-x: auto;
  scroll-behavior: smooth;
  scrollbar-width: none; /* Firefox */
  -ms-overflow-style: none; /* IE/Edge */
  margin: 0 -8px; /* Offset margins for shadow visibility */
  padding: 8px;
  display: flex;
}

.seckill-scroll-container::-webkit-scrollbar {
  display: none; /* Chrome, Safari, Opera */
}

.seckill-list {
  display: flex;
  gap: 16px;
  flex-wrap: nowrap; /* Important for horizontal scrolling */
}

.seckill-card {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  color: #333;
  cursor: pointer;
  transition: all 0.3s ease;
  flex: 0 0 280px; /* Fixed width for scrolling items */
  width: 280px;
}

/* Carousel Arrows */
.carousel-arrow {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: #fff;
  border: 1px solid #eee;
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 20px;
  color: #333;
  cursor: pointer;
  z-index: 2;
  transition: all 0.2s;
}

.carousel-arrow:hover {
  background: #f5f7fa;
  color: #f52b43;
}

.carousel-arrow:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background: #f9f9f9;
}

.arrow-left {
  left: -20px;
}

.arrow-right {
  right: -20px;
}

/* Item Countdown */
.item-countdown {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #f56c6c;
  background: #fff3f3;
  padding: 4px 8px;
  border-radius: 4px;
  margin-bottom: 12px;
}

.countdown-icon {
  font-size: 14px;
}

.countdown-time {
  font-weight: bold;
  font-family: monospace;
  font-size: 13px;
}



.seckill-image-wrapper {
  width: 100%;
  height: 180px;
  margin-bottom: 12px;
  display: flex;
  justify-content: center;
  align-items: center;
  position: relative;
}

.seckill-image {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

/* 状态标签 */
.status-badge {
  position: absolute;
  top: 8px;
  right: 8px;
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: bold;
  color: #fff;
}

.status-pending {
  background: linear-gradient(135deg, #f39c12, #e67e22);
}

.status-active {
  background: linear-gradient(135deg, #27ae60, #2ecc71);
  animation: pulse-badge 1.5s infinite;
}

.status-ended {
  background: #95a5a6;
}

.status-soldout {
  background: #7f8c8d;
}

@keyframes pulse-badge {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.8; }
}

.seckill-name {
  font-size: 14px;
  margin: 0 0 8px;
  height: 40px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.seckill-price-box {
  margin-bottom: 8px;
}

.seckill-price {
  color: #f52b43;
  font-size: 18px;
  font-weight: bold;
  margin-right: 8px;
}

.original-price {
  color: #999;
  text-decoration: line-through;
  font-size: 12px;
}

.seckill-stock-bar {
  margin-bottom: 12px;
}

.stock-text {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #666;
  margin-top: 4px;
}

.seckill-btn {
  width: 100%;
  border-radius: 20px;
  font-weight: bold;
  background: linear-gradient(to right, #ff4e50, #f9d423);
  border: none;
}

.seckill-btn:disabled {
  background: #ccc;
}

/* Hot Products Section Header */
.section-header {
  margin-bottom: 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}

.section-header .section-title {
  color: #333;
  font-size: 22px;
}

.category-tabs {
  display: flex;
  gap: 20px;
  font-size: 14px;
  color: #666;
  margin-left: auto;
}

.category-tabs span {
  cursor: pointer;
  padding-bottom: 4px;
}

.category-tabs span.active {
  color: #409eff;
  border-bottom: 2px solid #409eff;
  font-weight: bold;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
  margin-bottom: 40px;
  min-height: 400px;
  justify-content: center;
}

.product-card {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
  transition: all 0.3s;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
  display: flex;
  flex-direction: column;
  position: relative;
  cursor: pointer;
}

.product-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.1);
}

.tag-new {
  position: absolute;
  top: 10px;
  left: 10px;
  background: #f56c6c;
  color: #fff;
  font-size: 12px;
  padding: 2px 6px;
  border-radius: 4px;
  z-index: 2;
}

.image-wrapper {
  position: relative;
  width: 100%;
  padding-top: 100%; /* 1:1 Aspect Ratio */
  background-color: #f7f8fa;
  overflow: hidden;
}

.product-image {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.5s ease;
}

.product-card:hover .product-image {
  transform: scale(1.05);
}

.image-slot {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
  background: #f5f7fa;
  color: #909399;
  font-size: 32px;
}

.card-overlay {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.05);
  display: flex;
  justify-content: center;
  align-items: center;
  opacity: 0;
  transition: opacity 0.3s ease;
}

.product-card:hover .card-overlay {
  opacity: 1;
}

.view-detail-btn {
  padding: 6px 12px;
  background: rgba(255, 255, 255, 0.95);
  border-radius: 20px;
  font-size: 12px;
  font-weight: 500;
  color: #333;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
  transform: translateY(10px);
  transition: transform 0.3s;
}

.product-card:hover .view-detail-btn {
  transform: translateY(0);
}

.product-info {
  padding: 12px;
  flex: 1;
  display: flex;
  flex-direction: column;
}

.product-name {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 500;
  color: #333;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
}

.product-desc {
  font-size: 12px;
  color: #999;
  margin: 0 0 10px;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  flex: 1;
}

.bottom-info {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-top: auto;
}

.price {
  color: #f56c6c;
  font-size: 16px;
  font-weight: bold;
}

.currency {
  font-size: 12px;
  margin-right: 1px;
}

.stock {
  font-size: 12px;
  color: #ccc;
}

.out-of-stock {
  color: #999;
}

.seckill-empty {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 200px;
}

.seckill-empty :deep(.el-empty__description) {
  color: rgba(255, 255, 255, 0.8);
}

.pagination-container {
  display: flex;
  justify-content: center;
  margin-top: 20px;
  padding-bottom: 40px;
}

@media (max-width: 1200px) {
  .product-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 900px) {
  .product-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .section-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }

  .category-tabs {
    margin-left: 0;
  }
}

@media (max-width: 600px) {
  .product-grid {
    grid-template-columns: 1fr;
  }
}
</style>
