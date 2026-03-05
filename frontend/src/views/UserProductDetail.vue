<template>
  <div class="product-detail-container" v-loading="loading">
    <div class="product-detail-card" v-if="product">
      <div class="left-section">
        <div class="image-box">
          <el-image :src="product.image" fit="cover" class="detail-image">
            <template #error>
              <div class="image-slot">
                <el-icon><Picture /></el-icon>
              </div>
            </template>
          </el-image>
        </div>
      </div>
      
      <div class="right-section">
        <h1 class="product-title">{{ product.name }}</h1>
        <p class="product-desc">{{ product.description || '暂无描述' }}</p>
        
        <div class="price-box">
          <span class="currency">￥</span>
          <span class="price">{{ product.price }}</span>
        </div>
        
        <div class="stock-info">
          <span class="label">库存:</span>
          <span class="value">{{ product.stock }}</span>
        </div>

        <div class="quantity-selector">
          <span class="label">数量:</span>
          <el-input-number v-model="buyCount" :min="1" :max="product.stock" :disabled="product.stock <= 0" />
        </div>

        <div class="action-buttons">
          <el-button type="primary" size="large" class="buy-btn" @click="handleBuy" :disabled="product.stock <= 0 || actionLoading" :loading="actionLoading">
            立即购买
          </el-button>
          <el-button type="success" size="large" class="cart-btn" @click="handleAddToCart" :disabled="product.stock <= 0 || actionLoading" :loading="actionLoading">
            加入购物车
          </el-button>
        </div>
      </div>
    </div>
    
    <el-empty v-else-if="!loading" description="商品不存在或已下架" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { productApi, orderApi, shoppingCartApi, alipayApi } from '../utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Picture } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const productId = route.params.id

const loading = ref(false)
const actionLoading = ref(false)
const product = ref(null)
const buyCount = ref(1)

const fetchProduct = async () => {
  loading.value = true
  try {
    const res = await productApi.getById(productId)
    if (res.data) {
      product.value = res.data
    }
  } catch (error) {
    // handled by interceptor
  } finally {
    loading.value = false
  }
}

const handleBuy = async () => {
  try {
    await ElMessageBox.confirm(
      `确认购买 ${product.value.name} x ${buyCount.value} 吗？总价: ￥${(product.value.price * buyCount.value).toFixed(2)}`,
      '购买确认',
      {
        confirmButtonText: '确认购买',
        cancelButtonText: '取消',
        type: 'info'
      }
    )
  } catch (error) {
    return
  }

  actionLoading.value = true
  try {
    const payload = {
      orderRequests: [
        {
          productId: Number(product.value.id),
          count: Number(buyCount.value)
        }
      ]
    }
    console.log('Create order payload:', payload)
    
    const res = await orderApi.create(payload)
    ElMessage.success(`下单成功，订单号：${res.data}`)
    
    // Initiate payment
    try {
      const orderNo = res.data
      const amount = (product.value.price * buyCount.value).toFixed(2)
      
      ElMessage.info('正在跳转支付...')
      const payRes = await alipayApi.pay(orderNo, amount)
      
      // Create a temporary div to render the form and submit it
      const div = document.createElement('div')
      div.innerHTML = payRes.data
      document.body.appendChild(div)
      
      const form = div.querySelector('form')
      if (form) {
        form.submit()
      } else {
        console.error('Payment form not found in response')
        ElMessage.error('支付表单加载失败')
      }
    } catch (payError) {
      console.error('Payment initiation failed', payError)
      ElMessage.error('支付跳转失败，请去订单列表支付')
    }

    // Refresh product info to update stock
    fetchProduct()
  } finally {
    actionLoading.value = false
  }
}

const handleAddToCart = async () => {
  actionLoading.value = true
  try {
    await shoppingCartApi.add(product.value.id, buyCount.value)
    ElMessage.success('已加入购物车')
  } finally {
    actionLoading.value = false
  }
}

onMounted(() => {
  // 优先使用路由传递过来的参数
  if (history.state?.product) {
    product.value = history.state.product
    return
  }

  if (productId) {
    fetchProduct()
  } else {
    ElMessage.error('无效的商品ID')
    router.push('/user/mall')
  }
})
</script>

<style scoped>
.product-detail-container {
  max-width: 1000px;
  margin: 40px auto;
  padding: 0 20px;
}

.product-detail-card {
  display: flex;
  background: #fff;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.05);
  min-height: 500px;
}

.left-section {
  width: 50%;
  background-color: #f8f9fa;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
}

.image-box {
  width: 100%;
  height: 0;
  padding-bottom: 100%;
  position: relative;
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 2px 12px rgba(0,0,0,0.03);
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
  font-size: 48px;
}

.right-section {
  width: 50%;
  padding: 60px 50px;
  display: flex;
  flex-direction: column;
}

.product-title {
  font-size: 28px;
  color: #333;
  margin: 0 0 16px;
  line-height: 1.4;
}

.product-desc {
  font-size: 15px;
  color: #666;
  line-height: 1.6;
  margin: 0 0 30px;
  flex-grow: 1;
}

.price-box {
  margin-bottom: 20px;
  color: #f56c6c;
  display: flex;
  align-items: baseline;
}

.currency {
  font-size: 20px;
  margin-right: 4px;
}

.price {
  font-size: 36px;
  font-weight: bold;
}

.stock-info {
  margin-bottom: 24px;
  font-size: 14px;
  color: #909399;
}

.quantity-selector {
  margin-bottom: 40px;
  display: flex;
  align-items: center;
  gap: 16px;
}

.quantity-selector .label {
  font-size: 15px;
  color: #606266;
}

.action-buttons {
  display: flex;
  gap: 20px;
  margin-top: auto;
}

.buy-btn, .cart-btn {
  flex: 1;
  height: 50px;
  font-size: 16px;
  border-radius: 25px;
}

@media (max-width: 768px) {
  .product-detail-card {
    flex-direction: column;
  }
  .left-section, .right-section {
    width: 100%;
  }
  .right-section {
    padding: 30px;
  }
}
</style>
