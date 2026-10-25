function hex(bytes: ArrayBuffer): string {
  return [...new Uint8Array(bytes)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

/** Demo 启动时通过 OpenAPI 幂等导入用户并取得真实 Phantom ID。 */
export async function ensureDevUser(apiUrl: string, appId: string, appSecret: string,
                                    externalUserId: string): Promise<string> {
  const path = "/openapi/users";
  const body = JSON.stringify({ external_user_id: externalUserId });
  const timestamp = Date.now();
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(appSecret),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const signature = hex(await crypto.subtle.sign("HMAC", key,
    new TextEncoder().encode("POST" + path + body + timestamp)));
  const response = await fetch(apiUrl + path, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-App-Id": appId,
      "X-Timestamp": String(timestamp), "X-Signature": signature },
    body,
  });
  const payload = await response.json();
  if (!response.ok || payload.code !== 0) throw new Error(payload.message ?? "用户导入失败");
  return payload.data.user_id ?? payload.data.userId;
}
