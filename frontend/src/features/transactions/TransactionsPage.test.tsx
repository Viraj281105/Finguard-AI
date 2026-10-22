import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { transactionAPI } from './api'
import TransactionsPage from './TransactionsPage'
import type { Transaction } from './types'

vi.mock('./api', () => ({
  transactionAPI: { getAll: vi.fn(), delete: vi.fn(), create: vi.fn() },
}))

const tx = (patch: Partial<Transaction>): Transaction => ({
  id: 'id', title: 't', amount: 100, type: 'EXPENSE', category: 'Food', note: null,
  date: '2026-03-05', source: 'MANUAL',
  createdAt: '2026-03-05T10:00:00', ...patch,
})

const ROWS = [
  tx({ id: '1', title: 'Salary', amount: 50000, type: 'INCOME', category: 'Salary', date: '2026-03-01' }),
  tx({ id: '2', title: 'Swiggy order', amount: 450, type: 'EXPENSE', category: null, date: '2026-03-05' }),
]

function renderPage() {
  render(<MemoryRouter><TransactionsPage /></MemoryRouter>)
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})

describe('TransactionsPage', () => {
  it('shows a spinner, then the transactions with totals', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue(ROWS)
    renderPage()

    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(await screen.findByText('Salary', { selector: 'p' })).toBeInTheDocument()
    expect(screen.getByText('2 total entries')).toBeInTheDocument()
    expect(screen.getByText('₹50,000')).toBeInTheDocument()
    expect(screen.getByText('Uncategorized')).toBeInTheDocument()
  })

  it('displays real transaction dates', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue(ROWS)
    renderPage()

    expect(await screen.findByText('Salary', { selector: 'p' })).toBeInTheDocument()
    expect(screen.getByText('01 Mar 2026')).toBeInTheDocument()
    expect(screen.getByText('05 Mar 2026')).toBeInTheDocument()
  })

  it('filters by type', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue(ROWS)
    renderPage()
    await screen.findByText('Swiggy order')

    await userEvent.click(screen.getByRole('button', { name: 'Expense' }))
    expect(screen.getByText('Swiggy order')).toBeInTheDocument()
    expect(screen.queryByText('Salary', { selector: 'p' })).not.toBeInTheDocument()
  })

  it('shows the empty state when there is nothing to list', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([])
    renderPage()

    expect(await screen.findByText('No transactions found.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '+ Add your first transaction' })).toBeInTheDocument()
  })

  it('shows an error banner when loading fails', async () => {
    vi.mocked(transactionAPI.getAll).mockRejectedValue(new Error('down'))
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not fetch transactions.')
  })

  it('deletes a row after confirmation', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue(ROWS)
    vi.mocked(transactionAPI.delete).mockResolvedValue(undefined)
    renderPage()
    await screen.findByText('Swiggy order')

    await userEvent.click(screen.getByRole('button', { name: 'Delete Swiggy order' }))

    await waitFor(() => expect(screen.queryByText('Swiggy order')).not.toBeInTheDocument())
    expect(transactionAPI.delete).toHaveBeenCalledWith('2')
  })

  it('keeps the row and reports an error when delete fails', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue(ROWS)
    vi.mocked(transactionAPI.delete).mockRejectedValue(new Error('nope'))
    renderPage()
    await screen.findByText('Swiggy order')

    await userEvent.click(screen.getByRole('button', { name: 'Delete Swiggy order' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Failed to delete')
    expect(screen.getByText('Swiggy order')).toBeInTheDocument()
  })

  it('does nothing when the user cancels the confirmation', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue(ROWS)
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderPage()
    await screen.findByText('Swiggy order')

    await userEvent.click(screen.getByRole('button', { name: 'Delete Swiggy order' }))
    expect(transactionAPI.delete).not.toHaveBeenCalled()
  })

  it('adds a transaction through the modal and lists it', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue([])
    vi.mocked(transactionAPI.create).mockResolvedValue(
      tx({ id: '3', title: 'Freelance gig', amount: 8000, type: 'INCOME', category: 'Freelance', date: '2026-03-10' }),
    )
    renderPage()
    await screen.findByText('No transactions found.')

    await userEvent.click(screen.getByRole('button', { name: '+ Add' }))
    const dialog = screen.getByRole('dialog', { name: 'Add Transaction' })
    await userEvent.type(within(dialog).getByLabelText('Title'), 'Freelance gig')
    await userEvent.type(within(dialog).getByLabelText('Amount (₹)'), '8000')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add Transaction' }))

    expect(await screen.findByText('Freelance gig')).toBeInTheDocument()
    expect(transactionAPI.create).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Freelance gig', amount: 8000, type: 'INCOME' }),
    )
  })

  it('renders pagination controls and respects page boundaries', async () => {
    vi.mocked(transactionAPI.getAll).mockResolvedValue(ROWS)
    renderPage()

    expect(await screen.findByText('Salary', { selector: 'p' })).toBeInTheDocument()
    expect(screen.getByText(/Page 1 of 1 \(2 entries\)/)).toBeInTheDocument()
    const prevBtn = screen.getByRole('button', { name: 'Previous page' })
    const nextBtn = screen.getByRole('button', { name: 'Next page' })
    expect(prevBtn).toBeDisabled()
    expect(nextBtn).toBeDisabled()
  })
})
