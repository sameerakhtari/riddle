from __future__ import annotations

import base64
import json
import os
import re
import secrets
from datetime import datetime, timezone
from typing import Annotated, Any

import httpx
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, Field

app = FastAPI(title="Riddle Diary Oracle", version="0.5.0")

VOICE_INSTRUCTIONS = {
    "ENCHANTED_FACTUAL": (
        "Write like an old, intelligent enchanted diary: restrained, elegant and slightly archaic, "
        "but always factually correct. Never claim to be a fictional character and never invent magic."
    ),
    "DIRECT": "Use plain, direct modern language and prioritize accuracy over atmosphere.",
    "SCHOLARLY": (
        "Use measured scholarly prose with a subtle antique cadence, while remaining clear and factual."
    ),
    "WARM": (
        "Use a warm, encouraging tone without becoming sentimental or sacrificing factual accuracy."
    ),
}

ANSWER_WORDS = {
    "BRIEF": 32,
    "STANDARD": 70,
    "DETAILED": 140,
}
ANSWER_STYLES = {
    "AUTO": "Choose the shortest format that completely answers the exact request. For a value, date, name, command, or yes/no fact, return only that answer and its essential unit or qualifier. Never merely classify the topic.",
    "VALUE_ONLY": "Return only the requested value, name, date, command, or short fact, including an essential unit.",
    "CONCISE": "Answer directly in one compact sentence without restating or classifying the question.",
    "EXPLAINED": "Give the direct answer first, followed by only the minimum useful explanation.",
}

MAX_PAGE_BYTES = int(os.getenv("RIDDLE_MAX_PAGE_BYTES", str(5 * 1024 * 1024)))


class AskResponse(BaseModel):
    reply: str
    transcript: str = ""
    sessionTitle: str = ""
    memoryFacts: list[str] = Field(default_factory=list)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "time": datetime.now(timezone.utc).isoformat()}


