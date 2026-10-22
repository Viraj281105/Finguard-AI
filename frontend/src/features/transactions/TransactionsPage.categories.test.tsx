import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { transactionAPI } from './api'
import TransactionsPage from './TransactionsPage'
import type { Transaction } from './types'

vi.mock('./api', () => ({
  transactionAPI: {
    getAll: vi.fn(), delete: vi.fn(), create: vi.fn(), updateCategory: vi.fn(), reclassify: vi.fn(),
  },
}))

const tx = (patch: Partial<Transaction>): Transaction => ({
  id: 'id', title: 't', amount: 100, type: 'EXPENSE', category: null, note: null,
  date: '2026-03-05', source: 'MANUAL', categorySource: 'NONE',
  createdAt: '2026-03-05T10:00:00', ...patch,
})

const swiggy = tx({
  id: '2', title: 'Swiggy order', category: 'DINING', categorySource: 'RULE',
  categoryConfidence: 0.9, categoryConfidenceCalibrated: false, categoryNeedsReview: false,
  categoryRule: 'dining.swiggy', categoryModelVersion: 'rules-1.0.0', source: 'IMPORT',
})
const mine = tx({ id: '3', title: 'Rent March', category: 'HOUSING', categorySource: 'USER' })
const none = tx({ id: '4', title: 'UPI-RAMESH', category: null, categorySource: 'NONE', source: 'IMPORT' })

