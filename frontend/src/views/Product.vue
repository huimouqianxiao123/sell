<template>
  <div class="product-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>商品列表</span>
          <el-button type="primary" @click="handleAdd">新增商品</el-button>
        </div>
      </template>
      
      <!-- Search -->
      <div class="search-bar">
        <el-input v-model="searchName" placeholder="请输入商品名称搜索" style="width: 200px; margin-right: 10px;" clearable @clear="fetchData" />
        <el-button type="primary" @click="fetchData">搜索</el-button>
      </div>

      <!-- Table -->
      <el-table :data="tableData" style="width: 100%; margin-top: 20px;" v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="image" label="图片" width="100">
          <template #default="scope">
            <el-image style="width: 50px; height: 50px" :src="scope.row.image" fit="cover" v-if="scope.row.image">
              <template #error>
                <div class="image-slot">
                  <el-icon><Picture /></el-icon>
                </div>
              </template>
            </el-image>
            <span v-else>无图</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="商品名称" />
        <el-table-column prop="price" label="价格" width="120">
          <template #default="scope">
            ￥{{ scope.row.price }}
          </template>
        </el-table-column>
        <el-table-column prop="stock" label="库存" width="100" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="scope">
            <el-tag :type="scope.row.status === 1 ? 'success' : 'info'">
              {{ scope.row.status === 1 ? '上架' : '下架' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="280">
          <template #default="scope">
            <el-button size="small" @click="handleEdit(scope.row)">编辑</el-button>
            <el-button size="small" type="warning" @click="handleAddSeckill(scope.row)">加入秒杀</el-button>
            <el-button size="small" type="danger" @click="handleDelete(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- Pagination -->
      <div class="pagination-container">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </el-card>

    <!-- Dialog -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="500px">
      <el-form :model="form" label-width="80px" :rules="rules" ref="formRef">
        <el-form-item label="商品名称" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="价格" prop="price">
          <el-input-number v-model="form.price" :precision="2" :step="0.1" :min="0" />
        </el-form-item>
        <el-form-item label="库存" prop="stock">
          <el-input-number v-model="form.stock" :step="1" :min="0" />
        </el-form-item>
        <el-form-item label="商品图片" prop="image">
           <el-radio-group v-model="uploadType" style="margin-bottom: 10px;">
             <el-radio label="file">本地上传</el-radio>
             <el-radio label="url">网络图片</el-radio>
           </el-radio-group>
           
           <!-- 本地上传 -->
           <div v-if="uploadType === 'file'">
             <el-upload
               class="avatar-uploader"
               action="#"
               :show-file-list="false"
               :auto-upload="false"
               :on-change="handleUploadChange"
               accept="image/*"
             >
               <img v-if="form.image" :src="form.image" class="avatar" />
               <el-icon v-else class="avatar-uploader-icon"><Plus /></el-icon>
             </el-upload>
             <div class="el-upload__tip">只能上传图片文件，建议大小不超过2MB</div>
           </div>

           <!-- 网络图片 -->
           <div v-else>
             <el-input v-model="form.image" placeholder="请输入图片URL" clearable style="margin-bottom: 10px;">
             </el-input>
             <div class="image-preview-box" v-if="form.image">
               <img :src="form.image" class="avatar-preview" />
             </div>
             <div class="el-upload__tip">请输入有效的图片网络链接</div>
           </div>
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">上架</el-radio>
            <el-radio :label="0">下架</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" @click="submitForm">确定</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- Seckill Dialog -->
    <el-dialog v-model="seckillDialogVisible" title="添加到秒杀活动" width="500px">
      <el-form :model="seckillForm" label-width="100px" :rules="seckillRules" ref="seckillFormRef">
        <el-form-item label="商品名称">
          <el-input :value="seckillForm.productName" disabled />
        </el-form-item>
        <el-form-item label="原价">
          <el-input :value="'￥' + seckillForm.originalPrice" disabled />
        </el-form-item>
        <el-form-item label="秒杀价格" prop="seckillPrice">
          <el-input-number v-model="seckillForm.seckillPrice" :precision="2" :step="1" :min="0.01" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="秒杀库存" prop="seckillStock">
          <el-input-number v-model="seckillForm.seckillStock" :step="1" :min="1" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="开始时间" prop="startTime">
          <el-date-picker
            v-model="seckillForm.startTime"
            type="datetime"
            placeholder="选择开始时间"
            style="width: 100%;"
            :disabled-date="disabledStartDate"
          />
        </el-form-item>
        <el-form-item label="结束时间" prop="endTime">
          <el-date-picker
            v-model="seckillForm.endTime"
            type="datetime"
            placeholder="选择结束时间"
            style="width: 100%;"
            :disabled-date="disabledEndDate"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="seckillDialogVisible = false">取消</el-button>
          <el-button type="warning" @click="submitSeckillForm" :loading="seckillLoading">添加秒杀</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onActivated } from 'vue'
import { productApi, fileApi, seckillApi } from '../utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Picture, Plus } from '@element-plus/icons-vue'

const loading = ref(false)
const tableData = ref([])
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const searchName = ref('')

const dialogVisible = ref(false)
const dialogTitle = ref('新增商品')
const formRef = ref(null)
const uploadType = ref('file')
const imageUrlInput = ref('')

const form = reactive({
  id: null,
  name: '',
  price: 0,
  stock: 0,
  image: '',
  status: 1,
  description: ''
})

const rules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  price: [{ required: true, message: '请输入价格', trigger: 'blur' }],
  stock: [{ required: true, message: '请输入库存', trigger: 'blur' }]
}

