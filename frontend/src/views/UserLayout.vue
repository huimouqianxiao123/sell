<template>
  <el-container class="layout-container" direction="vertical">
    <AppHeader />
    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import AppHeader from '../components/AppHeader.vue'
</script>

<style scoped>
.layout-container {
  min-height: 100vh;
  background-color: #f5f7fa;
}
.el-main {
  max-width: 1200px;
  margin: 0 auto;
  width: 100%;
  padding: 24px 20px;
}
</style>
