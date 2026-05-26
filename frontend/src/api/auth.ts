import api from '@/lib/axios'
import { useMutation, useQuery } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import type { EmailLoginPayload, LoginPayload, PasswordPayload, ProfilePayload, RegisterPayload, ResetPasswordPayload, Session, UsernameCheckResult } from '@/types'

export async function login(payload: LoginPayload): Promise<Session> {
  const { data } = await api.post('/auth/login', payload)
  return data
}

export async function sendEmailCode(email: string, provider = 'qq'): Promise<void> {
  await api.post('/auth/email-code', { email, provider })
}

export async function emailLogin(payload: EmailLoginPayload): Promise<Session> {
  const { data } = await api.post('/auth/email-login', payload)
  return data
}

export async function register(payload: RegisterPayload): Promise<Session> {
  const { data } = await api.post('/auth/register', payload)
  return data
}

export async function resetPassword(payload: ResetPasswordPayload): Promise<void> {
  await api.post('/auth/reset-password', payload)
}

export async function checkUsername(username: string): Promise<UsernameCheckResult> {
  const { data } = await api.get('/auth/check-username', { params: { username } })
  return data
}

export async function getMe(): Promise<Session> {
  const { data } = await api.get('/auth/me')
  return data
}

export async function updateProfile(payload: ProfilePayload): Promise<Session> {
  const { data } = await api.put('/auth/me', payload)
  return data
}

export async function updatePassword(payload: PasswordPayload): Promise<void> {
  await api.put('/auth/password', payload)
}

export async function logout(): Promise<void> {
  await api.post('/auth/logout')
}

export function useLogin() {
  return useMutation({
    mutationFn: login,
  })
}

export function useSendEmailCode() {
  return useMutation({
    mutationFn: ({ email, provider }: { email: string; provider?: string }) => sendEmailCode(email, provider),
  })
}

export function useRegister() {
  return useMutation({
    mutationFn: register,
  })
}

export function useResetPassword() {
  return useMutation({
    mutationFn: resetPassword,
  })
}

export function useCheckUsername(username: string, enabled: boolean) {
  return useQuery({
    queryKey: ['check-username', username],
    queryFn: () => checkUsername(username),
    enabled,
    staleTime: 10_000,
  })
}

export function useMe(enabled: boolean) {
  return useQuery({
    queryKey: ['me'],
    queryFn: getMe,
    enabled,
    retry: false,
  })
}

export function useLogout() {
  return useMutation({
    mutationFn: logout,
    onSuccess: () => {
      localStorage.removeItem('learning-assistant.frontend.session')
      queryClient.clear()
    },
  })
}
