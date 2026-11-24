import argparse
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('action', choices=('show','tap','long','save','wait','input'))
parser.add_argument('value', nargs='?')
parser.add_argument('--id', action='store_true')
parser.add_argument('--has', action='store_true')
parser.add_argument('--last', action='store_true')
parser.add_argument('--require', action='append', default=[])
args = parser.parse_args()
adb = [str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-P', '5037', '-s', 'emulator-5580']

def cmd(*parts):
    return subprocess.check_output(adb + list(parts), timeout=30)

def dump():
    cmd('shell','uiautomator','dump','/sdcard/leadpipe-guide.xml')
    return ET.fromstring(cmd('exec-out','cat','/sdcard/leadpipe-guide.xml'))

def text(node):
    return node.get('text') or node.get('content-desc') or ''

def get_item(root, name):
    out = []
    for n in root.iter('node'):
        if n.get('enabled') != 'true':
            continue
        val = n.get('resource-id','').split('/')[-1] if args.id else text(n)
        if (name.lower() in val.lower()) if args.has else (name.lower() == val.lower()):
            out.append(n)
    return out[-1 if args.last else 0] if out else None

def show(root):
    for n in root.iter('node'):
        if text(n):
            print(text(n), n.get('resource-id'), n.get('bounds'), 'checked=' + n.get('checked',''))

def get_screen(checks):
    root = None
    for _ in range(7):
        try:
            root = dump()
            if all(check(root) for check in checks):
                return root
        except (subprocess.SubprocessError, ET.ParseError):
            pass
        time.sleep(1.2)
    if root is not None:
        show(root)
    raise SystemExit(f'Expected UI not visible: {args.value} {args.require}')

if args.action == 'show':
    show(get_screen([]))
elif args.action == 'input':
    cmd('shell','input','text',args.value.replace(' ', '%s'))
else:
    checks = [
        lambda markers: lambda root: any(markers.lower() in text(n).lower() for n in root.iter('node'))
        for markers in args.require
    ]
    checks = [entry(needle) for entry, needle in zip(checks, args.require)]
    if args.action in ('tap','long','wait'):
        checks.append(lambda root: get_item(root,args.value) is not None)
    root = get_screen(checks)
    if args.action == 'save':
        Path(args.value).write_bytes(cmd('exec-out','screencap','-p'))
        print('Captured', args.value)
    elif args.action in ('tap','long'):
        n = get_item(root,args.value)
        l,t,r,b = [int(v) for v in re.findall(r'\d+',n.get('bounds',''))]
        x,y = str((l+r)//2),str((t+b)//2)
        if args.action == 'long':
            cmd('shell','input','swipe',x,y,x,y,'820')
        else:
            cmd('shell','input','tap',x,y)
