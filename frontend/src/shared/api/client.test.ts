import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getRefreshToken, getToken, setToken, setTokens } from '../auth/tokenStorage'
import { fakeJwt } from '../../test/jwt'
import { apiClient, apiErrorMessage, setSessionExpiredHandler } from './client'

const originalAdapter = apiClient.defaults.adapter

afterEach(() => {
  apiClient.defaults.adapter = originalAdapter
  vi.restoreAllMocks()
})

function respondWith(status: number, data: unknown = {}) {
  apiClient.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
    const response = { data, status, statusText: '', headers: {}, config }
    if (status >= 400) {
      throw new AxiosError('failed', 'ERR_BAD_REQUEST', config, null, response)
    }
    return response
  }
}

describe('request interceptor', () => {
  it('sends the stored token as a bearer header', async () => {
    let sent: unknown
    apiClient.defaults.adapter = async (config) => {
      sent = config.headers.Authorization
      return { data: {}, status: 200, statusText: '', headers: {}, config }
    }
    setToken('tok')
    await apiClient.get('/transactions')
    expect(sent).toBe('Bearer tok')
  })

  it('sends no authorization header when signed out', async () => {
    let sent: unknown = 'unset'
    apiClient.defaults.adapter = async (config) => {
      sent = config.headers.Authorization
      return { data: {}, status: 200, statusText: '', headers: {}, config }
    }
    await apiClient.get('/transactions')
    expect(sent).toBeUndefined()
  })
})

describe('401 handling and silent refresh', () => {
  const expired = vi.fn()

  beforeEach(() => {
    expired.mockClear()
    setSessionExpiredHandler(expired)
  })

  it('clears the session and signals expiry when refresh fails', async () => {
    setTokens('stale-access', 'invalid-refresh')
    vi.spyOn(axios, 'post').mockRejectedValueOnce(new Error('Refresh failed'))
    respondWith(401)

    await expect(apiClient.get('/transactions')).rejects.toBeDefined()
    expect(getToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    expect(expired).toHaveBeenCalledOnce()
  })

  it('silently refreshes the token and retries the request on 401', async () => {
    setTokens('stale-access', 'valid-refresh')
    let attempts = 0
    let lastHeader: unknown

    apiClient.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
      attempts++
      lastHeader = config.headers.Authorization
      if (attempts === 1) {
        throw new AxiosError('unauthorized', 'ERR_BAD_REQUEST', config, null, {
          data: {}, status: 401, statusText: '', headers: {}, config,
        })
      }
      return { data: { success: true }, status: 200, statusText: '', headers: {}, config }
    }

    vi.spyOn(axios, 'post').mockResolvedValueOnce({
      data: { accessToken: 'fresh-access', refreshToken: 'fresh-refresh' },
    })

    const res = await apiClient.get<{ success: boolean }>('/transactions')
    expect(res.data.success).toBe(true)
    expect(attempts).toBe(2)
    expect(lastHeader).toBe('Bearer fresh-access')
    expect(getToken()).toBe('fresh-access')
    expect(getRefreshToken()).toBeNull()
    expect(localStorage.getItem('refreshToken')).toBeNull()
    expect(expired).not.toHaveBeenCalled()
  })

  it('merges parallel 401s into a single refresh request', async () => {
    setTokens('stale-access', 'valid-refresh')
    let refreshCalls = 0

    const postSpy = vi.spyOn(axios, 'post').mockImplementation(async () => {
      refreshCalls++
      await new Promise((resolve) => setTimeout(resolve, 20))
      return { data: { accessToken: 'new-access', refreshToken: 'new-refresh' } }
    })

    let txAttempts = 0
    let invAttempts = 0

    apiClient.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
      if (config.url === '/transactions') {
        txAttempts++
        if (txAttempts === 1) {
          throw new AxiosError('401', 'ERR_BAD_REQUEST', config, null, {
            data: {}, status: 401, statusText: '', headers: {}, config,
          })
        }
        return { data: [{ id: 1 }], status: 200, statusText: '', headers: {}, config }
      }
      if (config.url === '/investments') {
        invAttempts++
        if (invAttempts === 1) {
          throw new AxiosError('401', 'ERR_BAD_REQUEST', config, null, {
            data: {}, status: 401, statusText: '', headers: {}, config,
          })
        }
        return { data: [{ id: 2 }], status: 200, statusText: '', headers: {}, config }
      }
      return { data: {}, status: 200, statusText: '', headers: {}, config }
    }

    const [txRes, invRes] = await Promise.all([
      apiClient.get('/transactions'),
      apiClient.get('/investments'),
    ])

    expect(txRes.data).toEqual([{ id: 1 }])
    expect(invRes.data).toEqual([{ id: 2 }])
    expect(refreshCalls).toBe(1)
    expect(postSpy).toHaveBeenCalledTimes(1)
    expect(getToken()).toBe('new-access')
  })

  it('leaves the session alone when login itself answers 401', async () => {
    setToken('keep')
    respondWith(401, { message: 'Invalid email or password' })
    await expect(apiClient.post('/auth/login', {})).rejects.toBeDefined()
    expect(getToken()).toBe('keep')
    expect(expired).not.toHaveBeenCalled()
  })

  it('skips network refresh if another tab refreshed the token while acquiring lock (O-03)', async () => {
    const unexpiredToken = fakeJwt(3600)
    setTokens('stale-access')

    const originalLocks = (navigator as unknown as { locks?: unknown }).locks
    ;(navigator as unknown as { locks: unknown }).locks = {
      request: vi.fn(async (_name: string, callback: () => Promise<string | null>) => {
        // Simulate another tab having completed refresh and written new valid token to localStorage
        setToken(unexpiredToken)
        return callback()
      }),
    }

    const postSpy = vi.spyOn(axios, 'post')

    let attempts = 0
    apiClient.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
      attempts++
      if (attempts === 1) {
        throw new AxiosError('unauthorized', 'ERR_BAD_REQUEST', config, null, {
          data: {}, status: 401, statusText: '', headers: {}, config,
        })
      }
      return { data: { success: true }, status: 200, statusText: '', headers: {}, config }
    }

    try {
      const res = await apiClient.get<{ success: boolean }>('/transactions')
      expect(res.data.success).toBe(true)
      expect(postSpy).not.toHaveBeenCalled()
      expect(getToken()).toBe(unexpiredToken)
    } finally {
      ;(navigator as unknown as { locks: unknown }).locks = originalLocks
    }
  })

  it('does not treat other errors as an expired session', async () => {
    setToken('keep')
    respondWith(500)
    await expect(apiClient.get('/transactions')).rejects.toBeDefined()
    expect(getToken()).toBe('keep')
    expect(expired).not.toHaveBeenCalled()
  })
})

describe('apiErrorMessage', () => {
  const failure = (data: unknown) =>
    new AxiosError('x', 'ERR', undefined, null, {
      data,
      status: 400,
      statusText: '',
      headers: {},
      config: {} as InternalAxiosRequestConfig,
    })

  it('prefers the first field error', () => {
    expect(apiErrorMessage(failure({ message: 'Validation failed', fieldErrors: { email: 'must be valid' } }), 'fb'))
      .toBe('must be valid')
  })

  it('falls back to the server message, then to the fallback', () => {
    expect(apiErrorMessage(failure({ message: 'Email already registered' }), 'fb')).toBe('Email already registered')
    expect(apiErrorMessage(failure({}), 'fb')).toBe('fb')
    expect(apiErrorMessage(new Error('boom'), 'fb')).toBe('fb')
  })
})
