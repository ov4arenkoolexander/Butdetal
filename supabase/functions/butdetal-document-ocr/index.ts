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

function extractProductColumn(result: any): { text: string; found: boolean; reason: string; bounds: null | { left: number; top: number; right: number; bottom: number; normalized: boolean } } {
  const annotations = Array.isArray(result?.textAnnotations) ? result.textAnnotations.slice(1) : [];
  const words = annotations.map((item: any) => {
    const raw = item?.boundingPoly?.vertices?.length ? item.boundingPoly.vertices : item?.boundingPoly?.normalizedVertices;
    if (!item?.description || !Array.isArray(raw) || !raw.length) return null;
    const xs = raw.map((v: any) => Number(v.x ?? 0));
    const ys = raw.map((v: any) => Number(v.y ?? 0));
    const minX = Math.min(...xs), maxX = Math.max(...xs);
    const minY = Math.min(...ys), maxY = Math.max(...ys);
    return { text: String(item.description), minX, maxX, minY, maxY, cx: (minX + maxX) / 2, cy: (minY + maxY) / 2, height: Math.max(1, maxY - minY) };
  }).filter(Boolean) as Array<{text:string;minX:number;maxX:number;minY:number;maxY:number;cx:number;cy:number;height:number}>;
  const normalize = (value: string) => value.toLocaleLowerCase("uk")
    .replace(/[aopckxym]/g, (ch) => ({a:"а",o:"о",p:"р",c:"с",k:"к",x:"х",y:"у",m:"м"}[ch] || ch))
    .replace(/[^\p{L}]/gu, "");
  const editDistanceAtMostOne = (a: string, b: string) => {
    if (Math.abs(a.length - b.length) > 1) return false;
    let i = 0, j = 0, edits = 0;
    while (i < a.length && j < b.length) {
      if (a[i] === b[j]) { i++; j++; continue; }
      if (++edits > 1) return false;
      if (a.length > b.length) i++;
      else if (b.length > a.length) j++;
      else { i++; j++; }
    }
    if (i < a.length || j < b.length) edits++;
    return edits <= 1;
  };
  const headers = words.filter((word) => {
    const value = normalize(word.text);
    return value === "товар" || (value.length >= 4 && value.length <= 6 && editDistanceAtMostOne(value, "товар"));
  });
  if (!headers.length) return { text: "", found: false, reason: "Не знайдено заголовок «Товар» (навіть з урахуванням типової помилки OCR).", bounds: null };

  const likelyMarkPart = (value: string) => {
    const v = value.replace(/[‐‑‒–—]/g, "-").replace(/[()[\],;:]/g, "");
    return /^(?:\d{1,2}[А-ЯІЇЄҐA-Z]{1,8}|[А-ЯІЇЄҐA-Z]{1,8}|\d{1,4}(?:[-.]\d{1,4}){1,3})(?:[А-ЯІЇЄҐA-Z]{0,4}\d{0,2})?$/iu.test(v);
  };

  for (const header of headers) {
    const rowTolerance = Math.max(10, header.height * 0.85);
    const sameHeaderRow = words.filter((word) => word !== header && Math.abs(word.cy - header.cy) <= rowTolerance);
    const leftHeaders = sameHeaderRow.filter((word) => word.cx < header.cx).sort((a,b) => b.cx-a.cx);
    const rightHeaders = sameHeaderRow.filter((word) => word.cx > header.cx).sort((a,b) => a.cx-b.cx);
    const leftNeighbor = leftHeaders[0] || null;
    const rightNeighbor = rightHeaders[0] || null;

    let left = leftNeighbor ? (leftNeighbor.maxX + header.minX) / 2 : NaN;
    let right = rightNeighbor ? (header.maxX + rightNeighbor.minX) / 2 : NaN;
    let method = "межі за сусідніми заголовками";

    // Якщо OCR пропустив один із сусідніх заголовків, звужуємо пошук
    // тільки до фрагментів рядків, схожих на марку виробу. Не беремо всю сторінку.
    if (!leftNeighbor || !rightNeighbor) {
      const searchRadius = Math.max(header.height * 16, (header.maxX - header.minX) * 7);
      const below = words.filter((word) =>
        word.cy > header.maxY &&
        word.cy < header.maxY + Math.max(header.height * 45, 900) &&
        Math.abs(word.cx - header.cx) <= searchRadius &&
        likelyMarkPart(word.text)
      );
      if (!below.length) continue;
      const pad = Math.max(3, header.height * 0.65);
      if (!leftNeighbor) left = Math.min(...below.map((word) => word.minX)) - pad;
      if (!rightNeighbor) right = Math.max(...below.map((word) => word.maxX)) + pad;
      method = "межі за заголовком і вирівняними фрагментами марок";
    }

    const columnWidth = right - left;
    if (!Number.isFinite(columnWidth) || columnWidth < Math.max(header.height * 1.8, (header.maxX-header.minX) * 1.25)) continue;

    const cells = words.filter((word) =>
      word.cy > header.maxY &&
      word.cx > left &&
      word.cx < right
    ).sort((a,b) => a.cy-b.cy || a.minX-b.minX);
    if (!cells.length) continue;

    const heights = cells.map((word) => word.height).sort((a,b) => a-b);
    const medianHeight = heights[Math.floor(heights.length / 2)] || header.height || 12;
    const textRowTolerance = Math.max(7, medianHeight * 0.72);
    const rows: Array<{ y:number; words: typeof cells }> = [];
    for (const word of cells) {
      let row = rows.find((candidate) => Math.abs(candidate.y - word.cy) <= textRowTolerance);
      if (!row) { row = { y: word.cy, words: [] }; rows.push(row); }
      row.words.push(word);
      row.y = row.words.reduce((sum, item) => sum + item.cy, 0) / row.words.length;
    }
    const orderedRows = rows.sort((a,b) => a.y-b.y);
    const text = orderedRows
      .map((row) => row.words.sort((a,b) => a.minX-b.minX).map((word) => word.text).join(" ").trim())
      .filter(Boolean).join("\n");
    if (!text) continue;

    const maxBottom = Math.max(...cells.map((word) => word.maxY));
    const normalized = Math.max(...words.map((word) => Math.max(word.maxX, word.maxY))) <= 1.01;
    return {
      text,
      found: true,
      reason: method === "межі за сусідніми заголовками"
        ? "Межі колонки підтверджені сусідніми заголовками."
        : "Колонку попередньо виділено за заголовком і розташуванням фрагментів марок. Перевір результат перед імпортом.",
      bounds: { left, top: header.minY, right, bottom: maxBottom, normalized },
    };
  }
  return {
    text: "",
    found: false,
    reason: "Заголовок «Товар» знайдено, але межі колонки не підтверджені. Щоб не захопити кількість або ціну, імпорт зупинено.",
    bounds: null,
  };
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
    const productColumn = extractProductColumn(result);
    return json({
      provider: "google-cloud-vision",
      text,
      productColumnText: productColumn.text,
      productColumnFound: productColumn.found,
      productColumnReason: productColumn.reason,
      productColumnBounds: productColumn.bounds,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Помилка авторизації Google Cloud Vision.";
    return json({ error: message.slice(0, 500), code: "GOOGLE_AUTH_ERROR" }, 503);
  }
});
