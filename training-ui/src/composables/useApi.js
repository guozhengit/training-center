import { ref } from 'vue'

export function useApi() {
  async function fetchJson(path, options = {}) {
    const { headers: customHeaders, ...rest } = options
    let response
    try {
      response = await fetch(path, {
        ...rest,
        headers: { 'Content-Type': 'application/json', ...(customHeaders ?? {}) }
      })
    } catch (networkError) {
      throw new Error(`网络连接失败，请检查网络后重试（${path}）`)
    }
    if (!response.ok) {
      const body = await response.json().catch(() => ({}))
      throw new Error(body.message || `${path} 请求失败：${response.status}`)
    }
    return response.json()
  }

  return { fetchJson }
}
