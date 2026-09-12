import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
try:
    for n in ET.parse(path).iter('node'):
        tx = (n.get('text') or '').strip()
        cd = (n.get('content-desc') or '').strip()
        if tx:
            print('  ', repr(tx))
except Exception as e:
    print('解析失败:', e)
