import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { uploadAPI } from './api'
import CsvUpload from './CsvUpload'
import type { UploadStatus } from './types'
import { MAX_FILE_BYTES, POLL_INTERVAL_MS, REFRESH_DELAY_MS } from './uploadConfig'

vi.mock('./api', () => ({ uploadAPI: { uploadCsv: vi.fn(), getStatus: vi.fn() } }))

const job = (patch: Partial<UploadStatus> = {}): UploadStatus => ({
  jobId: 'job-1', status: 'PENDING', bankName: 'HDFC', originalFilename: 'march.csv',
  totalRows: 0, rowsProcessed: 0, duplicateRows: 0, rejectedRows: 0, skippedRows: 0,
  rowErrors: [], closingBalance: null, statementFrom: null, statementTo: null,
  errorMessage: null, createdAt: null, completedAt: null, ...patch,
})

function selectFile(file: File) {
  fireEvent.change(screen.getByLabelText('Bank statement CSV'), { target: { files: [file] } })
}

const csvFile = () => new File(['a,b'], 'march.csv', { type: 'text/csv' })

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('CsvUpload', () => {
  it('rejects non-CSV files without calling the API', () => {
    render(<CsvUpload onUploadComplete={vi.fn()} />)
    selectFile(new File(['x'], 'statement.pdf', { type: 'application/pdf' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Please upload a CSV file.')
    expect(uploadAPI.uploadCsv).not.toHaveBeenCalled()
  })

  it('rejects files over the size limit without calling the API', () => {
    render(<CsvUpload onUploadComplete={vi.fn()} />)
    const big = new File(['x'], 'big.csv', { type: 'text/csv' })
    Object.defineProperty(big, 'size', { value: MAX_FILE_BYTES + 1 })
    selectFile(big)

    expect(screen.getByRole('alert')).toHaveTextContent('File too large')
    expect(uploadAPI.uploadCsv).not.toHaveBeenCalled()
  })

  it('uploads, polls until completion, then asks the parent to refresh', async () => {
    const onUploadComplete = vi.fn()
    vi.mocked(uploadAPI.uploadCsv).mockResolvedValue(job())
    vi.mocked(uploadAPI.getStatus)
      .mockResolvedValueOnce(job({ status: 'PROCESSING', totalRows: 3 }))
      .mockResolvedValueOnce(job({ status: 'COMPLETED', totalRows: 3, rowsProcessed: 3 }))

    render(<CsvUpload onUploadComplete={onUploadComplete} />)
    selectFile(csvFile())
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(uploadAPI.uploadCsv).toHaveBeenCalledOnce()

    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })
    expect(screen.getByText('PROCESSING')).toBeInTheDocument()

    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })
    expect(screen.getByText('COMPLETED')).toBeInTheDocument()
    expect(screen.getByText(/3 transactions imported successfully/)).toBeInTheDocument()
    expect(onUploadComplete).not.toHaveBeenCalled()

    await act(async () => { await vi.advanceTimersByTimeAsync(REFRESH_DELAY_MS) })
    expect(onUploadComplete).toHaveBeenCalledOnce()
  })

  it('shows the failure reason when the import fails', async () => {
    vi.mocked(uploadAPI.uploadCsv).mockResolvedValue(job())
    vi.mocked(uploadAPI.getStatus).mockResolvedValue(
      job({ status: 'FAILED', errorMessage: 'Unrecognized CSV format.' }),
    )

    render(<CsvUpload onUploadComplete={vi.fn()} />)
    selectFile(csvFile())
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })

    expect(screen.getByRole('alert')).toHaveTextContent('Unrecognized CSV format.')
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('shows an error when the upload request fails', async () => {
    vi.mocked(uploadAPI.uploadCsv).mockRejectedValue(new Error('network'))

    render(<CsvUpload onUploadComplete={vi.fn()} />)
    selectFile(csvFile())
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })

    expect(screen.getByRole('alert')).toHaveTextContent('Upload failed')
  })

  it('stops polling when it unmounts', async () => {
    vi.mocked(uploadAPI.uploadCsv).mockResolvedValue(job())
    vi.mocked(uploadAPI.getStatus).mockResolvedValue(job({ status: 'PROCESSING' }))

    const { unmount } = render(<CsvUpload onUploadComplete={vi.fn()} />)
    selectFile(csvFile())
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })
    const callsBefore = vi.mocked(uploadAPI.getStatus).mock.calls.length

    unmount()
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3) })
    expect(vi.mocked(uploadAPI.getStatus).mock.calls.length).toBe(callsBefore)
  })

  it('displays full import breakdown and row errors upon completion', async () => {
    vi.mocked(uploadAPI.uploadCsv).mockResolvedValue(job())
    vi.mocked(uploadAPI.getStatus).mockResolvedValue(
      job({
        status: 'COMPLETED',
        totalRows: 10,
        rowsProcessed: 6,
        duplicateRows: 2,
        skippedRows: 1,
        rejectedRows: 1,
        rowErrors: [{ rowNumber: 8, errorMessage: 'Cannot parse date: notadate', snippet: 'notadate,INVALID ROW' }],
        closingBalance: 75450.00,
        statementFrom: '2026-03-01',
        statementTo: '2026-03-05',
      }),
    )

    render(<CsvUpload onUploadComplete={vi.fn()} />)
    selectFile(csvFile())
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })

    expect(screen.getByText('COMPLETED')).toBeInTheDocument()
    expect(screen.getByText(/6 transactions imported successfully/)).toBeInTheDocument()
    expect(screen.getByText(/2 duplicate entries were detected/)).toBeInTheDocument()
    expect(screen.getByText(/1 non-financial row was skipped/)).toBeInTheDocument()
    expect(screen.getByText(/1 row was rejected due to format errors/)).toBeInTheDocument()
    expect(screen.getByText(/Closing Balance: ₹75,450.00/)).toBeInTheDocument()

    // Expand error details
    fireEvent.click(screen.getByRole('button', { name: 'View errors' }))
    expect(screen.getByText(/Line 8:/)).toBeInTheDocument()
    expect(screen.getByText(/Cannot parse date: notadate/)).toBeInTheDocument()
  })
})
