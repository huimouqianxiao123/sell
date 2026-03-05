<template>
  <el-header>
    <div class="header-content">
      <div class="breadcrumb">
        <!-- Placeholder for breadcrumb if needed -->
        <span class="current-page">商品管理</span>
      </div>
      <div class="right-menu">
        <el-dropdown trigger="click" @command="handleCommand">
          <div class="avatar-wrapper">
            <el-avatar :size="32" class="user-avatar">{{ username.charAt(0).toUpperCase() }}</el-avatar>
            <span class="username">{{ username }}</span>
            <el-icon><CaretBottom /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>
  </el-header>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { CaretBottom } from '@element-plus/icons-vue'

const router = useRouter()
const username = ref('管理员')

const userStr = localStorage.getItem('user')
if (userStr) {
  const user = JSON.parse(userStr)
  username.value = user.username || '管理员'
}

const handleCommand = (command) => {
  if (command === 'logout') {
    localStorage.removeItem('user')
    localStorage.removeItem('token')
    router.push('/login')
  }
}
</script>

<style scoped>
.el-header {
  background-color: #fff;
  color: #333;
  height: 64px;
  padding: 0 20px;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  display: flex;
  align-items: center;
  position: relative;
  z-index: 5;
}
.header-content {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.current-page {
  font-size: 16px;
  font-weight: 500;
  color: #303133;
}
.right-menu {
  display: flex;
  align-items: center;
}
.avatar-wrapper {
  display: flex;
  align-items: center;
  cursor: pointer;
  gap: 8px;
}
.user-avatar {
  background-color: #409eff;
}
.username {
  font-size: 14px;
}
</style>
