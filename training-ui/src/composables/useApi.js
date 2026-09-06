import { translate } from '../i18n'

const DEFAULT_TIMEOUT_MS = 30000
const API_KEY_STORAGE_KEY = 'training:apiKey'

export function configuredApiKey() {
  try {
    const stored = localStorage.getItem(API_KEY_STORAGE_KEY)?.trim()
    if (stored) return stored
  } catch { /* storage unavailable, ignore */ }
  return import.meta.env?.VITE_TRAINING_API_KEY?.trim() || ''
}

export function apiHeaders(customHeaders = {}) {
  const headers = { 'Content-Type': 'application/json', ...(customHeaders ?? {}) }
  const key = configuredApiKey()
  const hasApiKeyHeader = Object.keys(headers).some((name) => name.toLowerCase() === 'x-api-key')
  if (key && !hasApiKeyHeader) {
    headers['X-API-Key'] = key
  }
  return headers
}

export function useApi({ timeout = DEFAULT_TIMEOUT_MS } = {}) {
  async function fetchJson(path, options = {}) {
    const { headers: customHeaders, ...rest } = options
    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), timeout)
    let response
    try {
      response = await fetch(path, {
        ...rest,
        headers: apiHeaders(customHeaders),
        signal: controller.signal
      })
    } catch (networkError) {
      if (networkError.name === 'AbortError') {
        throw new Error(translate('api.timeout', { path, timeout }))
      }
      throw new Error(translate('api.network', { path }))
    } finally {
      clearTimeout(timeoutId)
    }
    if (!response.ok) {
      const body = await response.json().catch(() => ({}))
      throw new Error(body.message || translate('api.requestFailed', { path, status: response.status }))
    }
    return response.json()
  }

  return { fetchJson }
}
