// Cloudflare Workers 用。環境変数: ANTHROPIC_API_KEY, APP_TOKEN(任意)
// 日本語の答えが「同じ意味か」だけを判定して { correct: true/false } を返す。
export default {
  async fetch(request, env) {
    if (request.method !== "POST") return new Response("ok");
    if (env.APP_TOKEN && request.headers.get("X-App-Token") !== env.APP_TOKEN) {
      return new Response("forbidden", { status: 403 });
    }

    const { question, expected, answer } = await request.json();

    const res = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": env.ANTHROPIC_API_KEY,
        "anthropic-version": "2023-06-01",
      },
      body: JSON.stringify({
        model: "claude-haiku-5-5",
        max_tokens: 10,
        system:
          "You grade a Japanese-English vocabulary quiz. 'question' is the word shown to the student, " +
          "'expected' is the reference answer (several accepted meanings may be separated by '/'), " +
          "'answer' is the student's answer. Reply YES if the answer has the same meaning as one of the " +
          "expected answers (synonyms, kana/kanji variants and okurigana differences are fine). " +
          "Otherwise reply NO. Output exactly one word: YES or NO.",
        messages: [{ role: "user", content: JSON.stringify({ question, expected, answer }) }],
      }),
    });

    if (!res.ok) return new Response("upstream error", { status: 502 });
    const data = await res.json();
    const text = (data.content?.[0]?.text ?? "").trim();
    return Response.json({ correct: /^YES/i.test(text) });
  },
};
