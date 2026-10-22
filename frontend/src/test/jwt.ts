/** Builds an unsigned JWT-shaped string with the given expiry (seconds from now). For tests only. */
export function fakeJwt(expiresInSeconds: number): string {
  const encode = (value: object) =>
    btoa(JSON.stringify(value)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')
  const exp = Math.floor(Date.now() / 1000) + expiresInSeconds
  return `${encode({ alg: 'HS256' })}.${encode({ sub: 'a@example.com', exp })}.signature`
}
