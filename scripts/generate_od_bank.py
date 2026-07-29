"""
Phase 2 & 3: Convert OD raw data into training-center question bank files.
Generates: content MD, starter Java, test Java, catalog JSON, starter-mapping entries.
"""
import json
import re
import unicodedata
from pathlib import Path

SCRIPTS = Path(__file__).parent
WORKSPACE = SCRIPTS.parent.parent  # jiupainews root
RAW = json.loads((SCRIPTS / "od-raw.json").read_text(encoding="utf-8"))

# Output paths
CONTENT_DIR = WORKSPACE / "output/coding-ai-exam/content/od"
STARTER_DIR = WORKSPACE / "training-center/starters/java/src/main/java/com/guoyongzheng/exam/od"
TEST_DIR = WORKSPACE / "output/coding-ai-exam/java/src/test/java/com/guoyongzheng/exam/od"
CATALOG_OUT = WORKSPACE / "training-center/config/od-questions.json"
MAPPING_OUT = SCRIPTS / "od-starter-mapping.json"

PACKAGE = "com.guoyongzheng.exam.od"


def to_class_name(title: str, order: int) -> str:
    """Generate a safe Java class name from title."""
    # Transliterate Chinese to pinyin-like abbreviation, or just use OD + number
    safe = re.sub(r"[^a-zA-Z0-9]", "", title)
    if len(safe) < 3:
        safe = f"Problem{order:03d}"
    return f"OD{order:03d}{safe[:20]}"


def extract_method_signature(java_code: str, title: str, order: int):
    """
    Attempt to extract a core method from ACM code.
    Returns (class_name, method_signature, imports) or None if manual needed.
    """
    if not java_code:
        return None

    lines = java_code.split("\n")
    imports = [l.strip() for l in lines if l.strip().startswith("import ")]
    # Remove Scanner import
    imports = [i for i in imports if "Scanner" not in i]

    class_name = to_class_name(title, order)

    # Try to find the core logic: look for helper methods besides main
    methods = []
    for i, line in enumerate(lines):
        m = re.match(r"\s*(public|private|static)\s+.*\s+(\w+)\s*\(", line)
        if m and "main" not in line and "class" not in line:
            methods.append(line.strip())

    # Determine return type and params from the problem
    # Heuristic: most OD problems read input and print one result
    # We'll create a generic solve method
    return {
        "class_name": class_name,
        "imports": imports,
        "methods": methods,
    }


def generate_starter(entry: dict, class_name: str) -> str:
    """Generate a starter Java file with TODO stub."""
    info = extract_method_signature(entry["java_code"], entry["title"], entry["order"])

    imports_block = ""
    if info and info["imports"]:
        # Deduplicate and limit
        seen = set()
        clean_imports = []
        for imp in info["imports"]:
            if imp not in seen and "Scanner" not in imp:
                seen.add(imp)
                clean_imports.append(imp)
        imports_block = "\n".join(clean_imports[:10]) + "\n"

    # Determine method signature based on examples
    examples = entry.get("examples", [])
    if examples:
        inp = examples[0]["input"]
        inp_lines = inp.strip().split("\n")
        # Heuristic: single line input -> String param; multi-line -> String[] or specific
        if len(inp_lines) == 1:
            params = "String input"
        else:
            params = "String[] lines"
    else:
        params = "String input"

    body = f'''package {PACKAGE};

{imports_block}
/**
 * {entry["title"]}
 * 难度：{entry["difficulty"]} | 分类：{entry["topic"]}
 */
public final class {class_name} {{

    private {class_name}() {{}}

    /**
     * 请实现此方法。
     * 输入格式参考题目描述中的示例。
     */
    public static String solve({params}) {{
        throw new UnsupportedOperationException("TODO");
    }}
}}
'''
    return body


def generate_test(entry: dict, class_name: str) -> str:
    """Generate a JUnit 5 test file from examples."""
    examples = entry.get("examples", [])
    if not examples:
        # Generate a placeholder test
        return f'''package {PACKAGE};

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class {class_name}Test {{

    @Test
    void placeholder() {{
        // TODO: Add test cases from problem description
        // {entry["title"]}
    }}
}}
'''

    test_methods = []
    for i, ex in enumerate(examples[:3]):
        inp = ex["input"].strip()
        out = ex["output"].strip()

        # Escape for Java string
        inp_escaped = java_escape(inp)
        out_escaped = java_escape(out)

        inp_lines = inp.split("\n")
        if len(inp_lines) == 1:
            call = f'{class_name}.solve("{inp_escaped}")'
        else:
            arr_items = ", ".join(f'"{java_escape(l)}"' for l in inp_lines)
            call = f'{class_name}.solve(new String[]{{{arr_items}}})'

        test_methods.append(f'''    @Test
    void example{i + 1}() {{
        String result = {call};
        assertEquals("{out_escaped}", result.strip());
    }}''')

    tests_block = "\n\n".join(test_methods)

    return f'''package {PACKAGE};

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class {class_name}Test {{

{tests_block}
}}
'''


