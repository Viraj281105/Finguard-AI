import { describe, expect, it } from 'vitest'
import contractSource from '../../../contracts/categories.json?raw'
import {
  CATEGORIES, categoriesForType, categoryInfo, categoryLabel, countsTowardTotals,
} from './categories'

interface ContractCategory { id: string; label: string; flow: string; types: string[] }

const contract: { categories: ContractCategory[] } = JSON.parse(contractSource)

describe('category taxonomy', () => {
  it('matches the shared contract exactly (ids, labels, flows, directions, order)', () => {
    expect(CATEGORIES.map((c) => ({ id: c.id, label: c.label, flow: c.flow, types: [...c.types] })))
      .toEqual(contract.categories)
  })

  it('labels known ids and falls back gracefully', () => {
    expect(categoryLabel('LOAN_PAYMENTS')).toBe('Loan payments')
    expect(categoryLabel(null)).toBe('Uncategorized')
    expect(categoryLabel('FUTURE_CATEGORY')).toBe('FUTURE_CATEGORY')
    expect(categoryInfo('NOPE')).toBeUndefined()
  })

  it('offers only categories that suit the direction', () => {
    const income = categoriesForType('INCOME').map((c) => c.id)
    const expense = categoriesForType('EXPENSE').map((c) => c.id)
    expect(income).toContain('INCOME')
    expect(income).toContain('REFUNDS')
    expect(income).not.toContain('GROCERIES')
    expect(expense).toContain('GROCERIES')
    expect(expense).toContain('CARD_PAYMENTS')
    expect(expense).not.toContain('INCOME')
    expect(expense).not.toContain('REFUNDS')
  })

  it('keeps money movements out of income and expense totals', () => {
    for (const id of ['TRANSFERS', 'CARD_PAYMENTS', 'INVESTMENTS', 'REFUNDS']) {
      expect(countsTowardTotals(id)).toBe(false)
    }
    for (const id of ['GROCERIES', 'INCOME', 'LOAN_PAYMENTS', 'UNCATEGORIZED', null]) {
      expect(countsTowardTotals(id)).toBe(true)
    }
  })
})
