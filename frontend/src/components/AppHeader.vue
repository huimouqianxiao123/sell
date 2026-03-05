<template>
  <el-header>
    <div class="header-inner">
      <div class="logo">
        <el-icon class="logo-icon"><Shop /></el-icon>
        <span>在线商城</span>
      </div>
      
      <div class="search-bar">
        <el-input 
          v-model="searchName" 
          placeholder="搜索您心仪的商品" 
          class="search-input"
          size="default"
          clearable 
          @clear="handleSearch"
          @keyup.enter="handleSearch"
        >
          <template #prefix>
            <el-icon class="search-icon"><Search /></el-icon>
          </template>
          <template #append>
            <el-button @click="handleSearch" class="search-btn">搜索</el-button>
          </template>
        </el-input>
      </div>

      <div class="user-info">
        <el-avatar :size="32" class="user-avatar">{{ username.charAt(0).toUpperCase() }}</el-avatar>
        <span class="username">欢迎您，{{ username }}</span>
        <el-button type="danger" plain size="small" @click="handleLogout">退出登录</el-button>
      </div>
    </div>
  </el-header>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Shop, Search } from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()
const username = ref('用户')
const searchName = ref('')

const userStr = localStorage.getItem('user')
if (userStr) {
  try {
    const user = JSON.parse(userStr)
    username.value = user.userVO?.username || user.username || '用户'
  } catch (e) {
    console.error('Error parsing user info', e)
  }
}

const handleSearch = () => {
  // Update query params
  router.push({
    path: '/user/mall',
    query: { ...route.query, name: searchName.value || undefined }
  })
}

const handleLogout = () => {
  localStorage.removeItem('user')
  localStorage.removeItem('token')
  router.push('/login')
}
</script>

<style scoped>
.el-header {
  background-color: #fff;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
  padding: 0;
  position: sticky;
  top: 0;
  z-index: 100;
  height: 64px;
}
.header-inner {
  max-width: 1200px;
  margin: 0 auto;
  height: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
}
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 24px;
  font-weight: bold;
  color: #409eff;
  cursor: pointer;
  width: 200px; /* Fixed width for logo to balance layout */
}
.logo-icon {
  font-size: 28px;
}
.search-bar {
  flex: 1;
  display: flex;
  justify-content: center;
  padding: 0 40px;
}
.search-input {
  width: 100%;
  max-width: 600px;
}
:deep(.el-input-group__append) {
  background-color: #409eff;
  color: white;
  border-color: #409eff;
  font-weight: bold;
}
:deep(.el-input-group__append:hover) {
  background-color: #66b1ff;
}
.user-info {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: flex-end;
  width: 280px; /* Adjust based on content */
}
.user-avatar {
  background-color: #409eff;
  color: #fff;
}
.username {
  font-size: 14px;
  color: #606266;
}
</style>
