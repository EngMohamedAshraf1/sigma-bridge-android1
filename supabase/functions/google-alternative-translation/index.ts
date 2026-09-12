import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const LANGUAGE_NAMES: Record<string, string> = {
  ar: "Arabic",
  ru: "Russian",
  en: "English",
  fr: "French",
  de: "German",
  es: "Spanish",
  it: "Italian",
  pt: "Portuguese",
  tr: "Turkish",
  zh: "Chinese (Simplified)",
  ja: "Japanese",
  ko: "Korean",
  hi: "Hindi",
  uk: "Ukrainian",
  pl: "Polish",
};

const GEMINI_MODEL = "gemini-3.1-flash-lite";
const MAX_TEXT_LENGTH = 4000;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

function getGeminiApiKeys(): string[] {
  const numbered = Array.from({ length: 10 }, (_, index) =>
    Deno.env.get(`GEMINI_ALTERNATIVE_TRANSLATION_API_KEY_${index + 1}`)?.trim() ?? "",
  ).filter(Boolean);

  // Keep the original single-key secret as a backwards-compatible fallback.
  const legacy = Deno.env.get("GEMINI_ALTERNATIVE_TRANSLATION_API_KEY")?.trim() ?? "";
  return [...numbered, ...(legacy ? [legacy] : [])];
}

function shouldRotateKey(status: number): boolean {
  return status === 401 || status === 403 || status === 429 || (status >= 500 && status <= 599);
}

async function requestTranslation(apiKey: string, prompt: string): Promise<string> {
  const response = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": apiKey,
      },
      body: JSON.stringify({
        contents: [
          {
            role: "user",
            parts: [{ text: prompt }],
          },
        ],
        generationConfig: {
          temperature: 0.2,
          maxOutputTokens: 1024,
        },
      }),
    },
  );

  if (!response.ok) {
    const error = new Error("TRANSLATION_PROVIDER_ERROR");
    (error as Error & { status?: number }).status = response.status;
    throw error;
  }

  const payload = await response.json();
  const translatedText = payload?.candidates?.[0]?.content?.parts
    ?.map((part: { text?: unknown }) => typeof part?.text === "string" ? part.text : "")
    .join("")
    .trim();

  if (!translatedText) {
    throw new Error("EMPTY_TRANSLATION");
  }

  return translatedText;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return json({ error: "METHOD_NOT_ALLOWED" }, 405);
  }

  try {
    const apiKeys = getGeminiApiKeys();
    if (apiKeys.length === 0) {
      return json({ error: "TRANSLATION_SERVICE_NOT_CONFIGURED" }, 503);
    }

    const body = await req.json();
    const text = typeof body?.text === "string" ? body.text.trim() : "";
    const targetLanguage = typeof body?.targetLanguage === "string"
      ? body.targetLanguage.trim()
      : "";

    if (!text) return json({ error: "TEXT_REQUIRED" }, 400);
    if (text.length > MAX_TEXT_LENGTH) return json({ error: "TEXT_TOO_LONG" }, 413);

    const languageName = LANGUAGE_NAMES[targetLanguage];
    if (!languageName) return json({ error: "UNSUPPORTED_TARGET_LANGUAGE" }, 400);

    const prompt = [
      `Translate the following message faithfully into ${languageName}.`,
      "Preserve the meaning, tone, punctuation, line breaks, names, and emojis.",
      "Return only the translation. Do not add explanations or quotation marks.",
      "",
      text,
    ].join("\n");

    let lastError: unknown = null;
    for (const apiKey of apiKeys) {
      try {
        const translatedText = await requestTranslation(apiKey, prompt);
        return json({ translatedText, targetLanguage });
      } catch (error) {
        lastError = error;
        const status = (error as { status?: number })?.status;
        if (!status || !shouldRotateKey(status)) {
          break;
        }
      }
    }

    void lastError;
    return json({ error: "TRANSLATION_PROVIDER_ERROR" }, 502);
  } catch (_error) {
    return json({ error: "INVALID_REQUEST" }, 400);
  }
});
