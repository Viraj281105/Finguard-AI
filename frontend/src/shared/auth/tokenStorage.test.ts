import { describe, expect, it } from 'vitest'
import { fakeJwt } from '../../test/jwt'
import { clearToken, getRefreshToken, getToken, isAuthenticated, setToken, setTokens } from './tokenStorage'

describe('tokenStorage', () => {
  it('stores and clears the token under the legacy "token" key', () => {
    setToken('abc')
    expect(localStorage.getItem('token')).toBe('abc')
    expect(getToken()).toBe('abc')
    clearToken()
    expect(getToken()).toBeNull()
  })

  it('stores and clears both access and refresh tokens with setTokens and clearToken', () => {
    setTokens('access-val', 'refresh-val')
    expect(getToken()).toBe('access-val')
    expect(getRefreshToken()).toBe('refresh-val')
    clearToken()
    expect(getToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
  })

  it('is not authenticated without a token', () => {
    clearToken()
    expect(isAuthenticated()).toBe(false)
  })

  it('is authenticated with an unexpired token', () => {
    setToken(fakeJwt(3600))
    expect(isAuthenticated()).toBe(true)
  })

  it('is not authenticated once the token has expired', () => {
    setToken(fakeJwt(-10))
    expect(isAuthenticated()).toBe(false)
  })

  it('treats a token it cannot decode as present (the server decides)', () => {
    setToken('opaque-token')
    expect(isAuthenticated()).toBe(true)
  })
})
