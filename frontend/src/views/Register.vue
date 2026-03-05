<template>
  <div class="register-container">
    <div class="register-box">
      <div class="register-header">
        <div class="logo">
          <el-icon><UserFilled /></el-icon>
        </div>
        <h2>创建账户</h2>
        <p>注册以体验更多功能</p>
      </div>

      <el-form :model="registerForm" :rules="rules" ref="registerFormRef" size="large" class="register-form">
        <el-form-item prop="username">
          <el-input 
            v-model="registerForm.username" 
            placeholder="请输入用户名" 
            prefix-icon="User"
            clearable 
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input 
            v-model="registerForm.password" 
            type="password" 
            placeholder="请输入密码" 
            prefix-icon="Lock"
            show-password 
          />
        </el-form-item>
        <el-form-item prop="confirmPassword">
          <el-input 
            v-model="registerForm.confirmPassword" 
            type="password" 
            placeholder="请再次输入密码" 
            prefix-icon="Key"
            show-password 
            @keyup.enter="handleRegister"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="register-btn" @click="handleRegister" :loading="loading" round>
            立即注册
          </el-button>
        </el-form-item>
        <div class="register-footer">
          <span>已有账号？</span>
          <router-link to="/login" class="login-link">返回登录</router-link>
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
import { User, Lock, Key, UserFilled } from '@element-plus/icons-vue'

const router = useRouter()
const registerFormRef = ref(null)
const loading = ref(false)

const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: ''
})

const validatePass2 = (rule, value, callback) => {
  if (value === '') {
    callback(new Error('请再次输入密码'))
  } else if (value !== registerForm.password) {
    callback(new Error('两次输入密码不一致!'))
  } else {
    callback()
  }
}

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  confirmPassword: [{ validator: validatePass2, trigger: 'blur' }]
}

const handleRegister = async () => {
  if (!registerFormRef.value) return
  await registerFormRef.value.validate(async (valid) => {
    if (valid) {
      loading.value = true
      try {
        await request.post('/auth/register', {
          username: registerForm.username,
          password: registerForm.password
        })
        ElMessage.success('注册成功，请登录')
        router.push('/login')
      } catch (error) {
        // Error handled in interceptor
      } finally {
        loading.value = false
      }
    }
  })
}
</script>

<style scoped>
.register-container {
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background-image: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  overflow: hidden;
  position: relative;
}

.register-container::before {
  content: "";
  position: absolute;
  width: 2000px;
  height: 2000px;
  border-radius: 50%;
  background: linear-gradient(135deg, #a18cd1 0%, #fbc2eb 100%);
  bottom: -10%;
  left: 48%;
  transform: translateY(50%);
  z-index: 1;
  opacity: 0.5;
}

.register-box {
  width: 460px;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(10px);
  padding: 40px;
  border-radius: 16px;
  box-shadow: 0 15px 35px rgba(0, 0, 0, 0.2);
  z-index: 2;
  transition: transform 0.3s ease;
}

.register-box:hover {
  transform: translateY(-5px);
}

.register-header {
  text-align: center;
  margin-bottom: 30px;
}

.logo {
  font-size: 48px;
  color: #764ba2;
  margin-bottom: 10px;
}

.register-header h2 {
  font-size: 24px;
  color: #303133;
  margin: 0 0 10px;
  font-weight: 600;
}

.register-header p {
  font-size: 14px;
  color: #909399;
  margin: 0;
}

.register-form {
  margin-top: 20px;
}

.register-btn {
  width: 100%;
  font-weight: bold;
  letter-spacing: 1px;
  background: linear-gradient(to right, #667eea, #764ba2);
  border: none;
}

.register-btn:hover {
  background: linear-gradient(to right, #764ba2, #667eea);
  opacity: 0.9;
}

.register-footer {
  text-align: center;
  margin-top: 16px;
  font-size: 14px;
  color: #606266;
}

.login-link {
  color: #764ba2;
  text-decoration: none;
  font-weight: 500;
  margin-left: 5px;
  transition: color 0.2s;
}

.login-link:hover {
  color: #667eea;
  text-decoration: underline;
}
</style>
