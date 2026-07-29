"""
Phase 1: Extract structured data from OD-Code HTML/docx files.
Outputs: scripts/od-raw.json
"""
import json
import re
import sys
from pathlib import Path
from bs4 import BeautifulSoup

OD_ROOT = Path(r"D:\AI\OD-Code")
OUTPUT = Path(__file__).parent / "od-raw.json"

CATEGORY_MAP = {
    "01-字符串处理": "od-string",
    "02-模拟与贪心": "od-greedy",
    "03-排序": "od-sort",
    "04-栈与队列": "od-stack",
    "05-数组双指针滑窗": "od-array",
    "06-BFS_DFS_图论": "od-graph",
    "07-动态规划": "od-dp",
    "08-哈希与Map": "od-hash",
    "09-二分与区间": "od-binary",
    "10-数学与逻辑": "od-math",
    "11-综合与其他": "od-misc",
}

CATEGORY_TOPIC = {
    "od-string": "字符串处理",
    "od-greedy": "模拟与贪心",
    "od-sort": "排序",
    "od-stack": "栈与队列",
    "od-array": "数组/双指针/滑窗",
    "od-graph": "BFS/DFS/图论",
    "od-dp": "动态规划",
    "od-hash": "哈希与Map",
    "od-binary": "二分与区间",
    "od-math": "数学与逻辑",
    "od-misc": "综合",
}


def parse_score_and_title(filename: str):
    """Extract score (100/200) and title from filename."""
    score_match = re.search(r"(\d{3})分", filename)
    score = int(score_match.group(1)) if score_match else 100

    # Remove score prefix and language suffix
    title = re.sub(r"^\(?\d{3}分[)）]?\s*[-–]?\s*", "", filename)
    title = re.sub(r"[（(].*?[)）]", "", title)  # remove (Java & Python...)
    title = re.sub(r"\.(html|docx)$", "", title)
    title = title.strip(" -–—")
    return score, title


def extract_html(filepath: Path):
    """Parse a CSDN HTML file and extract structured content."""
    soup = BeautifulSoup(filepath.read_text(encoding="utf-8"), "html.parser")

    # Get all text content from the main article
    content_div = soup.find("div", id="content_views") or soup.find("div", class_="markdown_views")
    if not content_div:
        content_div = soup.find("main") or soup

    # Extract sections by headings
    sections = {}
    current_heading = "intro"
    sections[current_heading] = []

    for elem in content_div.children:
        if elem.name in ("h1", "h2", "h3", "h4", "h5"):
            current_heading = elem.get_text(strip=True)
            sections[current_heading] = []
        else:
            sections.setdefault(current_heading, []).append(elem)

    # Build description from 题目描述 + 输入描述 + 输出描述
    desc_parts = []
    for key in sections:
        if "题目描述" in key or "描述" in key and "输入" not in key and "输出" not in key:
            desc_parts.extend(sections[key])
        elif "输入描述" in key or "输入" in key:
            desc_parts.extend(sections[key])
        elif "输出描述" in key or "输出" in key:
            desc_parts.extend(sections[key])

    description = ""
    for part in desc_parts:
        text = part.get_text(strip=True) if hasattr(part, "get_text") else str(part).strip()
        if text:
            description += text + "\n"

    # If description is empty, use all text before first code block
    if not description.strip():
        all_text = content_div.get_text(separator="\n", strip=True)
        description = all_text[:2000]

    # Extract examples (input/output pairs)
    examples = extract_examples(sections, content_div)

    # Extract Java code
    java_code = extract_java_code(content_div)

    # Extract 解题思路
    analysis = ""
    for key in sections:
        if "解题思路" in key or "题目解析" in key:
            for part in sections[key]:
                text = part.get_text(strip=True) if hasattr(part, "get_text") else ""
                if text:
                    analysis += text + "\n"
            break

    return {
        "description": description.strip(),
        "examples": examples,
        "java_code": java_code,
        "analysis": analysis.strip(),
    }


