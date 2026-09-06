import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useApi } from '../useApi'

describe('useApi', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('fetchJson returns parsed JSON on success', async () => {
    const mockData = { id: 'q1', title: 'Two Sum' }
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(mockData)
    })

    const { fetchJson } = useApi()
    const result = await fetchJson('/api/questions')

    expect(result).toEqual(mockData)
    expect(global.fetch).toHaveBeenCalledWith('/api/questions', expect.objectContaining({
      headers: { 'Content-Type': 'application/json' }
    }))
  })

  it('fetchJson merges custom headers', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({})
    })

    const { fetchJson } = useApi()
    await fetchJson('/api/data', { headers: { 'X-Custom': 'val' } })

    expect(global.fetch).toHaveBeenCalledWith('/api/data', expect.objectContaining({
      headers: { 'Content-Type': 'application/json', 'X-Custom': 'val' }
    }))
  })

  it('fetchJson includes configured API key header', async () => {
    localStorage.setItem('training:apiKey', ' secret-key ')
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({})
    })

    const { fetchJson } = useApi()
    await fetchJson('/api/secure')

    expect(global.fetch).toHaveBeenCalledWith('/api/secure', expect.objectContaining({
      headers: { 'Content-Type': 'application/json', 'X-API-Key': 'secret-key' }
    }))
  })

  it('fetchJson throws with server message on error', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: () => Promise.resolve({ message: '题目不存在' })
    })

    const { fetchJson } = useApi()
    await expect(fetchJson('/api/questions/x')).rejects.toThrow('题目不存在')
  })

  it('fetchJson throws generic message when body has no message', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: () => Promise.resolve({})
    })

    const { fetchJson } = useApi()
    await expect(fetchJson('/api/broken')).rejects.toThrow('/api/broken 请求失败：500')
  })

  it('fetchJson handles non-JSON error body gracefully', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 502,
      json: () => Promise.reject(new Error('not json'))
    })

    const { fetchJson } = useApi()
    await expect(fetchJson('/api/gateway')).rejects.toThrow('/api/gateway 请求失败：502')
  })
})
