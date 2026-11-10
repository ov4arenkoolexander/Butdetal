#!/usr/bin/env python3
"""Static sanity checks for the BUTDETAL Android project (does not replace a device test)."""
from pathlib import Path
import re, sys

ROOT = Path(__file__).resolve().parents[1]
java = (ROOT / 'app/src/main/java/ua/budivelnyk/ua/MainActivity.java').read_text(encoding='utf-8')
html_files = list((ROOT / 'app/src/main/assets').glob('*.html'))
html = '\n'.join(p.read_text(encoding='utf-8') for p in html_files)

methods = set(re.findall(r'@JavascriptInterface\s+public\s+[\w<>\[\]]+\s+(\w+)\s*\(', java))
required = {'clearCredential','saveSession','getSession','clearSession','saveDocument','saveImage','listImages','deleteImage','savePdf','listPdfs','readPdfBase64','importPdf','deletePdf','openPdf','recognizeImage','recognizePdf','beginBackup','appendBackupChunk','finishBackup','importBackup'}
missing = sorted(required - methods)
if missing:
    print('ERROR: missing AndroidStorage methods:', ', '.join(missing))
    sys.exit(1)

for method in required:
    if method not in html:
        print(f'ERROR: method not referenced by HTML: {method}')
        sys.exit(1)

index = (ROOT / 'app/src/main/assets/index.html').read_text(encoding='utf-8')
for auth_method in ('restoreButdetalSession', 'refreshButdetalSession', 'ensureFreshButdetalSession', 'persistButdetalSession', 'clearButdetalSession'):
    if auth_method not in index:
        print(f'ERROR: persistent auth flow is missing: {auth_method}')
        sys.exit(1)
if 'rememberPassword' in index or 'persistRememberedCredential' in index:
    print('ERROR: password persistence must stay disabled; store only the auth session')
    sys.exit(1)

sync_start = index.find('async function syncButdetalRecords(){')
sync_end = index.find('function localArchiveItems', sync_start)
sync_code = index[sync_start:sync_end]
for sync_guard in ("const localOnly=", "if(!accepted)", "pendingUploads.push(...localOnly)", "First sync: never silently discard local-only records"):
    if sync_guard not in sync_code:
        print(f'ERROR: first-sync data protection is missing: {sync_guard}')
        sys.exit(1)

for lang in ('ukr', 'rus', 'eng'):
    f = ROOT / 'app/src/main/assets/tessdata' / f'{lang}.traineddata'
    if not f.is_file() or f.stat().st_size < 100_000:
        print(f'ERROR: missing or suspicious OCR language file: {f}')
        sys.exit(1)

for f in ('index.html', 'armature.html', 'xlsx.full.min.js'):
    if not (ROOT / 'app/src/main/assets' / f).is_file():
        print(f'ERROR: missing asset: {f}')
        sys.exit(1)

gradle = (ROOT / 'app/build.gradle').read_text(encoding='utf-8')
version = re.search(r"versionName\s+'([^']+)'", gradle)
if not version:
    print('ERROR: Gradle versionName is missing')
    sys.exit(1)

if 'getAppVersion' not in java or 'downloadAndInstall' not in java:
    print('ERROR: missing native updater method')
    sys.exit(1)

index = (ROOT / 'app/src/main/assets/index.html').read_text(encoding='utf-8')
if 'releases/latest' not in index or 'ov4arenkoolexander/Butdetal' not in index:
    print('ERROR: latest-release update endpoint missing or points to the wrong repository')
    sys.exit(1)

if 'createButdetalBackup' not in index or 'restoreButdetalBackup' not in index:
    print('ERROR: backup/restore UI is missing')
    sys.exit(1)

print(f'Static checks passed: Android bridge, OCR data, backup/restore, app assets, and in-app updater {version.group(1)}.')
print('NOTE: This does not prove Gradle compilation or runtime OCR correctness.')

# Regression guards for BUTDETAL 2.0.67.
for guard in ("productMetaChip('Армування',x.rebar)", "window.handleNativeBack=handleNativeBack", "function rememberButdetalNavigation", "detail.code==='NoSuchKey'", "if(!blob){missing++;continue}", "scroll-behavior:auto"):
    if guard not in index:
        print(f'ERROR: missing 2.0.67 regression guard: {guard}')
        sys.exit(1)
