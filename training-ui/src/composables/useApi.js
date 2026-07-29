import { ref } from 'vue'

export function useApi() {
  async function fetchJson(path, options = {}) {
    const { headers: customHeaders, ...rest } = options
    const response = await fetch(path, {
      ...rest,
      headers: { 'Content-Type': 'application/json', ...(customHeaders ?? {}) }
    })
    if (!response.ok) {
      const body = await response.json().catch(() => ({}))
      throw new Error(body.message || `${path} 请求失败：${response.status}`)
    }
    return response.json()
  }

  return { fetchJson }
}
