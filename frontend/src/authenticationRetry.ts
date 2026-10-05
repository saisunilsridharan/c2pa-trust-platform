export function authenticationRetry(response: Response): string | null {
  if (response.status !== 429) return null;
  const seconds = Number(response.headers.get("Retry-After"));
  return Number.isInteger(seconds) && seconds > 0 && seconds <= 600
    ? `Too many sign-in or recovery attempts. Try again in ${seconds} seconds.`
    : "Too many sign-in or recovery attempts. Try again shortly.";
}