const fetchData = async () => {
  loading.value = true
  try {
    const res = await productApi.page({
      page: currentPage.value,
      size: pageSize.value,
      name: searchName.value
    })
    tableData.value = res.data.records
    total.value = res.data.total
  } catch (error) {
    // handled by interceptor
  } finally {
    loading.value = false
  }
}

const handleSizeChange = (val) => {
  pageSize.value = val
  fetchData()
}

const handleCurrentChange = (val) => {
  currentPage.value = val
  fetchData()
}

const resetForm = () => {
  form.id = null
  form.name = ''
  form.price = 0
  form.stock = 0
  form.image = ''
  form.status = 1
  form.description = ''
  uploadType.value = 'file'
  imageUrlInput.value = ''
  if (formRef.value) {
    formRef.value.clearValidate()
  }
}

const handleAdd = () => {
  dialogTitle.value = '新增商品'
  resetForm()
  dialogVisible.value = true
}

const handleEdit = (row) => {
  dialogTitle.value = '编辑商品'
  form.id = row?.id ?? null
  form.name = row?.name ?? ''
  form.price = row?.price ?? 0
  form.stock = row?.stock ?? 0
  form.image = row?.image ?? ''
  form.status = row?.status ?? 1
  form.description = row?.description ?? ''
  // 如果当前是网络图片且非base64，可以切到url模式回显，但因为需要转base64提交，这里默认还是file模式展示预览即可
  uploadType.value = 'file' 
  dialogVisible.value = true
}

const handleUploadChange = async (file) => {
  const isImage = file.raw.type.startsWith('image/')
  const isLt2M = file.raw.size / 1024 / 1024 < 2

  if (!isImage) {
    ElMessage.error('上传文件只能是图片格式!')
    return
  }
  if (!isLt2M) {
    ElMessage.error('上传图片大小不能超过 2MB!')
    return
  }

  // Upload to backend immediately
  const formData = new FormData()
  formData.append('file', file.raw)

  try {
    const res = await fileApi.upload(formData)
    
    // FileUploadController returns the URL directly as string (ResponseEntity<String>)
    // Axios puts it in res.data
    if (res.status === 200 && res.data) {
      form.image = res.data
      ElMessage.success('图片上传成功')
    } else {
      ElMessage.error('图片上传失败')
    }
  } catch (error) {
    console.error('Upload error:', error)
    ElMessage.error('图片上传出错: ' + (error.response?.data || error.message))
  }
}

const handleDelete = (row) => {
  ElMessageBox.confirm('确认删除该商品吗?', '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await productApi.removeById(row.id)
      ElMessage.success('删除成功')
      fetchData()
    } catch (error) {
      // handled by interceptor
    }
  }).catch(() => {})
}

