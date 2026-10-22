import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { setToken } from '../../shared/auth/tokenStorage'
import { fakeJwt } from '../../test/jwt'
import PrivateRoute from './PrivateRoute'

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<p>login page</p>} />
        <Route path="/secret" element={<PrivateRoute><p>secret content</p></PrivateRoute>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('PrivateRoute', () => {
  it('redirects to /login when signed out', () => {
    renderAt('/secret')
    expect(screen.getByText('login page')).toBeInTheDocument()
    expect(screen.queryByText('secret content')).not.toBeInTheDocument()
  })

  it('redirects to /login when the stored token has expired', () => {
    setToken(fakeJwt(-60))
    renderAt('/secret')
    expect(screen.getByText('login page')).toBeInTheDocument()
  })

  it('renders the page for a signed-in user', () => {
    setToken(fakeJwt(3600))
    renderAt('/secret')
    expect(screen.getByText('secret content')).toBeInTheDocument()
  })
})