def java_escape(s: str) -> str:
    """Escape a string for use in Java source."""
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\r", "")


def generate_content_md(entry: dict) -> str:
    """Generate the question description Markdown."""
    lines = [
        f"# {entry['title']}",
        "",
        f"> 难度：{entry['difficulty']}（{entry['score']}分） | 分类：{entry['topic']}",
        "",
        "## 题目描述",
        "",
        entry["description"] if entry["description"] else "（待补充）",
        "",
    ]

    if entry["examples"]:
        lines.append("## 示例")
        lines.append("")
        for i, ex in enumerate(entry["examples"][:3]):
            lines.append(f"### 示例 {i + 1}")
            lines.append("")
            lines.append("输入：")
            lines.append("```")
            lines.append(ex["input"].strip())
            lines.append("```")
            lines.append("")
            lines.append("输出：")
            lines.append("```")
            lines.append(ex["output"].strip())
            lines.append("```")
            lines.append("")

    if entry.get("analysis"):
        lines.append("## 解题思路")
        lines.append("")
        lines.append(entry["analysis"][:1500])
        lines.append("")

    return "\n".join(lines)


def main():
    CONTENT_DIR.mkdir(parents=True, exist_ok=True)
    STARTER_DIR.mkdir(parents=True, exist_ok=True)
    TEST_DIR.mkdir(parents=True, exist_ok=True)

    catalog = []
    mapping = []
    stats = {"total": 0, "with_starter": 0, "with_test": 0, "manual": 0}

    for entry in RAW:
        order = entry["order"]
        qid = f"OD{order:03d}"
        class_name = to_class_name(entry["title"], order)
        stats["total"] += 1

        # 1. Content markdown
        content_file = f"content/od/{qid}.md"
        md = generate_content_md(entry)
        (WORKSPACE / "output/coding-ai-exam" / content_file).parent.mkdir(parents=True, exist_ok=True)
        (WORKSPACE / "output/coding-ai-exam" / content_file).write_text(md, encoding="utf-8")

        # 2. Starter Java
        starter_rel = f"training-center/starters/java/src/main/java/com/guoyongzheng/exam/od/{class_name}.java"
        sandbox_rel = f"java/src/main/java/com/guoyongzheng/exam/od/{class_name}.java"
        starter_code = generate_starter(entry, class_name)
        (STARTER_DIR / f"{class_name}.java").write_text(starter_code, encoding="utf-8")
        stats["with_starter"] += 1

        # 3. Test Java
        test_rel = f"java/src/test/java/com/guoyongzheng/exam/od/{class_name}Test.java"
        test_code = generate_test(entry, class_name)
        (TEST_DIR / f"{class_name}Test.java").write_text(test_code, encoding="utf-8")
        if entry["examples"]:
            stats["with_test"] += 1
        else:
            stats["manual"] += 1

        # 4. Catalog entry
        catalog.append({
            "id": qid,
            "group": entry["group"],
            "order": order,
            "title": entry["title"],
            "difficulty": entry["difficulty"],
            "topic": entry["topic"],
            "language": "java",
            "content_path": content_file,
            "source_path": sandbox_rel,
            "test_path": test_rel,
        })

        # 5. Starter mapping entry
        mapping.append({
            "question_id": qid,
            "starter_path": starter_rel,
            "sandbox_source_path": sandbox_rel,
            "runner_kind": "MAVEN",
            "test_selector": f"{class_name}Test",
        })

    # Write catalog
    CATALOG_OUT.write_text(json.dumps(catalog, ensure_ascii=False, indent=2), encoding="utf-8")

    # Write mapping (to be merged into starter-mapping.json later)
    MAPPING_OUT.write_text(json.dumps(mapping, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"Generated {stats['total']} questions:")
    print(f"  Starters: {stats['with_starter']}")
    print(f"  Tests with real cases: {stats['with_test']}")
    print(f"  Manual (no examples): {stats['manual']}")
    print(f"  Catalog -> {CATALOG_OUT}")
    print(f"  Mapping -> {MAPPING_OUT}")


if __name__ == "__main__":
    main()