const submitForm = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (valid) {
      try {
        if (form.id) {
          await productApi.update(form)
          ElMessage.success('修改成功')
        } else {
          await productApi.save(form)
          ElMessage.success('保存成功')
        }
        dialogVisible.value = false
        fetchData()
      } catch (error) {
        // handled by interceptor
      }
    }
  })
}

onMounted(() => {
  fetchData()
})

onActivated(() => {
  fetchData()
})

// 秒杀相关
const seckillDialogVisible = ref(false)
const seckillLoading = ref(false)
const seckillFormRef = ref(null)

const seckillForm = reactive({
  productId: null,
  productName: '',
  originalPrice: 0,
  seckillPrice: 0,
  seckillStock: 10,
  startTime: null,
  endTime: null
})

const seckillRules = {
  seckillPrice: [{ required: true, message: '请输入秒杀价格', trigger: 'blur' }],
  seckillStock: [{ required: true, message: '请输入秒杀库存', trigger: 'blur' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }]
}

const disabledStartDate = (date) => {
  return date.getTime() < Date.now() - 86400000 // 不能选择过去的日期
}

const disabledEndDate = (date) => {
  if (seckillForm.startTime) {
    return date.getTime() < new Date(seckillForm.startTime).getTime()
  }
  return date.getTime() < Date.now() - 86400000
}

const handleAddSeckill = (row) => {
  seckillForm.productId = row.id
  seckillForm.productName = row.name
  seckillForm.originalPrice = row.price
  seckillForm.seckillPrice = Math.round(row.price * 0.8 * 100) / 100 // 默认8折
  seckillForm.seckillStock = Math.min(row.stock, 100) // 默认取库存和100的最小值
  seckillForm.startTime = null
  seckillForm.endTime = null
  seckillDialogVisible.value = true
}

const submitSeckillForm = async () => {
  if (!seckillFormRef.value) return
  
  await seckillFormRef.value.validate(async (valid) => {
    if (valid) {
      // 验证结束时间必须大于开始时间
      if (new Date(seckillForm.endTime) <= new Date(seckillForm.startTime)) {
        ElMessage.error('结束时间必须大于开始时间')
        return
      }
      
      seckillLoading.value = true
      try {
        const payload = {
          productId: seckillForm.productId,
          seckillPrice: seckillForm.seckillPrice,
          seckillStock: seckillForm.seckillStock,
          startTime: formatDateTime(seckillForm.startTime),
          endTime: formatDateTime(seckillForm.endTime)
        }
        
        await seckillApi.add(payload)
        ElMessage.success('秒杀活动添加成功')
        seckillDialogVisible.value = false
      } catch (error) {
        // handled by interceptor
      } finally {
        seckillLoading.value = false
      }
    }
  })
}

// 格式化日期时间为后端需要的格式
const formatDateTime = (date) => {
  if (!date) return null
  const d = new Date(date)
  const year = d.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hours = String(d.getHours()).padStart(2, '0')
  const minutes = String(d.getMinutes()).padStart(2, '0')
  const seconds = String(d.getSeconds()).padStart(2, '0')
  return `${year}-${month}-${day}T${hours}:${minutes}:${seconds}`
}
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.search-bar {
  margin-bottom: 20px;
}
.pagination-container {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}
.image-slot {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
  background: #f5f7fa;
  color: #909399;
}

/* Avatar Uploader Styles */
.avatar-uploader .avatar {
  width: 178px;
  height: 178px;
  display: block;
  object-fit: cover;
}
.avatar-uploader :deep(.el-upload) {
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
  cursor: pointer;
  position: relative;
  overflow: hidden;
  transition: var(--el-transition-duration-fast);
}
.avatar-uploader :deep(.el-upload:hover) {
  border-color: var(--el-color-primary);
}
.avatar-uploader-icon {
  font-size: 28px;
  color: #8c939d;
  width: 178px;
  height: 178px;
  text-align: center;
  display: flex;
  justify-content: center;
  align-items: center;
}
.image-preview-box {
  width: 178px;
  height: 178px;
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
  overflow: hidden;
  display: flex;
  justify-content: center;
  align-items: center;
  margin-top: 10px;
}
.avatar-preview {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
</style>
