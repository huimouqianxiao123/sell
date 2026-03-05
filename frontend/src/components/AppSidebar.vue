<template>
  <el-aside width="220px">
    <el-menu
      :default-active="activeMenu"
      class="el-menu-vertical-demo"
      router
      background-color="#304156"
      text-color="#bfcbd9"
      active-text-color="#409eff"
    >
      <div class="logo-container">
        <el-icon class="logo-icon"><Management /></el-icon>
        <span class="logo-text">后台管理系统</span>
      </div>
      <el-menu-item index="/admin/product">
        <el-icon><Goods /></el-icon>
        <span>商品管理</span>
      </el-menu-item>
      <!-- Future menu items can go here -->
    </el-menu>
  </el-aside>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Goods, Management } from '@element-plus/icons-vue'

const route = useRoute()
const activeMenu = computed(() => route.path)
</script>

<style scoped>
.el-aside {
  background-color: #304156;
  color: #333;
  height: 100vh;
  transition: width 0.3s;
  box-shadow: 2px 0 6px rgba(0, 21, 41, 0.35);
  z-index: 10;
}
.el-menu {
  border-right: none;
  background-color: transparent;
}
.logo-container {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  background-color: #2b3649;
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  overflow: hidden;
}
.logo-icon {
  font-size: 22px;
  color: #409eff;
}
:deep(.el-menu-item) {
  height: 56px;
  line-height: 56px;
}
:deep(.el-menu-item:hover) {
  background-color: #263445 !important;
}
:deep(.el-menu-item.is-active) {
  background-color: #1f2d3d !important;
}
</style>