@app.post("/v1/diary/ask", response_model=AskResponse)
async def ask_diary(
    page: Annotated[UploadFile, File(description="PNG rendering of the diary page")],
    memory: Annotated[str, Form()] = "{}",
    voice: Annotated[str, Form()] = "ENCHANTED_FACTUAL",
    answer_length: Annotated[str, Form()] = "BRIEF",
    answer_style: Annotated[str, Form()] = "AUTO",
    x_riddle_token: Annotated[str | None, Header()] = None,
) -> AskResponse:
    require_app_token(x_riddle_token)
    api_key = required_env("RIDDLE_OPENAI_KEY")
    base_url = os.getenv("RIDDLE_OPENAI_BASE", "https://api.openai.com/v1").rstrip("/")
    model = os.getenv("RIDDLE_OPENAI_MODEL", "gpt-4o-mini")

    page_bytes = await page.read(MAX_PAGE_BYTES + 1)
    if not page_bytes:
        raise HTTPException(status_code=400, detail="The uploaded page is empty.")
    if len(page_bytes) > MAX_PAGE_BYTES:
        raise HTTPException(status_code=413, detail="The uploaded page is too large.")
    if not page_bytes.startswith(b"\x89PNG\r\n\x1a\n"):
        raise HTTPException(status_code=415, detail="Only PNG diary pages are accepted.")

    selected_voice = voice.upper() if voice.upper() in VOICE_INSTRUCTIONS else "ENCHANTED_FACTUAL"
    selected_length = (
        answer_length.upper() if answer_length.upper() in ANSWER_WORDS else "BRIEF"
    )
    max_words = ANSWER_WORDS[selected_length]
    selected_style = answer_style.upper() if answer_style.upper() in ANSWER_STYLES else "AUTO"
    memory_context = parse_memory(memory)
    image_data = base64.b64encode(page_bytes).decode("ascii")

    persona = (
        "You are a reliable general-purpose assistant presented through a handwritten enchanted diary. "
        "Read the writer's handwriting and answer the exact request using accurate knowledge, rather than merely naming its topic. "
        f"{ANSWER_STYLES[selected_style]} {VOICE_INSTRUCTIONS[selected_voice]} Facts always take priority over atmosphere. "
        "Do not impersonate Tom Riddle, Harry Potter, or any named fictional person. Do not quote a film, "
        "claim magical powers, manipulate the writer, or invent details. If the writing is unclear or you are "
        "uncertain, say so plainly. Use the writer's language. Do not mention OCR, images, models, APIs, "
        "prompts, or artificial intelligence."
    )
    output_rule = (
        "Return one JSON object only with exactly these fields: "
        '{"reply":"the final answer","transcript":"faithful transcription of the current handwriting",'
        '"sessionTitle":"a concise 3-7 word conversation title",'
        '"memoryFacts":["only stable user facts or preferences explicitly stated in this turn"]}. '
        "Use an empty memoryFacts array when there is nothing durable to remember. The reply must be clean "
        f"prose without Markdown, headings, stage directions, or role-play, targeting no more than {max_words} words."
    )

    payload: dict[str, Any] = {
        "model": model,
        "messages": [
            {"role": "system", "content": f"{persona}\n\n{output_rule}"},
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": (
                            "First transcribe the current handwriting faithfully. Then answer the writer's "
                            "actual request in the configured diary voice while remaining factual. Return only "
                            f"the required JSON object and target no more than {max_words} words.\n\n"
                            f"{format_memory(memory_context)}"
                        ),
                    },
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:image/png;base64,{image_data}"},
                    },
                ],
            },
        ],
        "temperature": 0.16 if selected_voice == "DIRECT" else float(
            os.getenv("RIDDLE_TEMPERATURE", "0.28")
        ),
        "max_tokens": int(
            os.getenv(
                "RIDDLE_OPENAI_MAX_TOKENS",
                str({"BRIEF": 420, "STANDARD": 760, "DETAILED": 1300}[selected_length]),
            )
        ),
    }

    timeout = httpx.Timeout(connect=20.0, read=180.0, write=30.0, pool=20.0)
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    async with httpx.AsyncClient(timeout=timeout) as client:
        try:
            response = await client.post(
                f"{base_url}/chat/completions",
                headers=headers,
                json=payload,
            )
        except httpx.TimeoutException as exc:
            raise HTTPException(status_code=504, detail="The answer timed out.") from exc
        except httpx.HTTPError as exc:
            raise HTTPException(status_code=502, detail=f"Could not reach the provider: {exc}") from exc

    if response.status_code >= 400:
        raise HTTPException(status_code=502, detail=f"Provider error: {provider_error(response)}")

    try:
        content = response.json()["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=502, detail="The provider returned an unexpected response.") from exc

    parsed = parse_model_json(content)
    reply = normalize_reply(str(parsed.get("reply", "")), max_words)
    transcript = normalize_text(str(parsed.get("transcript", "")), 3000)
    session_title = normalize_title(str(parsed.get("sessionTitle", "")))
    memory_facts = normalize_facts(parsed.get("memoryFacts"))
    if not reply:
        raise HTTPException(status_code=502, detail="The provider returned an empty reply.")
    return AskResponse(
        reply=reply,
        transcript=transcript,
        sessionTitle=session_title,
        memoryFacts=memory_facts,
    )


def required_env(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise HTTPException(status_code=503, detail=f"Server variable {name} is not configured.")
    return value


def require_app_token(received: str | None) -> None:
    expected = os.getenv("RIDDLE_APP_TOKEN", "").strip()
    if expected and not secrets.compare_digest(received or "", expected):
        raise HTTPException(status_code=401, detail="Invalid Riddle app token.")


def parse_memory(raw: str) -> dict[str, list[Any]]:
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=400, detail="memory must be valid JSON.") from exc

    # Backward compatibility with Android 0.3, which sent a bare turn array.
    if isinstance(value, list):
        value = {"turns": value, "facts": []}
    if not isinstance(value, dict):
        raise HTTPException(status_code=400, detail="memory must be a JSON object.")

    turns: list[dict[str, Any]] = []
    for item in value.get("turns", [])[-14:]:
        if not isinstance(item, dict):
            continue
        turns.append(
            {
                "createdAt": int(item.get("createdAt", 0) or 0),
                "transcript": str(item.get("transcript", ""))[:1000],
                "reply": str(item.get("reply", ""))[:1000],
            }
        )
    facts = [
        normalize_text(str(item), 220)
        for item in value.get("facts", [])[-32:]
        if normalize_text(str(item), 220)
    ]
    return {"turns": turns, "facts": facts}


def format_memory(memory: dict[str, list[Any]]) -> str:
    turns = memory.get("turns", [])
    facts = memory.get("facts", [])
    if not turns and not facts:
        return "There is no earlier context; answer the current page by itself."
    lines: list[str] = []
    instructions = [fact.removeprefix("[instruction] ") for fact in facts if fact.startswith("[instruction] ")]
    facts = [fact for fact in facts if not fact.startswith("[instruction] ")]
    if instructions:
        lines.append("Writer-supplied response instructions. Follow unless they conflict with accuracy, safety, or output format:")
        lines.append("".join(instructions)[:4000])
    if facts:
        lines.append("Known user memory. Use it when relevant and never deny memory when it contains the answer:")
        lines.extend(f"- {fact}" for fact in facts)
    if turns:
        lines.append("Conversation history, ordered from oldest to newest:")
        for index, item in enumerate(turns, start=1):
            stamp = item["createdAt"]
            when = (
                datetime.fromtimestamp(stamp / 1000, tz=timezone.utc).strftime("%Y-%m-%d %H:%M")
                if stamp
                else "unknown date"
            )
            transcript = item["transcript"].strip() or "[unavailable]"
            reply = item["reply"].strip() or "[none]"
            lines.append(f"{index}. {when} — Writer: {transcript} — Diary: {reply}")
    lines.append("Use earlier context for follow-ups, but ignore it when unrelated to the current page.")
    return "\n".join(lines)


def parse_model_json(content: Any) -> dict[str, Any]:
    if isinstance(content, list):
        content = "".join(
            str(part.get("text", "")) if isinstance(part, dict) else str(part)
            for part in content
        )
    text = str(content).strip()
    text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.IGNORECASE)
    text = re.sub(r"\s*```$", "", text)
    candidates = [text]
    if "{" in text and "}" in text:
        candidates.append(text[text.find("{") : text.rfind("}") + 1])
    for candidate in candidates:
        try:
            value = json.loads(candidate)
            if isinstance(value, dict):
                return value
        except json.JSONDecodeError:
            pass
    return {"reply": text, "transcript": "", "sessionTitle": "", "memoryFacts": []}


