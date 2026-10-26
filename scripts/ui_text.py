import re, sys

path = sys.argv[1]
x = open(path, encoding='utf-8').read()
for m in re.finditer(r'<node[^>]*>', x):
    s = m.group(0)
    t = re.search(r'text="([^"]*)"', s)
    b = re.search(r'bounds="([^"]*)"', s)
    c = re.search(r'class="([^"]*)"', s)
    d = re.search(r'content-desc="([^"]*)"', s)
    text = t.group(1) if t else ''
    desc = d.group(1) if d else ''
    if text or desc:
        print(repr(text), b.group(1) if b else '', (c.group(1).split('.')[-1] if c else ''), 'desc=' + repr(desc))
