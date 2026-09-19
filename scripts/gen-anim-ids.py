# -*- coding: utf-8 -*-
"""
扫描 training-ui/public/algo-anim/demos-*.js, 抽取每个 demo 的 id/title/render,
生成 anim-ids.json 供训练台前端判断「这道题有没有动画」。

新增 demo 后重新执行本脚本即可:
    python scripts/gen-anim-ids.py
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANIM_DIR = ROOT / "training-ui" / "public" / "algo-anim"
OUT = ANIM_DIR / "anim-ids.json"

ITEM_RE = re.compile(
    r"id:\s*'(?P<id>[A-Za-z0-9]+)'\s*,\s*"
    r"title:\s*'(?P<title>[^']*)'\s*,\s*"
    r"group:\s*'(?P<group>[^']*)'"
)


def main() -> int:
    items = []
    for path in sorted(ANIM_DIR.glob("demos-*.js")):
        text = path.read_text(encoding="utf-8")
        for m in ITEM_RE.finditer(text):
            items.append({
                "id": m.group("id"),
                "title": m.group("title"),
                "group": m.group("group"),
            })
    # 同 id 去重, 保持首次出现顺序
    seen, uniq = set(), []
    for it in items:
        if it["id"] in seen:
            continue
        seen.add(it["id"])
        uniq.append(it)

    OUT.write_text(
        json.dumps({"count": len(uniq), "items": uniq}, ensure_ascii=False, indent=1) + "\n",
        encoding="utf-8",
    )
    print("wrote %s  (%d demos)" % (OUT, len(uniq)))
    for it in uniq:
        print("  %-6s %s" % (it["id"], it["title"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
