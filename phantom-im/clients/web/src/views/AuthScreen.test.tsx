import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { BffApiError } from '../api/bff'
import AuthScreen from './AuthScreen'

describe('AuthScreen', () => {
  it('注册成功后不自动登录，并回填登录名', async () => {
    const user = userEvent.setup()
    const onLogin = vi.fn()
    const onRegister = vi.fn(async () => {})
    render(<AuthScreen onLogin={onLogin} onRegister={onRegister} />)

    await user.click(screen.getByRole('button', { name: '注册' }))
    await user.type(screen.getByLabelText('昵称'), 'Alice')
    await user.type(screen.getByLabelText('登录名'), 'alice_1')
    await user.type(screen.getByLabelText('密码'), 'password1')
    await user.type(screen.getByLabelText('确认密码'), 'password1')
    await user.click(screen.getByTestId('auth-register-submit'))

    expect(onRegister).toHaveBeenCalledWith({
      nickname: 'Alice',
      loginName: 'alice_1',
      password: 'password1',
      passwordConfirm: 'password1',
    })
    expect(onLogin).not.toHaveBeenCalled()
    expect(await screen.findByText('注册成功，请使用新账号登录')).toBeInTheDocument()
    expect(screen.getByLabelText('登录名')).toHaveValue('alice_1')
  })

  it('登录失败保持未登录并展示服务端字段错误', async () => {
    const user = userEvent.setup()
    const onLogin = vi.fn(async () => {
      throw new BffApiError({
        message: '登录字段不合法',
        code: 1001,
        status: 400,
        fields: { loginName: '登录名或密码错误' },
        requestId: 'req-login',
      })
    })
    render(<AuthScreen onLogin={onLogin} onRegister={vi.fn()} />)

    await user.type(screen.getByLabelText('登录名'), 'alice_1')
    await user.type(screen.getByLabelText('密码'), 'wrong-password')
    await user.click(screen.getByTestId('auth-login-submit'))

    expect(await screen.findByText('登录名或密码错误')).toBeInTheDocument()
    expect(screen.getByTestId('auth-login-submit')).toBeEnabled()
  })
})
