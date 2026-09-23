import json, re, sys, urllib.request

def compare(from_rev, to_rev):
    url = ("https://en.wikipedia.org/w/api.php?action=compare"
           f"&fromrev={from_rev}&torev={to_rev}&format=json&formatversion=2&prop=diff")
    req = urllib.request.Request(url, headers={"User-Agent": "jev-fidelity-eval/0.1"})
    return json.load(urllib.request.urlopen(req, timeout=30))["compare"]["diff"]

def strip_wiki(wt):
    t = re.sub(r"<ref[^>]*/>", "", wt)
    t = re.sub(r"<ref[^>]*>.*?</ref>", "", t, flags=re.S)
    t = re.sub(r"\{\{[^{}]*\}\}", " ", t)
    t = re.sub(r"\{\{[^{}]*\}\}", " ", t)
    t = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", t)
    t = re.sub(r"\[https?://[^\s\]]+\s*([^\]]*)\]", r"\1", t)
    t = re.sub(r"'{2,5}", "", t)
    t = re.sub(r"<[^>]+>", " ", t)
    t = re.sub(r"&nbsp;", " ", t)
    t = re.sub(r"\s+", " ", t)
    return t.strip()

def extract(html):
    """diff HTML → [(removed_text, added_text)] 按行配对。"""
    rows = re.findall(r"<tr>(.*?)</tr>", html, flags=re.S)
    pairs = []
    pending_del = None
    for row in rows:
        d = re.search(r'class="diff-deletedline[^"]*"[^>]*>(.*?)</td>', row, flags=re.S)
        a = re.search(r'class="diff-addedline[^"]*"[^>]*>(.*?)</td>', row, flags=re.S)
        dtxt = strip_wiki(d.group(1)) if d else None
        atxt = strip_wiki(a.group(1)) if a else None
        if dtxt and len(dtxt) > 30:
            pending_del = dtxt
        if atxt and len(atxt) > 30:
            pairs.append((pending_del, atxt))
            pending_del = None
        elif pending_del and not a:
            pairs.append((pending_del, None))   # 纯删除
            pending_del = None
    if pending_del:
        pairs.append((pending_del, None))
    return pairs

if __name__ == "__main__":
    html = compare(sys.argv[1], sys.argv[2])
    pairs = extract(html)
    print(f"changed pairs: {len(pairs)}")
    for i, (d, a) in enumerate(pairs[:12]):
        print(f"--- pair {i+1} ---")
        print("OLD:", (d or "")[:250])
        print("NEW:", (a or "")[:250])
