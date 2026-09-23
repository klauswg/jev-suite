import io

p = r"D:\work\personal\jev-suite\jev-fidelity\src\main\java\com\jevsuite\fidelity\eval\Samples.java"
src = io.open(p, encoding="utf-8").read()

# 严格口径重标（新增/改变实质事实细节 = DRIFT）：唯一上下文定位 7 处
pairs = [
    # R1 f1: mining +52% detail
    ('its environmental impact.", "PRESERVED",',
     'its environmental impact.", "DRIFT",'),
    # R5 f1: SHA-256 mechanism detail
    ('chaining them in chronological order.", "EQUIVALENT",\n                "Blockchain analysts estimate',
     'chaining them in chronological order.", "DRIFT",\n                "Blockchain analysts estimate'),
    # R7 f2: another contract -> contracts
    ('or create a new contract, and are identified on the blockchain and in the state by an account address.", "PRESERVED",',
     'or create a new contract, and are identified on the blockchain and in the state by an account address.", "DRIFT",'),
    # R9 f2: search -> state space search
    ('AI researchers have adapted and integrated a wide range of techniques, including search and mathematical optimization, formal logic, artificial neural networks, and methods based on statistics, operations research, and economics.", "EQUIVALENT",',
     'AI researchers have adapted and integrated a wide range of techniques, including search and mathematical optimization, formal logic, artificial neural networks, and methods based on statistics, operations research, and economics.", "DRIFT",'),
    # R12 f2: a wealthy family -> the wealthy Musk family
    ('Born into a wealthy family in Pretoria, South Africa, Musk emigrated in 1989 to Canada; he has Canadian citizenship since his mother was born there.", "PRESERVED",',
     'Born into a wealthy family in Pretoria, South Africa, Musk emigrated in 1989 to Canada; he has Canadian citizenship since his mother was born there.", "DRIFT",'),
    # R13 f2: software company -> web software company
    ('In 1995, Musk co-founded the software company Zip2.", "EQUIVALENT",',
     'In 1995, Musk co-founded the software company Zip2.", "DRIFT",'),
    # R14 f1: merged -> merged with Confinity in March 2000
    ('an online payment company that later merged to form PayPal, which was acquired by eBay in 2002.", "EQUIVALENT",',
     'an online payment company that later merged to form PayPal, which was acquired by eBay in 2002.", "DRIFT",'),
]
for old, new in pairs:
    n = src.count(old)
    assert n == 1, f"expect 1 occurrence, got {n}: {old[:60]}"
    src = src.replace(old, new)

anchor = " *    改动句逐条人工对照标注；仅做模板/引用标记剥离，文本逐字保留。"
assert anchor in src
src = src.replace(anchor, anchor + "\n"
    + " *    标注口径（严格版，与 PRD 语义偏移定义一致）：新增或改变实质事实细节\n"
    + " *    （数字、实体、范围、限定词）= DRIFT；纯格式/同义词/澄清性缩写不算。\n"
    + " *    v1 宽松口径的原始结果保留在 eval/results/run-0-55.jsonl，\n"
    + " *    校准报告同时披露两套口径的数字。")

io.open(p, "w", encoding="utf-8").write(src)
print("relabels applied: 7")
