"""Prepare checksums for an existing release APK. Never signs, publishes, or edits an APK."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--aapt2', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    apk = args.apk.resolve(strict=True)
    output = args.output.resolve(strict=True)
    if not output.is_dir() or apk.suffix.lower() != '.apk':
        parser.error('An existing APK and existing output directory are required')
    if not re.fullmatch(r'[A-Za-z0-9._-]+\.apk', apk.name):
        parser.error('Use an APK filename containing only letters, numbers, dots, dashes and underscores')
    dump = subprocess.check_output([str(args.aapt2.resolve(strict=True)), 'dump', 'badging', str(apk)], text=True, encoding='utf-8', errors='replace')
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", dump, re.MULTILINE)
    if not match or match[1] != 'com.dsh.mobile' or "application-debuggable" in dump:
        parser.error('Expected the non-debuggable com.dsh.mobile release APK')
    digest = hashlib.sha256()
    with apk.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    checksums = output / 'SHA256SUMS.txt'
    metadata = output / 'release-metadata.json'
    if checksums.exists() or metadata.exists():
        parser.error('Output metadata already exists; choose a fresh output directory')
    record = {'packageName': match[1], 'versionCode': int(match[2]), 'versionName': match[3], 'tag': 'v' + match[3],
              'fileName': apk.name, 'size': apk.stat().st_size, 'sha256': digest.hexdigest()}
    with checksums.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(f'{digest.hexdigest()}  {apk.name}\n')
    with metadata.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(record, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(json.dumps(record, ensure_ascii=False))
    print('Prepared metadata only. Upload the unchanged APK and SHA256SUMS.txt together when publishing.')

if __name__ == '__main__':
    main()
