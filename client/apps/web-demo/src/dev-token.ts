// 仅供本地 Demo：在浏览器用共享 AppSecret 签发 UserToken(HS256)。
// 真实接入方由服务端签发 UserToken，SDK 不做签发。
function b64url(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
const enc = (obj: unknown) => b64url(new TextEncoder().encode(JSON.stringify(obj)));

export async function devToken(appId: string, userId: string, secret: string, expired = false): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "HS256", typ: "JWT" };
  const payload = { app_id: appId, user_id: userId, iat: now, exp: now + (expired ? -60 : 3600) };
  const data = `${enc(header)}.${enc(payload)}`;
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(data));
  return `${data}.${b64url(new Uint8Array(sig))}`;
}
