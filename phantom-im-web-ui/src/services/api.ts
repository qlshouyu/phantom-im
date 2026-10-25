import type {
  Account, ApiEnvelope, PhantomToken, RuntimeConfig,
} from "../types";

export class ApiError extends Error {
  constructor(public readonly code: number, message: string, public readonly status: number,
              public readonly fields?: Record<string, string>) {
    super(message);
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && typeof init.body === "string") headers.set("Content-Type", "application/json");
  headers.set("Accept", "application/json");
  let response: Response;
  try {
    response = await fetch(path, { ...init, headers, credentials: "same-origin" });
  } catch {
    throw new ApiError(0, "网络不可用，请检查服务是否已启动", 0);
  }
  let payload: ApiEnvelope<T> | undefined;
  try {
    payload = await response.json() as ApiEnvelope<T>;
  } catch {
    throw new ApiError(0, response.ok ? "响应格式错误" : `HTTP ${response.status}`, response.status);
  }
  if (!response.ok || payload.code !== 0) {
    throw new ApiError(payload.code, payload.message || `HTTP ${response.status}`,
      response.status, typeof payload.data === "object" && payload.data ? payload.data as Record<string, string> : undefined);
  }
  return payload.data;
}

export async function getRuntimeConfig(): Promise<RuntimeConfig> {
  return request<RuntimeConfig>("/api/runtime-config");
}

export async function register(input: {
  nickname: string; login_name: string; password: string; password_confirm: string;
}): Promise<{ account_id: string; phantom_user_id: string; status: string }> {
  return request("/api/auth/register", { method: "POST", body: JSON.stringify(input) });
}

export async function login(input: { login_name: string; password: string }): Promise<Account> {
  return request("/api/auth/login", { method: "POST", body: JSON.stringify(input) });
}

export async function logout(): Promise<void> {
  await request<void>("/api/auth/logout", { method: "POST", body: "{}" });
}

export async function currentAccount(): Promise<Account> {
  return request<Account>("/api/auth/me");
}

export async function phantomToken(expired = false): Promise<PhantomToken> {
  return request<PhantomToken>(expired
    ? "/api/auth/phantom-token/test-expired"
    : "/api/auth/phantom-token", { method: "POST", body: "{}" });
}
