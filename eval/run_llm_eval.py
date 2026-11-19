"""端末内 AI（LiteRT-LM）で評価セットを流し、見逃し・誤検出・題名を数える（issue #71）。

アプリと同じ問い（AiTaskPrompt.kt の SYSTEM と userMessage）を使う。
PC で動かす：
    python -m venv llmenv
    llmenv/Scripts/pip install litert-lm-api==0.19.2
    llmenv/Scripts/python eval/run_llm_eval.py <model.litertlm>
"""

import json
import re
import sys
import time
from pathlib import Path

import litert_lm

ROOT = Path(__file__).resolve().parent.parent


def load_system_prompt() -> str:
    src = (ROOT / "app/src/main/java/jp/mimimemo/AiTaskPrompt.kt").read_text(encoding="utf-8")
    body = re.search(r'val SYSTEM = """(.*?)"""\.trimIndent\(\)', src, re.S).group(1)
    lines = body.split("\n")
    indent = min(len(l) - len(l.lstrip()) for l in lines if l.strip())
    return "\n".join(l[indent:] for l in lines).strip()


LINE = re.compile(r"^\s*(\d+)\s*[|｜]\s*([^|｜]*?)\s*[|｜]\s*([^|｜]*?)\s*(?:[|｜]\s*([^|｜]*?)\s*)?$")


def parse(reply: str, count: int):
    seen, tasks = set(), []
    for raw in reply.splitlines():
        m = LINE.match(raw.strip().lstrip("-・* "))
        if not m:
            continue
        i = int(m.group(1))
        title = m.group(2).strip().rstrip("。.")
        if not (1 <= i <= count) or not title or title == "なし" or i in seen:
            continue
        seen.add(i)
        tasks.append((i, title, m.group(3).strip()))
    return tasks


def main():
    model = sys.argv[1]
    system = load_system_prompt()
    cases = json.loads((ROOT / "eval/conversations.json").read_text(encoding="utf-8"))["cases"]
    engine = litert_lm.Engine(model, cache_dir=":nocache")
    tp = fp = fn = title_ok = title_all = 0
    rules_path = ROOT / "app/build/eval-rules.json"
    rules = json.loads(rules_path.read_text(encoding="utf-8")) if rules_path.exists() else None
    mix = {"union": [0, 0, 0], "inter": [0, 0, 0]}
    started = time.time()
    for case in cases:
        sentences = case["sentences"]
        user = "\n".join(f"{i + 1}: {s}" for i, s in enumerate(sentences)) + "\n答え"
        conv = engine.create_conversation(
            system_message=system,
            sampler_config=litert_lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0, seed=0),
            thinking_config=litert_lm.ThinkingConfig(enable_thinking=False, thinking_token_budget=0),
            max_output_tokens=512,
        )
        reply = conv.send_message(user)
        text = "".join(c.text for c in reply.contents if hasattr(c, "text"))
        text = re.sub(r"(?s)<think>.*?</think>", "", text).strip()
        got = parse(text, len(sentences))
        expected = set(case["tasks"])
        found = {i for i, _, _ in got}
        tp += len(found & expected)
        fp += len(found - expected)
        fn += len(expected - found)
        for i, title, _ in got:
            want = case.get("title_has", {}).get(str(i))
            if want:
                title_all += 1
                title_ok += want in title
        if rules is not None:
            r = set(rules.get(case["name"], []))
            for key, got_set in (("union", found | r), ("inter", found & r)):
                mix[key][0] += len(got_set & expected)
                mix[key][1] += len(got_set - expected)
                mix[key][2] += len(expected - got_set)
        mark = "OK " if found == expected else "NG "
        print(f"{mark}{case['name']}: 期待={sorted(expected)} 結果={[(i, t, d) for i, t, d in got]}")
    precision = tp / (tp + fp) if tp + fp else 1.0
    recall = tp / (tp + fn) if tp + fn else 1.0
    print(f"\n適合率 {precision:.2f}（誤検出 {fp}） 再現率 {recall:.2f}（見逃し {fn}） 題名 {title_ok}/{title_all}"
          f"  {len(cases)}例 {time.time() - started:.0f}秒")
    if rules is not None:
        for key, (t, f, n) in mix.items():
            label = "ルール∪AI" if key == "union" else "ルール∩AI"
            print(f"{label}: 適合率 {t / (t + f) if t + f else 1:.2f}（誤検出 {f}） 再現率 {t / (t + n) if t + n else 1:.2f}（見逃し {n}）")


if __name__ == "__main__":
    main()
