<template>
  <div class="login-page">
    <div class="brand-blob blob-1"></div>
    <div class="brand-blob blob-2"></div>

    <!-- 顶部导航栏 -->
    <header class="topbar">
      <div class="topbar-brand">
        <span class="topbar-logo"><el-icon><MagicStick /></el-icon></span>
        <div class="topbar-name">
          <strong>DocAI</strong>
          <span>智能文档平台</span>
        </div>
      </div>

      <div class="topbar-contact">
        <span class="contact-item"><el-icon><Phone /></el-icon> 400-888-8888</span>
        <span class="contact-item"><el-icon><Message /></el-icon> support@docai.com</span>
      </div>
    </header>

    <!-- 居中主体 -->
    <main class="main">
      <div class="auth-container">
        <!-- 居中品牌区 -->
        <div class="hero">
          <span class="hero-badge"><el-icon><MagicStick /></el-icon></span>
          <h1 class="hero-title">DocAI 智能文档处理平台</h1>
          <p class="hero-subtitle">让 AI 读懂、检索并协作你的每一份文档</p>
        </div>

        <!-- 居中登录卡片 -->
        <div class="form-card">
          <div class="form-header">
            <h2>欢迎回来</h2>
            <p>登录以继续使用 DocAI 智能平台</p>
          </div>

          <el-form :model="form" label-position="top" @submit.prevent>
            <el-form-item label="用户名">
              <el-input
                v-model="form.username"
                placeholder="请输入用户名"
                size="large"
                prefix-icon="User"
              />
            </el-form-item>

            <el-form-item label="密码">
              <el-input
                v-model="form.password"
                type="password"
                show-password
                placeholder="请输入密码"
                size="large"
                prefix-icon="Lock"
                @keyup.enter="handleLogin"
              />
            </el-form-item>

            <el-button
              type="primary"
              class="submit-btn"
              size="large"
              @click="handleLogin"
              :loading="loading"
            >
              登 录
            </el-button>

            <div class="footer-text">
              <router-link to="/register">没有账号？免费注册</router-link>
            </div>
          </el-form>

          <!-- 能力亮点：卡片底部居中小标签 -->
          <div class="feature-row">
            <div class="feature-chip"><el-icon><ChatDotRound /></el-icon><span>AI 问答</span></div>
            <div class="feature-chip"><el-icon><Document /></el-icon><span>知识库</span></div>
            <div class="feature-chip"><el-icon><Connection /></el-icon><span>实时协同</span></div>
            <div class="feature-chip"><el-icon><Lock /></el-icon><span>企业安全</span></div>
          </div>
        </div>

        <div class="brand-footer">© 2026 DocAI · 企业级文档智能平台</div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userApi } from '../../api/user'
import { STORAGE_KEYS } from '../../constants'

const router = useRouter()
const loading = ref(false)
const form = ref({ username: '', password: '' })

