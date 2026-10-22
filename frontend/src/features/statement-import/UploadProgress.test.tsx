import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { UploadStatus } from './types'
import UploadProgress from './UploadProgress'

const done = (patch: Partial<UploadStatus> = {}): UploadStatus => ({
  jobId: 'j', status: 'COMPLETED', bankName: 'HDFC', originalFilename: 'march.csv',
  totalRows: 10, rowsProcessed: 10, duplicateRows: 0, rejectedRows: 0, skippedRows: 0,
  rowErrors: [], closingBalance: null, statementFrom: null, statementTo: null,
  errorMessage: null, createdAt: null, completedAt: null,
  classificationStatus: 'COMPLETED', classifiedRows: 7, ...patch,
})

describe('UploadProgress categorisation notice', () => {
  it('reports how many imported transactions were categorised and how many still need a category', () => {
    render(<UploadProgress job={done()} isDone />)

    expect(screen.getByRole('status')).toHaveTextContent('7 of 10 transactions were categorized automatically.')
    expect(screen.getByRole('status')).toHaveTextContent('3 need a category')
  })

  it('says so plainly, without alarm, when the AI service was unavailable - the import still succeeded', () => {
    render(<UploadProgress job={done({ classificationStatus: 'UNAVAILABLE', classifiedRows: 0 })} isDone />)

    expect(screen.getByText(/10 transactions imported successfully/)).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Automatic categorization is unavailable right now')
    expect(screen.getByRole('status')).toHaveTextContent('imported without categories')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('mentions a partial run', () => {
    render(<UploadProgress job={done({ classificationStatus: 'PARTIAL', classifiedRows: 4 })} isDone />)
    expect(screen.getByRole('status')).toHaveTextContent('only partly completed')
  })

  it('shows nothing when classification was not attempted, nothing was imported, or an older server omits it', () => {
    const { rerender } = render(<UploadProgress job={done({ classificationStatus: 'NOT_ATTEMPTED', classifiedRows: 0 })} isDone />)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()

    rerender(<UploadProgress job={done({ rowsProcessed: 0, duplicateRows: 10 })} isDone />)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()

    rerender(<UploadProgress job={done({ classificationStatus: undefined, classifiedRows: undefined })} isDone />)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('shows no notice while the import is still running', () => {
    render(<UploadProgress job={done({ status: 'PROCESSING' })} isDone={false} />)
    expect(screen.queryByText(/categorized automatically/)).not.toBeInTheDocument()
  })
})