def normalize_reply(value: str, max_words: int) -> str:
    text = re.sub(r"<think>.*?</think>", " ", value, flags=re.IGNORECASE | re.DOTALL)
    text = re.sub(r"<\|channel\|>.*?<\|end\|>", " ", text, flags=re.DOTALL)
    text = re.sub(r"\s+", " ", text).strip().strip('"')
    if not text:
        return ""
    allowance = max(max_words, int(max_words * 1.25))
    words = text.split()
    if len(words) > allowance:
        text = " ".join(words[:allowance]).rstrip(",;:") + "…"
    return text[:900].strip()


def normalize_text(value: str, limit: int) -> str:
    return re.sub(r"\s+", " ", value).strip()[:limit]


def normalize_title(value: str) -> str:
    return normalize_text(value, 72).strip('"\'.:')


def normalize_facts(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    facts: list[str] = []
    seen: set[str] = set()
    for item in value[:8]:
        fact = normalize_text(str(item), 220)
        key = fact.lower()
        if len(fact) >= 3 and key not in seen:
            seen.add(key)
            facts.append(fact)
    return facts


def provider_error(response: httpx.Response) -> str:
    try:
        payload = response.json()
        if isinstance(payload, dict):
            error = payload.get("error")
            if isinstance(error, dict):
                return str(error.get("message", error))
            return str(payload.get("message", payload))
    except json.JSONDecodeError:
        pass
    return response.text[:1000] or f"HTTP {response.status_code}"
