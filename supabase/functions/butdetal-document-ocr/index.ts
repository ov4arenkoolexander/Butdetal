import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const recentCalls = new Map<string, number[]>();
let cachedAccessToken = "";
let cachedTokenExpiry = 0;

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json; charset=utf-8" },
  });
}

function getPublishableKey(): string {
  const raw = Deno.env.get("SUPABASE_PUBLISHABLE_KEYS");
  if (raw) {
    try {
      const parsed = JSON.parse(raw);
      if (parsed.default) return String(parsed.default);
    } catch { /* legacy fallback */ }
  }
  return Deno.env.get("SUPABASE_ANON_KEY") || "";
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function base64UrlText(value: string): string {
  return base64Url(new TextEncoder().encode(value));
}

async function getGoogleAccessToken(): Promise<string> {
  if (cachedAccessToken && Date.now() < cachedTokenExpiry - 60_000) return cachedAccessToken;

  const raw = Deno.env.get("GOOGLE_SERVICE_ACCOUNT_JSON");
  if (!raw) throw new Error("Не знайдено секрет GOOGLE_SERVICE_ACCOUNT_JSON у Supabase.");
  let credentials: any;
  try { credentials = JSON.parse(raw); } catch { throw new Error("Секрет GOOGLE_SERVICE_ACCOUNT_JSON має некоректний JSON."); }

  const email = String(credentials.client_email || "");
  const privateKeyPem = String(credentials.private_key || "");
  const tokenUri = String(credentials.token_uri || "https://oauth2.googleapis.com/token");
  if (!email || !privateKeyPem.includes("PRIVATE KEY")) {
    throw new Error("У JSON-файлі не знайдено client_email або private_key облікового запису служби.");
  }

  const pemBody = privateKeyPem
    .replace(/-----BEGIN PRIVATE KEY-----/g, "")
    .replace(/-----END PRIVATE KEY-----/g, "")
    .replace(/\s/g, "");
  const keyBytes = Uint8Array.from(atob(pemBody), (c) => c.charCodeAt(0));
  const privateKey = await crypto.subtle.importKey(
    "pkcs8",
    keyBytes,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );

  const now = Math.floor(Date.now() / 1000);
  const header = base64UrlText(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64UrlText(JSON.stringify({
    iss: email,
    scope: "https://www.googleapis.com/auth/cloud-platform",
    aud: tokenUri,
    iat: now,
    exp: now + 3600,
  }));
  const unsigned = header + "." + claims;
  const signature = new Uint8Array(await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    privateKey,
    new TextEncoder().encode(unsigned),
  ));
  const assertion = unsigned + "." + base64Url(signature);

  const tokenResponse = await fetch(tokenUri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  const tokenData = await tokenResponse.json().catch(() => ({}));
  if (!tokenResponse.ok || !tokenData.access_token) {
    const detail = String(tokenData.error_description || tokenData.error || "невідома помилка").slice(0, 240);
    throw new Error("Google не видав токен доступу: " + detail);
  }
  cachedAccessToken = String(tokenData.access_token);
  cachedTokenExpiry = Date.now() + Number(tokenData.expires_in || 3600) * 1000;
  return cachedAccessToken;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  const authorization = req.headers.get("Authorization") || "";
  if (!/^Bearer\s+\S+/i.test(authorization)) return json({ error: "Потрібно увійти в BUTDETAL повторно." }, 401);

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const publishableKey = getPublishableKey();
  if (!supabaseUrl || !publishableKey) return json({ error: "Не налаштовано перевірку сесії Supabase." }, 503);

  const authResponse = await fetch(supabaseUrl + "/auth/v1/user", {
    headers: { "apikey": publishableKey, "Authorization": authorization },
  });
  if (!authResponse.ok) return json({ error: "Сесія недійсна або завершилася. Увійди повторно." }, 401);
  const user = await authResponse.json();
  const userId = String(user?.id || "");
  if (!userId) return json({ error: "Не вдалося підтвердити обліковий запис." }, 401);

  const nowMs = Date.now();
  const calls = (recentCalls.get(userId) || []).filter((t) => nowMs - t < 60_000);
  if (calls.length >= 6) return json({ error: "Забагато запитів. Спробуй ще раз через хвилину." }, 429);
  calls.push(nowMs);
  recentCalls.set(userId, calls);

  let body: { imageDataUrl?: string };
  try { body = await req.json(); } catch { return json({ error: "Некоректний запит." }, 400); }
  const dataUrl = String(body?.imageDataUrl || "");
  const match = dataUrl.match(/^data:image\/(jpeg|jpg|png|webp);base64,([A-Za-z0-9+/=]+)$/i);
  if (!match) return json({ error: "Потрібне фото у форматі JPG, PNG або WebP." }, 400);
  const base64 = match[2];
  if (base64.length > 14_000_000) return json({ error: "Фото завелике для хмарного розпізнавання. Максимум 10 МБ після обробки." }, 413);

  try {
    const accessToken = await getGoogleAccessToken();
    const visionResponse = await fetch("https://vision.googleapis.com/v1/images:annotate", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Bearer " + accessToken,
      },
      body: JSON.stringify({
        requests: [{
          image: { content: base64 },
          features: [{ type: "DOCUMENT_TEXT_DETECTION" }],
          imageContext: { languageHints: ["uk", "ru", "en"] },
        }],
      }),
    });

    const visionData: any = await visionResponse.json().catch(() => ({}));
    if (!visionResponse.ok || visionData?.error) {
      const message = String(visionData?.error?.message || "Помилка сервісу Google Vision.");
      const code = visionData?.error?.status || "VISION_UPSTREAM_ERROR";
      return json({
        error: message.slice(0, 500),
        code,
        hint: /permission|denied|403/i.test(message)
          ? "Перевір, чи Cloud Vision API увімкнено в Google Cloud і чи має сервісний обліковий запис доступ до використання API."
          : "",
      }, 502);
    }

    const result = visionData?.responses?.[0] || {};
    if (result.error) return json({ error: String(result.error.message || "Не вдалося розпізнати документ."), code: "VISION_IMAGE_ERROR" }, 422);
    const text = String(result.fullTextAnnotation?.text || result.textAnnotations?.[0]?.description || "").trim();
    if (!text) return json({ error: "Google Vision не знайшов тексту на фото.", code: "NO_TEXT" }, 422);
    return json({ provider: "google-cloud-vision", text });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Помилка авторизації Google Cloud Vision.";
    return json({ error: message.slice(0, 500), code: "GOOGLE_AUTH_ERROR" }, 503);
  }
});