function renderPage() {
  render(<MemoryRouter><TransactionsPage /></MemoryRouter>)
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('transaction categories', () => {
  it('tells automatic, confirmed and missing categories apart', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([swiggy, mine, none])
    renderPage()
    await screen.findByText('Swiggy order')

    const auto = screen.getByRole('button', { name: 'Change category for Swiggy order' })
    expect(auto).toHaveTextContent('Dining')
    expect(auto.closest('td')).toHaveTextContent('Auto')
    expect(auto.closest('td')).not.toHaveTextContent('✓')

    const confirmed = screen.getByRole('button', { name: 'Change category for Rent March' })
    expect(confirmed).toHaveTextContent('Housing')
    expect(confirmed.closest('td')).toHaveTextContent('✓')
    expect(confirmed.closest('td')).toHaveTextContent('Confirmed by you')
    expect(confirmed.closest('td')).not.toHaveTextContent('Auto')

    expect(screen.getByRole('button', { name: 'Change category for UPI-RAMESH' })).toHaveTextContent('Uncategorized')
  })

  it('shows a confidence percentage only when it is calibrated', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([
      swiggy,
      tx({
        id: '5', title: 'Calibrated one', category: 'SHOPPING', categorySource: 'MODEL',
        categoryConfidence: 0.93, categoryConfidenceCalibrated: true,
      }),
    ])
    renderPage()
    await screen.findByText('Swiggy order')

    expect(screen.queryByText('90%')).not.toBeInTheDocument() // a rule-strength tier is not a probability
    expect(screen.getByText('93%')).toBeInTheDocument()
  })

  it('flags weak automatic categories for review and offers the review queue', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([
      tx({
        id: '6', title: 'NEFT-SOME PERSON', category: 'TRANSFERS', categorySource: 'RULE',
        categoryConfidence: 0.55, categoryNeedsReview: true,
      }),
    ])
    renderPage()
    await screen.findByText('NEFT-SOME PERSON')

    expect(screen.getByText('Review')).toBeInTheDocument()
    expect(screen.getByText(/1 automatic category is worth a quick check/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Needs review' }))
    expect(transactionAPI.getAll).toHaveBeenLastCalledWith(expect.objectContaining({ needsReview: true }))
  })

  it('lets the user correct a category from a list limited to what suits the direction', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([swiggy])
    vi.mocked(transactionAPI.updateCategory).mockResolvedValue({
      ...swiggy, category: 'GROCERIES', categorySource: 'USER', categoryConfidence: null, categoryRule: null,
    })
    renderPage()
    await screen.findByText('Swiggy order')

    await userEvent.click(screen.getByRole('button', { name: 'Change category for Swiggy order' }))
    const picker = screen.getByRole('combobox', { name: 'Category for Swiggy order' })
    const offered = within(picker).getAllByRole('option').map((o) => o.textContent)
    expect(offered).toContain('Groceries')
    expect(offered).toContain('Credit card payments')
    expect(offered).not.toContain('Income') // an expense cannot be income
    expect(offered).not.toContain('Refunds & reversals')

    await userEvent.selectOptions(picker, 'GROCERIES')

    expect(transactionAPI.updateCategory).toHaveBeenCalledWith('2', 'GROCERIES')
    const cell = (await screen.findByRole('button', { name: 'Change category for Swiggy order' })).closest('td')!
    expect(cell).toHaveTextContent('Groceries')
    expect(cell).toHaveTextContent('✓')
    expect(cell).not.toHaveTextContent('Auto')
  })

  it('confirms an automatic category in one click, making it the user choice', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([swiggy])
    vi.mocked(transactionAPI.updateCategory).mockResolvedValue({ ...swiggy, categorySource: 'USER' })
    renderPage()
    await screen.findByText('Swiggy order')

    await userEvent.click(screen.getByRole('button', { name: 'Confirm category for Swiggy order' }))

    expect(transactionAPI.updateCategory).toHaveBeenCalledWith('2', 'DINING')
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Confirm category for Swiggy order' })).not.toBeInTheDocument())
  })

  it('keeps the old category and says so when saving fails', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([swiggy])
    vi.mocked(transactionAPI.updateCategory).mockRejectedValue(new Error('500'))
    renderPage()
    await screen.findByText('Swiggy order')

    await userEvent.click(screen.getByRole('button', { name: 'Change category for Swiggy order' }))
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Category for Swiggy order' }), 'FEES')

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not save the category')
    expect(screen.getByRole('button', { name: 'Change category for Swiggy order' })).toHaveTextContent('Dining')
  })

  it('closes the picker with Escape without saving', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([swiggy])
    renderPage()
    await screen.findByText('Swiggy order')

    await userEvent.click(screen.getByRole('button', { name: 'Change category for Swiggy order' }))
    await userEvent.keyboard('{Escape}')

    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    expect(transactionAPI.updateCategory).not.toHaveBeenCalled()
  })

  it('filters to transactions that need a category', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([swiggy, none])
    renderPage()
    await screen.findByText('Swiggy order')

    await userEvent.click(screen.getByRole('button', { name: 'Needs a category' }))

    expect(transactionAPI.getAll).toHaveBeenLastCalledWith(expect.objectContaining({ category: 'UNCATEGORIZED' }))
    expect(screen.queryByText('Swiggy order')).not.toBeInTheDocument()
    expect(screen.getByText('UPI-RAMESH')).toBeInTheDocument()
  })

  it('categorizes automatically and reports the outcome', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([none])
    vi.mocked(transactionAPI.reclassify).mockResolvedValue({
      status: 'COMPLETED', attempted: 1, categorized: 0, needsReview: 0, stillUncategorized: 1,
    })
    renderPage()
    await screen.findByText('UPI-RAMESH')
    expect(screen.getByText(/1 transaction needs a category on this page/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Categorize automatically' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Categorized 0 of 1 transactions.')
    expect(screen.getByRole('status')).toHaveTextContent('1 still needs a category')
    expect(transactionAPI.getAll).toHaveBeenCalledTimes(2) // list refreshed afterwards
  })

  it('explains an unavailable AI service without alarming the user about their data', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([none])
    vi.mocked(transactionAPI.reclassify).mockResolvedValue({
      status: 'UNAVAILABLE', attempted: 1, categorized: 0, needsReview: 0, stillUncategorized: 1,
    })
    renderPage()
    await screen.findByText('UPI-RAMESH')

    await userEvent.click(screen.getByRole('button', { name: 'Categorize automatically' }))

    expect(await screen.findByRole('status')).toHaveTextContent('unavailable right now')
    expect(screen.getByRole('status')).toHaveTextContent('transactions are safe')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByText('UPI-RAMESH')).toBeInTheDocument()
  })

  it('shows an error when the categorize request itself fails', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([none])
    vi.mocked(transactionAPI.reclassify).mockRejectedValue(new Error('network'))
    renderPage()
    await screen.findByText('UPI-RAMESH')

    await userEvent.click(screen.getByRole('button', { name: 'Categorize automatically' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach the server to categorize')
  })

  it('does not offer automatic categorization when nothing needs a category', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([mine])
    renderPage()
    await screen.findByText('Rent March')

    expect(screen.queryByRole('button', { name: 'Categorize automatically' })).not.toBeInTheDocument()
  })

  it('keeps transfers, card payments, investments and refunds out of the income and expense totals', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([
      tx({ id: 'a', title: 'Groceries', amount: 450, type: 'EXPENSE', category: 'GROCERIES', categorySource: 'USER' }),
      tx({ id: 'b', title: 'To savings', amount: 5000, type: 'EXPENSE', category: 'TRANSFERS', categorySource: 'RULE' }),
      tx({ id: 'c', title: 'Card bill', amount: 18000, type: 'EXPENSE', category: 'CARD_PAYMENTS', categorySource: 'RULE' }),
      tx({ id: 'd', title: 'Mutual fund', amount: 10000, type: 'EXPENSE', category: 'INVESTMENTS', categorySource: 'RULE' }),
      tx({ id: 'e', title: 'Pay', amount: 75000, type: 'INCOME', category: 'INCOME', categorySource: 'RULE' }),
      tx({ id: 'f', title: 'Amazon refund', amount: 499, type: 'INCOME', category: 'REFUNDS', categorySource: 'RULE' }),
    ])
    renderPage()
    await screen.findByText('Groceries', { selector: 'p' })

    expect(screen.getByText('₹450')).toBeInTheDocument() // expenses: only the grocery spend
    expect(screen.getByText('₹75,000')).toBeInTheDocument() // income: the salary, not the refund
    expect(screen.queryByText('₹33,450')).not.toBeInTheDocument()
  })

  it('offers only categories that suit the type when adding a transaction', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([])
    renderPage()
    await screen.findByText('No transactions found.')

    await userEvent.click(screen.getByRole('button', { name: '+ Add' }))
    const dialog = screen.getByRole('dialog', { name: 'Add Transaction' })
    const picker = within(dialog).getByLabelText('Category')
    expect(within(picker).getAllByRole('option').map((o) => o.textContent)).toContain('Income')

    await userEvent.click(within(dialog).getByRole('button', { name: 'EXPENSE' }))
    const options = within(picker).getAllByRole('option').map((o) => o.textContent)
    expect(options).toContain('No category yet')
    expect(options).toContain('Groceries')
    expect(options).not.toContain('Income')
    expect(picker).toHaveValue('') // the income category no longer applies, so it was cleared
  })

  it('falls back to the raw text for a category id it does not know', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([
      tx({ id: 'z', title: 'Odd', category: 'FUTURE_THING', categorySource: 'USER' }),
    ])
    renderPage()
    await screen.findByText('Odd')

    expect(screen.getByRole('button', { name: 'Change category for Odd' })).toHaveTextContent('FUTURE_THING')
  })
})