const handleLogin = async () => {
  if (!form.value.username || !form.value.password) {
    return ElMessage.warning('请填写完整的账号和密码')
  }
  loading.value = true
  try {
    const res = await userApi.login(form.value)

    // 保存 Token
    localStorage.setItem(STORAGE_KEYS.ACCESS_TOKEN, res.data.accessToken)
    localStorage.setItem(STORAGE_KEYS.REFRESH_TOKEN, res.data.refreshToken)
    localStorage.setItem('userId', res.data.user.id)

    ElMessage.success('登录成功！')
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* ===== 整页柔和浅色渐变 + 字体渲染优化 ===== */
.login-page {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  width: 100%;
  overflow: hidden;
  box-sizing: border-box;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto,
    'Helvetica Neue', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  text-rendering: optimizeLegibility;
  background: linear-gradient(135deg, #eef2ff 0%, #f5f3ff 50%, #fdf2f8 100%);
}

.brand-blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.5;
  pointer-events: none;
  z-index: 0;
}
.blob-1 {
  width: 460px; height: 460px;
  background: #c7d2fe;
  top: -160px; left: -120px;
}
.blob-2 {
  width: 400px; height: 400px;
  background: #fbcfe8;
  bottom: -160px; right: -80px;
}

/* ===== 顶部导航栏 ===== */
.topbar {
  position: relative;
  z-index: 3;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 32px;
  background: #ffffff;
  border-bottom: 1px solid #eef0f4;
  box-shadow: 0 2px 12px rgba(79, 70, 229, 0.06);
}
.topbar-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.topbar-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: linear-gradient(135deg, #a5b4fc 0%, #c4b5fd 100%);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.6),
    0 8px 18px rgba(99, 102, 241, 0.2);
}
.topbar-logo .el-icon {
  font-size: 22px;
  color: #ffffff;
}
.topbar-name {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}
.topbar-name strong {
  font-size: 18px;
  font-weight: 800;
  letter-spacing: 0.02em;
  color: #3730a3;
}
.topbar-name span {
  font-size: 12px;
  color: #64748b;
  margin-top: 2px;
}

.topbar-contact {
  display: flex;
  align-items: center;
  gap: 22px;
}
.contact-item {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 13.5px;
  color: #475569;
}
.contact-item .el-icon {
  font-size: 16px;
  color: #6366f1;
}

/* ===== 居中主体 ===== */
.main {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px 20px 32px;
  box-sizing: border-box;
}

.auth-container {
  width: 100%;
  max-width: 420px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  animation: fadeInUp 0.7s ease both;
}

/* ===== 品牌区（居中） ===== */
.hero {
  margin-bottom: 28px;
}
.hero-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 60px;
  height: 60px;
  border-radius: 18px;
  background: linear-gradient(135deg, #a5b4fc 0%, #c4b5fd 100%);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.6),
    0 10px 22px rgba(99, 102, 241, 0.22);
}
.hero-badge .el-icon {
  font-size: 32px;
  color: #ffffff;
}
.hero-title {
  margin: 18px 0 10px;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.01em;
  line-height: 1.3;
  color: #3730a3;
}
.hero-subtitle {
  font-size: 14.5px;
  line-height: 1.6;
  letter-spacing: 0.01em;
  color: #64748b;
}

/* ===== 登录卡片 ===== */
.form-card {
  width: 100%;
  padding: 40px 38px 28px;
  background: #ffffff;
  border-radius: 22px;
  box-shadow: 0 18px 50px rgba(79, 70, 229, 0.12), 0 2px 8px rgba(79, 70, 229, 0.06);
  box-sizing: border-box;
}

.form-header {
  text-align: center;
  margin-bottom: 26px;
}
.form-header h2 {
  font-size: 23px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: #1f2937;
  margin-bottom: 8px;
}
.form-header p {
  font-size: 14px;
  letter-spacing: 0.01em;
  color: #6b7280;
}

/* 表单项排版精修 */
.form-card :deep(.el-form-item) {
  margin-bottom: 18px;
}
.form-card :deep(.el-form-item__label) {
  font-size: 13.5px;
  font-weight: 500;
  color: #374151;
  padding-bottom: 6px;
  line-height: 1.4;
}
.form-card :deep(.el-input__wrapper) {
  border-radius: 11px;
  padding: 1px 14px;
  background-color: #f9fafb;
  box-shadow: 0 0 0 1px #e5e7eb inset;
  transition: box-shadow 0.2s ease, background-color 0.2s ease;
}
.form-card :deep(.el-input__wrapper.is-focus) {
  background-color: #fff;
  box-shadow: 0 0 0 1px #6366f1 inset, 0 0 0 3px rgba(99, 102, 241, 0.15);
}
.form-card :deep(.el-input__inner) {
  font-size: 14.5px;
  height: 44px;
}
.form-card :deep(.el-input__inner::placeholder) {
  color: #9ca3af;
}

.submit-btn {
  width: 100%;
  margin-top: 6px;
  font-size: 15.5px;
  font-weight: 600;
  letter-spacing: 4px;
  border: none;
  border-radius: 11px;
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
  box-shadow: 0 8px 20px rgba(99, 102, 241, 0.25);
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.submit-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 12px 26px rgba(99, 102, 241, 0.34);
}

.footer-text {
  text-align: center;
  margin-top: 18px;
  font-size: 14px;
  letter-spacing: 0.01em;
  color: #6b7280;
}
.footer-text a {
  color: #6366f1;
  font-weight: 600;
  text-decoration: none;
}
.footer-text a:hover {
  text-decoration: underline;
}

/* 能力亮点小标签 */
.feature-row {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 14px 22px;
  margin-top: 22px;
  padding-top: 20px;
  border-top: 1px solid #eef0f4;
}
.feature-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #64748b;
}
.feature-chip .el-icon {
  font-size: 16px;
  color: #6366f1;
}

.brand-footer {
  margin-top: 22px;
  font-size: 13px;
  letter-spacing: 0.02em;
  color: #94a3b8;
}

/* ===== 动画 ===== */
@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(16px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* ===== 响应式 ===== */
@media (max-width: 600px) {
  .topbar {
    padding: 12px 20px;
  }
  .topbar-contact {
    display: none;
  }
}
@media (max-width: 480px) {
  .form-card {
    padding: 32px 22px 24px;
  }
  .hero-title {
    font-size: 23px;
  }
}
</style>
