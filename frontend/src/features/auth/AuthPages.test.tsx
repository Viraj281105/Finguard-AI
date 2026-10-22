import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getToken } from '../../shared/auth/tokenStorage'
import { authAPI } from './api'
import LoginPage from './LoginPage'
import RegisterPage from './RegisterPage'

vi.mock('./api', () => ({ authAPI: { login: vi.fn(), register: vi.fn() } }))

const session = { accessToken: 'access-1', refreshToken: 'refresh-1', email: 'a@example.com', name: 'Asha' }

function renderPage(page: React.ReactNode, path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={path === '/login' ? page : <p>login page</p>} />
        <Route path="/register" element={path === '/register' ? page : <p>register page</p>} />
        <Route path="/dashboard" element={<p>dashboard page</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

const serverError = (status: number, data: unknown) =>
  new AxiosError('x', 'ERR', undefined, null, {
    data, status, statusText: '', headers: {}, config: {} as InternalAxiosRequestConfig,
  })

beforeEach(() => vi.clearAllMocks())

describe('LoginPage', () => {
  it('signs in, stores the access token and opens the dashboard', async () => {
    vi.mocked(authAPI.login).mockResolvedValue(session)
    renderPage(<LoginPage />, '/login')

    await userEvent.type(screen.getByLabelText('Email'), 'a@example.com')
    await userEvent.type(screen.getByLabelText('Password'), 'secret123')
    await userEvent.click(screen.getByRole('button', { name: 'Sign In' }))

    expect(await screen.findByText('dashboard page')).toBeInTheDocument()
    expect(authAPI.login).toHaveBeenCalledWith({ email: 'a@example.com', password: 'secret123' })
    expect(getToken()).toBe('access-1')
  })

  it('shows the server message and stores nothing when sign-in fails', async () => {
    vi.mocked(authAPI.login).mockRejectedValue(serverError(401, { message: 'Invalid email or password' }))
    renderPage(<LoginPage />, '/login')

    await userEvent.type(screen.getByLabelText('Email'), 'a@example.com')
    await userEvent.type(screen.getByLabelText('Password'), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: 'Sign In' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password')
    expect(getToken()).toBeNull()
  })

  it('disables the button while the request is in flight', async () => {
    let resolve!: (value: typeof session) => void
    vi.mocked(authAPI.login).mockReturnValue(new Promise((r) => { resolve = r }))
    renderPage(<LoginPage />, '/login')

    await userEvent.type(screen.getByLabelText('Email'), 'a@example.com')
    await userEvent.type(screen.getByLabelText('Password'), 'secret123')
    await userEvent.click(screen.getByRole('button', { name: 'Sign In' }))

    expect(screen.getByRole('button', { name: 'Signing in...' })).toBeDisabled()
    resolve(session)
    await screen.findByText('dashboard page')
  })
})

describe('RegisterPage', () => {
  // Regression: registration used to read `res.data.accessToken` from an already-unwrapped
  // response, so a successful sign-up showed "Registration failed" and never stored a usable token.
  it('creates the account, stores the access token and opens the dashboard', async () => {
    vi.mocked(authAPI.register).mockResolvedValue(session)
    renderPage(<RegisterPage />, '/register')

    await userEvent.type(screen.getByLabelText('Full Name'), 'Asha')
    await userEvent.type(screen.getByLabelText('Email'), 'a@example.com')
    await userEvent.type(screen.getByLabelText('Password'), 'secret12345')
    await userEvent.click(screen.getByRole('button', { name: 'Create Account' }))

    expect(await screen.findByText('dashboard page')).toBeInTheDocument()
    expect(authAPI.register).toHaveBeenCalledWith({ name: 'Asha', email: 'a@example.com', password: 'secret12345' })
    expect(getToken()).toBe('access-1')
  })

  it('shows "Email already registered" for a duplicate account', async () => {
    vi.mocked(authAPI.register).mockRejectedValue(serverError(409, { message: 'Email already registered' }))
    renderPage(<RegisterPage />, '/register')

    await userEvent.type(screen.getByLabelText('Full Name'), 'Asha')
    await userEvent.type(screen.getByLabelText('Email'), 'a@example.com')
    await userEvent.type(screen.getByLabelText('Password'), 'secret12345')
    await userEvent.click(screen.getByRole('button', { name: 'Create Account' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Email already registered'))
    expect(getToken()).toBeNull()
  })

  it('shows the first field error from a validation response', async () => {
    vi.mocked(authAPI.register).mockRejectedValue(
      serverError(400, { message: 'Validation failed', fieldErrors: { password: 'Password must be at least 10 characters' } }),
    )
    renderPage(<RegisterPage />, '/register')

    await userEvent.type(screen.getByLabelText('Full Name'), 'Asha')
    await userEvent.type(screen.getByLabelText('Email'), 'a@example.com')
    await userEvent.type(screen.getByLabelText('Password'), 'secret12345')
    await userEvent.click(screen.getByRole('button', { name: 'Create Account' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Password must be at least 10 characters')
  })
})
