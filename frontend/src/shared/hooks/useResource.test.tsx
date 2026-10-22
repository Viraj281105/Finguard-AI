import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useResource } from './useResource'

describe('useResource', () => {
  it('starts loading, then exposes the data', async () => {
    const fetcher = vi.fn().mockResolvedValue([1, 2, 3])
    const { result } = renderHook(() => useResource(fetcher, 'failed'))

    expect(result.current.loading).toBe(true)
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toEqual([1, 2, 3])
    expect(result.current.error).toBe('')
  })

  it('reports the supplied message when loading fails', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('network'))
    const { result } = renderHook(() => useResource(fetcher, 'Could not load'))

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('Could not load')
    expect(result.current.data).toBeNull()
  })

  it('reload refetches without flashing the loading state', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce('first').mockResolvedValueOnce('second')
    const { result } = renderHook(() => useResource(fetcher, 'failed'))
    await waitFor(() => expect(result.current.data).toBe('first'))

    act(() => result.current.reload())
    expect(result.current.loading).toBe(false)
    await waitFor(() => expect(result.current.data).toBe('second'))
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('update changes the loaded data locally', async () => {
    const fetcher = vi.fn().mockResolvedValue(['a'])
    const { result } = renderHook(() => useResource<string[]>(fetcher, 'failed'))
    await waitFor(() => expect(result.current.data).toEqual(['a']))

    act(() => result.current.update((prev) => [...prev, 'b']))
    expect(result.current.data).toEqual(['a', 'b'])
  })
})
