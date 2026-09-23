import json, re, sys, urllib.request

def fetch(revid):
    url = ("https://en.wikipedia.org/w/api.php?action=query&prop=revisions"
           f"&revids={revid}&rvslots=main&rvprop=content&format=json&formatversion=2")
    req = urllib.request.Request(url, headers={"User-Agent": "jev-fidelity-eval/0.1"})
    d = json.load(urllib.request.urlopen(req, timeout=30))
    return d["query"]["pages"][0]["revisions"][0]["slots"]["main"]["content"]

def strip_wiki(wt):
    t = re.sub(r"<ref[^>]*/>", "", wt)
    t = re.sub(r"<ref[^>]*>.*?</ref>", "", t, flags=re.S)
    t = re.sub(r"\{\{[^{}]*\}\}", " ", t)          # 一层模板
    t = re.sub(r"\{\{[^{}]*\}\}", " ", t)          # 两层
    t = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", t)  # 链接
    t = re.sub(r"\[https?://[^\s\]]+\s*([^\]]*)\]", r"\1", t)
    t = re.sub(r"'{2,5}", "", t)                   # 粗斜体
    t = re.sub(r"<[^>]+>", " ", t)
    t = re.sub(r"==+[^=]*==+", " ", t)             # 标题
    t = re.sub(r"&nbsp;", " ", t)
    t = re.sub(r"\s+", " ", t)
    return t.strip()

def sentences(t, n=14):
    parts = re.split(r"(?<=[.!?])\s+", t)
    return [p.strip() for p in parts if len(p.strip()) > 40][:n]

if __name__ == "__main__":
    old_id, new_id = sys.argv[1], sys.argv[2]
    old = sentences(strip_wiki(fetch(old_id)))
    new = sentences(strip_wiki(fetch(new_id)))
    print("===== OLD =====")
    for i, s in enumerate(old): print(f"O{i+1}. {s}")
    print("===== NEW =====")
    for i, s in enumerate(new): print(f"N{i+1}. {s}")