def extract_examples(sections, content_div):
    """Extract input/output example pairs."""
    examples = []

    # Strategy 1: Look for 用例 section with tables
    for key in sections:
        if "用例" in key or "示例" in key:
            for elem in sections[key]:
                if hasattr(elem, "find_all"):
                    tables = elem.find_all("table") if elem.name == "table" else [elem]
                    if elem.name == "table":
                        tables = [elem]
                    for table in tables:
                        rows = table.find_all("tr")
                        inp, out = "", ""
                        for row in rows:
                            cells = row.find_all(["td", "th"])
                            if len(cells) >= 2:
                                label = cells[0].get_text(strip=True)
                                value = cells[1].get_text(strip=True)
                                if "输入" in label:
                                    inp = value
                                elif "输出" in label:
                                    out = value
                        if inp and out:
                            examples.append({"input": inp, "output": out})

    # Strategy 2: Look for pre/code blocks after 示例/用例 headings
    if not examples:
        all_pres = content_div.find_all("pre")
        i = 0
        while i < len(all_pres) - 1:
            inp = all_pres[i].get_text(strip=True)
            out = all_pres[i + 1].get_text(strip=True)
            # Heuristic: input usually comes before output
            if inp and out and len(inp) < 500 and len(out) < 500:
                examples.append({"input": inp, "output": out})
                i += 2
            else:
                i += 1
        # Limit to first 3 examples
        examples = examples[:3]

    return examples


def extract_java_code(content_div):
    """Extract the Java solution code from code blocks."""
    # Find code blocks with language-java class
    java_blocks = content_div.find_all("code", class_=re.compile(r"language-java"))
    if java_blocks:
        # Pick the longest one (likely the full solution)
        longest = max(java_blocks, key=lambda b: len(b.get_text()))
        return longest.get_text()

    # Fallback: look for code after "Java" heading
    all_code = content_div.find_all("code")
    for code in all_code:
        text = code.get_text()
        if "public class Main" in text or "import java" in text:
            return text

    # Fallback: any pre block with Java-like content
    for pre in content_div.find_all("pre"):
        text = pre.get_text()
        if "public class" in text and "static" in text:
            return text

    return ""


def extract_docx(filepath: Path):
    """Parse a docx file and extract structured content."""
    try:
        from docx import Document
    except ImportError:
        return {"description": "", "examples": [], "java_code": "", "analysis": ""}

    doc = Document(str(filepath))
    paragraphs = [p.text.strip() for p in doc.paragraphs if p.text.strip()]

    description = "\n".join(paragraphs[:20])
    examples = []
    java_code = ""

    # Try to find input/output patterns
    i = 0
    while i < len(paragraphs):
        if "输入" in paragraphs[i] and i + 1 < len(paragraphs):
            inp_lines = []
            i += 1
            while i < len(paragraphs) and "输出" not in paragraphs[i]:
                inp_lines.append(paragraphs[i])
                i += 1
            if i < len(paragraphs) and "输出" in paragraphs[i]:
                i += 1
                out_lines = []
                while i < len(paragraphs) and "说明" not in paragraphs[i] and "示例" not in paragraphs[i]:
                    out_lines.append(paragraphs[i])
                    i += 1
                if inp_lines and out_lines:
                    examples.append({
                        "input": "\n".join(inp_lines),
                        "output": "\n".join(out_lines),
                    })
        else:
            i += 1

    return {
        "description": description,
        "examples": examples[:3],
        "java_code": java_code,
        "analysis": "",
    }


def main():
    results = []
    order = 0

    for folder_name, group in CATEGORY_MAP.items():
        folder = OD_ROOT / folder_name
        if not folder.exists():
            print(f"WARN: {folder} not found", file=sys.stderr)
            continue

        files = sorted(folder.iterdir())
        for f in files:
            if f.suffix not in (".html", ".docx"):
                continue
            # Skip temp files
            if f.name.startswith("~"):
                continue

            order += 1
            score, title = parse_score_and_title(f.name)

            if f.suffix == ".html":
                data = extract_html(f)
            else:
                data = extract_docx(f)

            entry = {
                "order": order,
                "file": str(f.relative_to(OD_ROOT)),
                "title": title,
                "score": score,
                "difficulty": "一星" if score == 100 else "二星",
                "group": group,
                "topic": CATEGORY_TOPIC[group],
                "description": data["description"],
                "examples": data["examples"],
                "java_code": data["java_code"],
                "analysis": data["analysis"],
            }
            results.append(entry)

    OUTPUT.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Extracted {len(results)} questions -> {OUTPUT}")

    # Stats
    has_java = sum(1 for r in results if r["java_code"])
    has_examples = sum(1 for r in results if r["examples"])
    has_desc = sum(1 for r in results if len(r["description"]) > 50)
    print(f"  With Java code: {has_java}/{len(results)}")
    print(f"  With examples:  {has_examples}/{len(results)}")
    print(f"  With description: {has_desc}/{len(results)}")


if __name__ == "__main__":
    main()
