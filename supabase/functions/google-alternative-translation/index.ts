import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const MAX_TEXT_LENGTH = 4000;
const GOOGLE_TRANSLATE_ENDPOINT = "https://translation.googleapis.com/language/translate/v2";
const GOOGLE_TRANSLATION_MODEL = "general/translation-llm";

interface RequestBody {
  text?: unknown;
  targetLanguage?: unknown;
}

interface GoogleTranslationResponse {
  data?: {
    translations?: Array<{
      translatedText?: string;
    }>;
  };
  error?: {
    message?: string;
  };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...corsHeaders,
      "Content-Type": "application/json",
    },
  });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return json({ error: "METHOD_NOT_ALLOWED" }, 405);
  }

  const apiKey = Deno.env.get("GOOGLE_TRANSLATION_API_KEY");
  const projectId = Deno.env.get("GOOGLE_CLOUD_PROJECT_ID");

  if (!apiKey || !projectId) {
    return json({ error: "TRANSLATION_SERVICE_NOT_CONFIGURED" }, 503);
  }

  let body: RequestBody;
  try {
    body = await req.json();
  } catch {
    return json({ error: "INVALID_JSON" }, 400);
  }

  const text = typeof body.text === "string" ? body.text.trim() : "";
  const targetLanguage = typeof body.targetLanguage === "string"
    ? body.targetLanguage.trim().toLowerCase()
    : "";

  if (!text) {
    return json({ error: "TEXT_REQUIRED" }, 400);
  }

  if (text.length > MAX_TEXT_LENGTH) {
    return json({ error: "TEXT_TOO_LONG", maxLength: MAX_TEXT_LENGTH }, 400);
  }

  if (!/^[a-z]{2}(?:-[A-Z]{2})?$/.test(targetLanguage)) {
    return json({ error: "INVALID_TARGET_LANGUAGE" }, 400);
  }

  const googleUrl = new URL(GOOGLE_TRANSLATE_ENDPOINT);
  googleUrl.searchParams.set("key", apiKey);

  try {
    const googleResponse = await fetch(googleUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        q: [text],
        target: targetLanguage,
        model: `projects/${projectId}/locations/us-central1/models/${GOOGLE_TRANSLATION_MODEL}`,
      }),
    });

    const payload = await googleResponse.json() as GoogleTranslationResponse;

    if (!googleResponse.ok) {
      console.error("Google Translation API error", googleResponse.status, payload.error?.message ?? "unknown");
      return json({ error: "TRANSLATION_PROVIDER_ERROR" }, 502);
    }

    const translatedText = payload.data?.translations?.[0]?.translatedText?.trim();
    if (!translatedText) {
      return json({ error: "EMPTY_TRANSLATION" }, 502);
    }

    return json({
      translatedText,
      targetLanguage,
    });
  } catch (error) {
    console.error("Translation request failed", error);
    return json({ error: "TRANSLATION_REQUEST_FAILED" }, 502);
  }
});
