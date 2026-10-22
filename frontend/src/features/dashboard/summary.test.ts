import { describe, expect, it } from 'vitest'
import type { Investment } from '../investments/types'
import type { Loan } from '../loans/types'
import type { Transaction } from '../transactions/types'
import { computeSummary, gainPercent } from './summary'

const tx = (type: Transaction['type'], amount: number, category: string | null = null): Transaction => ({
  id: crypto.randomUUID(), title: 't', amount, type, category, note: null,
  date: '2026-03-01', source: 'MANUAL', createdAt: '2026-03-01T00:00:00',
})
const inv = (amountInvested: number, currentValue: number | null): Investment => ({
  id: crypto.randomUUID(), name: 'i', type: 'STOCKS', amountInvested, currentValue, notes: null, createdAt: '2026-03-01T00:00:00',
})
const loan = (emiAmount: number, active: boolean): Loan => ({
  id: crypto.randomUUID(), name: 'l', type: 'HOME', principal: 100000, emiAmount, interestRate: 8,
  startDate: null, endDate: null, active, createdAt: '2026-03-01T00:00:00',
})

describe('computeSummary', () => {
  it('totals income and expenses separately', () => {
    const s = computeSummary([tx('INCOME', 1000), tx('INCOME', 500), tx('EXPENSE', 300)], [], [])
    expect(s.totalIncome).toBe(1500)
    expect(s.totalExpenses).toBe(300)
  })

  it('leaves transfers, card payments, investments and refunds out of income and expenses', () => {
    const s = computeSummary([
      tx('INCOME', 1000, 'INCOME'), tx('INCOME', 200, 'REFUNDS'), tx('INCOME', 300, 'TRANSFERS'),
      tx('EXPENSE', 100, 'GROCERIES'), tx('EXPENSE', 5000, 'TRANSFERS'), tx('EXPENSE', 9000, 'CARD_PAYMENTS'),
      tx('EXPENSE', 7000, 'INVESTMENTS'), tx('EXPENSE', 50), tx('EXPENSE', 25, 'UNCATEGORIZED'),
    ], [], [])
    expect(s.totalIncome).toBe(1000)
    // Groceries plus the two uncategorised rows: unknown stays in until a person decides.
    expect(s.totalExpenses).toBe(175)
  })

  it('counts only active loans toward EMI', () => {
    const s = computeSummary([], [], [loan(1000, true), loan(2000, true), loan(9999, false)])
    expect(s.activeLoans).toBe(2)
    expect(s.totalEMI).toBe(3000)
  })

  it('sums investments and treats an unvalued holding as worth what was invested', () => {
    const s = computeSummary([], [inv(1000, 1200), inv(500, null)], [])
    expect(s.totalInvested).toBe(1500)
    expect(s.totalCurrentValue).toBe(1700)
  })

  it('is all zeros for no data', () => {
    expect(computeSummary([], [], [])).toEqual({
      totalIncome: 0, totalExpenses: 0, totalInvested: 0, totalCurrentValue: 0, activeLoans: 0, totalEMI: 0,
    })
  })
})

describe('gainPercent', () => {
  it('computes the percentage change with two decimals', () => {
    expect(gainPercent(1200, 1000)).toBe('20.00')
    expect(gainPercent(900, 1000)).toBe('-10.00')
  })

  it('is 0.00 when nothing is invested', () => {
    expect(gainPercent(0, 0)).toBe('0.00')
  })
})
