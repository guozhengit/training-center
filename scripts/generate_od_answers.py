"""
Generate reference answer files for OD questions.

Strategy: wrap the entire original ACM code inside a private static inner class,
redirect System.in/out, and call the original main() method.

Output: output/coding-ai-exam/java/src/main/java/com/guoyongzheng/exam/od/*.java
"""

import json
import re
import glob
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
WORKSPACE_ROOT = PROJECT_ROOT.parent
RAW_JSON = SCRIPT_DIR / "od-raw.json"
OUTPUT_DIR = WORKSPACE_ROOT / "output" / "coding-ai-exam" / "java" / "src" / "main" / "java" / "com" / "guoyongzheng" / "exam" / "od"
STARTER_DIR = PROJECT_ROOT / "starters" / "java" / "src" / "main" / "java" / "com" / "guoyongzheng" / "exam" / "od"

PACKAGE = "com.guoyongzheng.exam.od"


def determine_params_from_starter(order: int) -> str:
    """Read the starter file to determine method signature (must match exactly)."""
    matches = list(STARTER_DIR.glob(f"OD{order:03d}*.java"))
    if matches:
        content = matches[0].read_text(encoding="utf-8")
        if "String input" in content:
            return "String input"
    return "String[] lines"


def get_class_name_from_starter(order: int) -> str:
    """Get the exact class name from the starter file."""
    matches = list(STARTER_DIR.glob(f"OD{order:03d}*.java"))
    if matches:
        return matches[0].stem
    return f"OD{order:03d}Problem{order:03d}"


def find_main_class(java_code: str) -> str:
    """Find the class name that contains public static void main."""
    # Look for class declarations and check if they contain main
    lines = java_code.split("\n")
    brace_depth = 0
    current_class = None
    class_stack = []

    for line in lines:
        stripped = line.strip()
        # Detect class declaration
        m = re.match(r'(?:public\s+)?(?:abstract\s+)?class\s+(\w+)', stripped)
        if m and brace_depth == 0:
            current_class = m.group(1)

        # Check for main method at class level
        if current_class and 'static void main' in stripped:
            return current_class

        brace_depth += line.count('{') - line.count('}')
        if brace_depth <= 0:
            brace_depth = 0
            current_class = None

    # Fallback: just find any class with main
    m = re.search(r'class\s+(\w+)[^{]*\{[^}]*?static\s+void\s+main', java_code, re.DOTALL)
    if m:
        return m.group(1)
    return "Main"


def prepare_inner_code(java_code: str) -> str:
    """Remove imports/package, make top-level classes static+non-public for nesting."""
    lines = java_code.split("\n")
    result = []
    brace_depth = 0

    for line in lines:
        stripped = line.strip()
        # Skip imports and package
        if stripped.startswith("import ") or stripped.startswith("package "):
            continue
        # At top level (depth 0), transform class declarations
        if brace_depth == 0 and re.match(r'(public\s+)?(abstract\s+)?class\s+\w+', stripped):
            # Remove 'public', add 'static'
            new_line = re.sub(r'^(\s*)public\s+', r'\1', line)
            new_line = re.sub(r'(\bclass\s+)', r'static \1', new_line)
            result.append(new_line)
        else:
            result.append(line)
        # Track brace depth
        brace_depth += line.count('{') - line.count('}')
        if brace_depth < 0:
            brace_depth = 0

    return "\n".join(result)


def extract_imports(java_code: str) -> list:
    """Extract import statements."""
    imports = []
    seen = set()
    for line in java_code.split("\n"):
        line = line.strip()
        if line.startswith("import ") and line not in seen:
            seen.add(line)
            imports.append(line)
    return imports


def is_valid_java(java_code: str) -> bool:
    """Basic check that the code is actually Java (not JS/Python)."""
    if 'require(' in java_code or 'console.log' in java_code:
        return False
    if 'const ' in java_code and 'readline' in java_code:
        return False
    if 'public class' not in java_code and 'class ' not in java_code:
        return False
    if 'void main' not in java_code and 'static void main' not in java_code:
        return False
    return True


def generate_reference(entry: dict, class_name: str) -> str:
    """Generate a reference answer Java file."""
    order = entry["order"]
    java_code = entry.get("java_code", "").strip()
    title = entry["title"]
    difficulty = entry.get("difficulty", "")
    topic = entry.get("topic", "")
    params = determine_params_from_starter(order)

    # Placeholder for missing/invalid code
    if not java_code or not is_valid_java(java_code):
        reason = "此题暂无参考解法" if not java_code else "原始代码非Java或结构无法自动转换"
        return f'''package {PACKAGE};

/**
 * {title}
 * \u96be\u5ea6\uff1a{difficulty} | \u5206\u7c7b\uff1a{topic}
 * \u6ce8\u610f\uff1a{reason}\u3002
 */
public final class {class_name} {{

    private {class_name}() {{}}

    public static String solve({params}) {{
        throw new UnsupportedOperationException("\u53c2\u8003\u89e3\u6cd5\u5f85\u8865\u5145");
    }}
}}
'''

    imports = extract_imports(java_code)
    wrapper_imports = [
        "import java.io.ByteArrayInputStream;",
        "import java.io.ByteArrayOutputStream;",
        "import java.io.PrintStream;",
        "import java.nio.charset.StandardCharsets;",
    ]
    all_imports = list(wrapper_imports)
    for imp in imports:
        if imp not in all_imports:
            all_imports.append(imp)
    imports_block = "\n".join(all_imports)

    # Find the class containing main()
    main_class = find_main_class(java_code)

    # Prepare inner code (strip imports, make classes static)
    inner_code = prepare_inner_code(java_code)
    inner_indented = "\n".join(
        "        " + line if line.strip() else ""
        for line in inner_code.split("\n")
    )

    # Input setup
    if "String input" in params:
        input_setup = "        String[] lines = new String[]{input};\n"
    else:
        input_setup = ""

    return f'''package {PACKAGE};

{imports_block}

/**
 * {title}
 * \u96be\u5ea6\uff1a{difficulty} | \u5206\u7c7b\uff1a{topic}
 */
public final class {class_name} {{

    private {class_name}() {{}}

    public static String solve({params}) {{
{input_setup}        String inputData = String.join("\\n", lines) + "\\n";
        ByteArrayInputStream fakeIn = new ByteArrayInputStream(inputData.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream fakeOut = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        java.io.InputStream originalIn = System.in;
        try {{
            System.setIn(fakeIn);
            System.setOut(new PrintStream(fakeOut, true, StandardCharsets.UTF_8));
            {main_class}.main(new String[]{{}});
        }} catch (Exception e) {{
            // swallow
        }} finally {{
            System.setOut(originalOut);
            System.setIn(originalIn);
        }}
        return fakeOut.toString(StandardCharsets.UTF_8).trim();
    }}

{inner_indented}
}}
'''


def main():
    with open(RAW_JSON, "r", encoding="utf-8") as f:
        entries = json.load(f)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    generated = 0
    for entry in entries:
        order = entry["order"]
        class_name = get_class_name_from_starter(order)
        content = generate_reference(entry, class_name)
        out_file = OUTPUT_DIR / f"{class_name}.java"
        with open(out_file, "w", encoding="utf-8") as f:
            f.write(content)
        generated += 1

    print(f"Generated {generated} reference answers in {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
