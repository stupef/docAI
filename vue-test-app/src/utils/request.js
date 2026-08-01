/**
 * @description Axios 全局请求封装工具
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { STORAGE_KEYS, RES_CODE } from '../constants'

const service = axios.create({
    baseURL: '/api',
    timeout: 300000
})

const AI_PATH_PREFIX = '/ai/'
const AIOPS_PATH_PREFIX = '/ai/aiops/'

// ----- 自动刷新 token 相关 -----
// isRefreshing 保证同一时间只有一次刷新请求，其余 401 排队等待结果
let isRefreshing = false
let refreshSubscribers = []

function subscribeTokenRefresh(cb) {
  refreshSubscribers.push(cb)
}

function onTokenRefreshed(token) {
  refreshSubscribers.forEach(cb => cb(token))
  refreshSubscribers = []
}

// 用 refreshToken 换新 accessToken（裸 axios，避免再次进入本拦截器造成循环）
async function refreshAccessToken(refreshToken) {
  const resp = await axios.post('/api/users/refresh', null, { params: { refreshToken } })
  if (resp.data && resp.data.code === 200 && resp.data.data) {
    return resp.data.data
  }
  throw new Error('refresh token 无效')
}

function isRefreshRequest(url) {
  return url && url.includes('/users/refresh')
}

function clearAuthAndRedirect() {
  localStorage.removeItem(STORAGE_KEYS.ACCESS_TOKEN)
  localStorage.removeItem(STORAGE_KEYS.REFRESH_TOKEN)
  router.push('/login')
}

function isAiRequest(url) {
  return url && url.startsWith(AI_PATH_PREFIX) && !url.startsWith(AIOPS_PATH_PREFIX)
}

function reportAiMetrics(config, hasError) {
  const url = config.url || ''
  if (!isAiRequest(url)) return

  const startTime = config._startTime
  const duration = startTime ? Date.now() - startTime : 0

  const baseURL = config.baseURL || '/api'

  axios.post(baseURL + '/ai/aiops/metrics/counter', null, {
    params: { name: 'ai.requests', delta: 1 }
  }).catch(() => {})

  if (duration > 0) {
    axios.post(baseURL + '/ai/aiops/metrics/timer', null, {
      params: { name: 'ai.request', duration }
    }).catch(() => {})
  }

  if (hasError) {
    axios.post(baseURL + '/ai/aiops/metrics/counter', null, {
      params: { name: 'ai.errors', delta: 1 }
    }).catch(() => {})
  }
}

service.interceptors.request.use(
    config => {
        config._startTime = Date.now()
        const token = localStorage.getItem(STORAGE_KEYS.ACCESS_TOKEN)
        if (token) {
            config.headers['Authorization'] = 'Bearer ' + token
        }
        return config
    },
    error => Promise.reject(error)
)

service.interceptors.response.use(
    response => {
        if (response.config.responseType === 'blob' || response.config.responseType === 'arraybuffer') {
            reportAiMetrics(response.config, false)
            return response.data
        }

        const res = response.data

        if (res && res.code === undefined) {
            reportAiMetrics(response.config, false)
            return { data: res, code: 200, message: 'success' }
        }

        if (res.code === 200 || res.code === 0) {
            reportAiMetrics(response.config, false)
            return res
        } else {
            reportAiMetrics(response.config, true)
            ElMessage.error(res.message || '操作失败')
            return Promise.reject(new Error(res.message || 'Error'))
        }
    },
    error => {
        const originalRequest = error.config
        if (error.config) {
            reportAiMetrics(error.config, true)
        }

        if (error.response && error.response.status === 401) {
            // 刷新接口自身 401，说明 refreshToken 也失效，直接登出，避免死循环
            if (isRefreshRequest(originalRequest && originalRequest.url)) {
                clearAuthAndRedirect()
                return Promise.reject(error)
            }

            const refreshToken = localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN)
            // 没有 refreshToken，无法续期，直接登出
            if (!refreshToken) {
                ElMessage.error('登录已过期，请重新登录')
                clearAuthAndRedirect()
                return Promise.reject(error)
            }

            // 第一个 401 负责刷新；刷新期间的其他 401 排队等待新 token
            if (!isRefreshing) {
                isRefreshing = true
                return refreshAccessToken(refreshToken)
                    .then(newToken => {
                        localStorage.setItem(STORAGE_KEYS.ACCESS_TOKEN, newToken)
                        onTokenRefreshed(newToken)
                        originalRequest.headers['Authorization'] = 'Bearer ' + newToken
                        return service(originalRequest)
                    })
                    .catch(() => {
                        onTokenRefreshed(null)
                        ElMessage.error('登录已过期，请重新登录')
                        clearAuthAndRedirect()
                        return Promise.reject(error)
                    })
                    .finally(() => {
                        isRefreshing = false
                    })
            } else {
                // 正在刷新中，等刷新完成后再用新 token 重试原请求
                return new Promise((resolve, reject) => {
                    subscribeTokenRefresh(token => {
                        if (token) {
                            originalRequest.headers['Authorization'] = 'Bearer ' + token
                            resolve(service(originalRequest))
                        } else {
                            reject(error)
                        }
                    })
                })
            }
        }

        ElMessage.error('服务器开了小差，请稍后再试')
        return Promise.reject(error)
    }
)

export default service
