"""Capture original HTTP response bytes for individually reviewed P2 cases.

Never overwrites an existing capture; the observation time is the real capture time.
Usage: python capture.py KEY URL [KEY URL ...]
"""
import hashlib
import json
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCES = ROOT / 'sources'
INDEX = ROOT / 'source-files.json'


def agent():
    for line in Path('infrastructure/.env').read_text(encoding='utf-8-sig').splitlines():
        if line.startswith('SEC_USER_AGENT='):
            return line.split('=', 1)[1].strip().strip('"').strip("'")
    raise SystemExit('SEC_USER_AGENT missing')


def main(args):
    index = json.loads(INDEX.read_text()) if INDEX.exists() else []
    known = {r['key'] for r in index}
    ua = agent()
    for key, uri in zip(args[::2], args[1::2], strict=True):
        if key in known:
            print(key, 'already captured'); continue
        # SEC requires its declared contact agent; other hosts receive a generic browser agent.
        header = ua if 'sec.gov' in uri else 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'
        request = urllib.request.Request(uri, headers={'User-Agent': header, 'Accept-Encoding': 'identity'})
        with urllib.request.urlopen(request, timeout=30) as response:
            body = response.read(); status = response.status
            media = response.headers.get('Content-Type', '')
        observed = datetime.now(timezone.utc).isoformat()
        suffix = '.pdf' if 'pdf' in media else '.htm' if 'html' in media else '.txt'
        name = key + suffix
        with (SOURCES / name).open('xb') as stream:
            stream.write(body)
        index.append(dict(key=key, sourceUri=uri, file='sources/' + name, httpStatus=status,
                          mediaType=media, observedAt=observed, bytes=len(body),
                          sourceSha256=hashlib.sha256(body).hexdigest(),
                          captureFormat='ORIGINAL_HTTP_RESPONSE_BYTES'))
        print(key, status, len(body))
        time.sleep(0.3)
    INDEX.write_text(json.dumps(index, indent=2) + '\n')


if __name__ == '__main__':
    main(sys.argv[1:])
