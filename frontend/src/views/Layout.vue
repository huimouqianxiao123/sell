<template>
  <el-container class="layout-container">
    <AppSidebar />
    <el-container class="main-content">
      <AdminHeader />
      <el-main>
        <router-view v-slot="{ Component }">
          <transition name="fade-transform" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import AppSidebar from '../components/AppSidebar.vue'
import AdminHeader from '../components/AdminHeader.vue'
</script>

<style scoped>
.layout-container {
  height: 100vh;
  display: flex;
}
.main-content {
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}
.el-main {
  background-color: #f0f2f5;
  color: #333;
  padding: 20px;
  overflow-y: auto;
}

/* Transition animation */
.fade-transform-leave-active,
.fade-transform-enter-active {
  transition: all 0.5s;
}

.fade-transform-enter-from {
  opacity: 0;
  transform: translateX(-30px);
}

.fade-transform-leave-to {
  opacity: 0;
  transform: translateX(30px);
}
</style>
