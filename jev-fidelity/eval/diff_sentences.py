import difflib, json, re, sys, urllib.request

UA = {"User-Agent": "jev-fidelity-eval/0.1"}

def fetch(revid):
    url = ("https://en.wikipedia.org/w/api.php?action=query&prop=revisions"
           f"&revids={revid}&rvslots=main&rvprop=content&format=json&formatversion=2")
    d = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30))
    return d["query"]["pages"][0]["revisions"][0]["slots"]["main"]["content"]

def strip_wiki(wt):
    t = re.sub(r"<ref[^>]*/>", "", wt)
    t = re.sub(r"<ref[^>]*>.*?</ref>", "", t, flags=re.S)
    for _ in range(3):
        t = re.sub(r"\{\{[^{}]*\}\}", " ", t)
    t = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", t)
    t = re.sub(r"\[https?://[^\s\]]+\s*([^\]]*)\]", r"\1", t)
    t = re.sub(r"'{2,5}", "", t)
    t = re.sub(r"<[^>]+>", " ", t)
    t = re.sub(r"==+[^=]*==+", " ", t)
    t = re.sub(r"&nbsp;", " ", t)
    t = re.sub(r"\s+", " ", t)
    return t.strip()

def sentences(t):
    parts = re.split(r"(?<=[.!?])\s+", t)
    return [p.strip() for p in parts if 40 < len(p.strip()) < 400]

if __name__ == "__main__":
    old = sentences(strip_wiki(fetch(sys.argv[1])))
    new = sentences(strip_wiki(fetch(sys.argv[2])))
    print(f"old={len(old)} new={len(new)}")
    sm = difflib.SequenceMatcher(a=old, b=new)
    shown = 0
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal" or shown >= 12:
            continue
        for k in range(max(i2 - i1, j2 - j1)):
            o = old[i1 + k] if i1 + k < i2 else None
            n = new[j1 + k] if j1 + k < j2 else None
            print(f"--- {tag} ---")
            if o: print("OLD:", o[:500])
            if n: print("NEW:", n[:500])
            shown += 1
