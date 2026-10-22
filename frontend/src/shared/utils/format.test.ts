import { describe, expect, it } from 'vitest'
import { formatCurrency, formatDate } from './format'

describe('formatCurrency', () => {
  it('uses Indian digit grouping and whole rupees', () => {
    expect(formatCurrency(1234567)).toBe('₹12,34,567')
    expect(formatCurrency(0)).toBe('₹0')
  })

  it('rounds to whole rupees', () => {
    expect(formatCurrency(99.6)).toBe('₹100')
  })
})

describe('formatDate', () => {
  it('formats an ISO date', () => {
    expect(formatDate('2026-03-05T10:00:00')).toBe('05 Mar 2026')
  })

  it('returns a dash for missing or invalid values', () => {
    expect(formatDate(null)).toBe('—')
    expect(formatDate(undefined)).toBe('—')
    expect(formatDate('')).toBe('—')
    expect(formatDate('not a date')).toBe('—')
  })
})
