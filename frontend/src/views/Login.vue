<template>
  <div class="login-container">
    <!-- Dark mode toggle placeholder (top right) -->
    <div class="dark-mode-switch">
      <el-icon><Moon /></el-icon>
    </div>

    <div class="login-box">
      <div class="login-header">
        <div class="logo-wrapper">
          <el-icon class="logo-icon"><Monitor /></el-icon>
        </div>
        <h2>欢迎回来</h2>
        <p>登录您的账户以继续</p>
      </div>
      
      <el-form :model="loginForm" :rules="rules" ref="loginFormRef" size="large" class="login-form">
        <el-form-item prop="username">
          <el-input 
            v-model="loginForm.username" 
            placeholder="2023400742" 
            prefix-icon="User" 
            clearable
            class="custom-input"
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input 
            v-model="loginForm.password" 
            type="password" 
            placeholder="请输入密码" 
            prefix-icon="Lock" 
            show-password 
            @keyup.enter="handleLogin"
            class="custom-input"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="login-btn" @click="handleLogin" :loading="loading" round>
            立即登录
          </el-button>
        </el-form-item>
        <div class="login-footer">
          <span>还没有账号？</span>
          <router-link to="/register" class="register-link">立即注册</router-link>
        </div>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../utils/request'
import { User, Lock, Monitor, Moon } from '@element-plus/icons-vue'

const router = useRouter()
const loginFormRef = ref(null)
const loading = ref(false)

const loginForm = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const handleLogin = async () => {
  if (!loginFormRef.value) return
  await loginFormRef.value.validate(async (valid) => {
    if (valid) {
      loading.value = true
      try {
        const res = await request.post('/auth/login', {
          username: loginForm.username,
          password: loginForm.password
        })
        ElMessage.success('登录成功')
        const { token, userVO } = res.data
        localStorage.setItem('token', token)
        localStorage.setItem('user', JSON.stringify(userVO))
        
        if (userVO.role === 'admin') {
          router.push('/admin/product')
        } else {
          router.push('/user/mall')
        }
      } catch (error) {
        console.error(error)
      } finally {
        loading.value = false
      }
    }
  })
}
</script>

<style scoped>
.login-container {
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  /* Soft blue gradient background */
  background: linear-gradient(180deg, #cbe2fb 0%, #f0f6ff 100%);
  position: relative;
  overflow: hidden;
}

/* Add a subtle curve at the bottom if desired, or just keep gradient */
.login-container::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 0;
  width: 100%;
  height: 40%;
  background: linear-gradient(180deg, rgba(255,255,255,0) 0%, rgba(255,255,255,0.4) 100%);
  pointer-events: none;
}

.dark-mode-switch {
  position: absolute;
  top: 20px;
  right: 20px;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background-color: rgba(255, 255, 255, 0.6);
  display: flex;
  justify-content: center;
  align-items: center;
  cursor: pointer;
  color: #606266;
  transition: all 0.3s;
}

.dark-mode-switch:hover {
  background-color: #fff;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
}

.login-box {
  width: 400px;
  background: #ffffff;
  padding: 48px 40px;
  border-radius: 20px;
  box-shadow: 0 10px 30px rgba(50, 50, 93, 0.1), 0 5px 15px rgba(0, 0, 0, 0.05);
  z-index: 2;
  text-align: center;
}

.login-header {
  margin-bottom: 32px;
}

.logo-wrapper {
  width: 64px;
  height: 64px;
  background-color: #e8f3ff;
  border-radius: 16px;
  display: flex;
  justify-content: center;
  align-items: center;
  margin: 0 auto 20px;
  color: #409eff;
  font-size: 32px;
}

.login-header h2 {
  font-size: 26px;
  color: #1a1a1a;
  margin: 0 0 8px;
  font-weight: 700;
  letter-spacing: -0.5px;
}

.login-header p {
  font-size: 14px;
  color: #909399;
  margin: 0;
}

.login-form {
  margin-top: 24px;
}

/* Custom Input Styling */
:deep(.custom-input .el-input__wrapper) {
  background-color: #f0f5fa; /* Lighter blue-ish gray */
  box-shadow: none !important;
  border-radius: 8px;
  padding: 1px 15px; /* Increase height slightly via padding */
  transition: all 0.3s;
  height: 44px; /* Explicit height */
}

:deep(.custom-input .el-input__wrapper:hover),
:deep(.custom-input .el-input__wrapper.is-focus) {
  background-color: #e6ebf5;
}

:deep(.custom-input .el-input__inner) {
  color: #333;
  height: 100%;
  font-weight: 500;
  font-size: 15px;
}

:deep(.custom-input .el-input__prefix) {
  margin-right: 10px;
}

:deep(.custom-input .el-input__prefix-inner) {
  color: #94a3b8; /* Cooler gray */
  font-size: 18px;
}

.login-btn {
  width: 100%;
  height: 48px;
  font-size: 16px;
  font-weight: 600;
  border-radius: 12px;
  background-color: #3b82f6; /* Vivid blue */
  border: none;
  margin-top: 10px;
  box-shadow: 0 4px 14px 0 rgba(59, 130, 246, 0.39);
  transition: transform 0.2s, box-shadow 0.2s, background-color 0.2s;
}

.login-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 20px 0 rgba(59, 130, 246, 0.45);
  background-color: #2563eb;
}

.login-btn:active {
  transform: translateY(0);
}

.login-footer {
  margin-top: 24px;
  font-size: 14px;
  color: #909399;
}

.register-link {
  color: #409eff;
  text-decoration: none;
  font-weight: 600;
  margin-left: 4px;
  transition: color 0.2s;
}

.register-link:hover {
  color: #66b1ff;
  text-decoration: underline;
}
</style>
