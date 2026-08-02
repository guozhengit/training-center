import { ref } from 'vue'
import { translate } from '../i18n'

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
      throw new Error(translate('api.network', { path }))
    }
    if (!response.ok) {
      const body = await response.json().catch(() => ({}))
      throw new Error(body.message || translate('api.requestFailed', { path, status: response.status }))
    }
    return response.json()
  }

  return { fetchJson }
}
