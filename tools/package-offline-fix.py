#!/usr/bin/env python3
"""Package the verified 1.0.1 offline-library fix without reusing historical device claims."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def sha(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def git(*arguments):
    return subprocess.check_output(['git', *arguments], cwd=ROOT).decode().strip()


def copy(source, target):
    if not source.is_file() or source.is_symlink():
        raise SystemExit(f'Expected a regular file: {source}')
    target.parent.mkdir(parents=True, exist_ok=True)
    with source.open('rb') as src, target.open('xb') as dst:
        shutil.copyfileobj(src, dst)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-log', type=Path, required=True)
    parser.add_argument('--verification', type=Path, required=True)
    parser.add_argument('--previous-verification', type=Path, required=True)
    parser.add_argument('--destination', type=Path, default=Path('/sdcard/Download/DSH-Go'))
    args = parser.parse_args()
    if git('status', '--porcelain', '--untracked-files=all'):
        raise SystemExit('Commit intended source and documentation before packaging')
    commit = git('rev-parse', 'HEAD')
    log = args.build_log.resolve()
    log_text = log.read_text()
    tasks = [':protocol:test', ':protocol:verifyOfflineLibrary', ':app:assembleDebug', ':app:assembleRelease',
             ':app:assembleDebugAndroidTest', ':app:lintDebug', ':app:lintRelease']
    if 'Build exit code: 0' not in log_text or any(f'> Task {task}' not in log_text for task in tasks):
        raise SystemExit('A successful complete fix build is required')
    unit = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    for path in (ROOT / 'protocol/build/test-results/test').glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for key in unit:
            unit[key] += int(suite.get(key, '0'))
    if unit != {'tests': 60, 'failures': 0, 'errors': 0, 'skipped': 0}:
        raise SystemExit(f'Unexpected unit results: {unit}')
    corpus_path = ROOT / 'protocol/build/reports/offline-library-verification.json'
    corpus = json.loads(corpus_path.read_text())
    if not corpus['passed'] or corpus['cardsAfterImport'] != 519 or corpus['answerableQuestionsReadAfterRestart'] != 7355:
        raise SystemExit('The supplied full corpus has not passed storage/import acceptance')
    lint = {}
    for variant in ('debug', 'release'):
        report = ET.parse(ROOT / f'app/build/reports/lint-results-{variant}.xml').getroot()
        levels = {}
        for issue in report.findall('issue'):
            severity = issue.get('severity')
            levels[severity] = levels.get(severity, 0) + 1
        if levels.get('Error', 0) or levels.get('Fatal', 0):
            raise SystemExit(f'Lint errors remain: {variant}')
        lint[variant] = levels
    verification = json.loads(args.verification.read_text())
    previous = json.loads(args.previous_verification.read_text())
    packages = {apk['package']: apk for apk in verification['apks']}
    old = {apk['package']: apk for apk in previous['apks']}
    for package, apk in packages.items():
        if not apk['checksPassed'] or sha(ROOT / apk['file']) != apk['sha256']:
            raise SystemExit(f'APK changed or failed verification: {package}')
    release = packages['app.zhinengtika.nativeapp']
    if release['versionName'] != '1.0.1' or release['versionCode'] != '10001' or release['debuggable']:
        raise SystemExit('Unexpected release identity')
    if release['signerSha256'] != old[release['package']]['signerSha256']:
        raise SystemExit('Release signing identity changed; an in-place upgrade would fail')
    if not all(verification[key] for key in ('independentReleaseAndDebugCertificates', 'testSharesDebugCertificate',
                                             'releaseNotDebuggable', 'release64BitNative16KiBAlignment')):
        raise SystemExit('Artifact safety checks did not pass')
    tracked = git('ls-files').splitlines()
    forbidden = {'.git', '.gradle', '.kotlin', 'android-sdk', 'arm64-sdk-tools', 'downloads', 'artifacts', '__pycache__'}
    for name in tracked:
        path = Path(name)
        if any(part in forbidden for part in path.parts) or path.suffix.lower() in {'.p12', '.pfx', '.jks', '.keystore', '.apk'} or path.name in {'store-password.txt', 'key.properties', 'local.properties'}:
            raise SystemExit(f'Generated/secret file tracked: {name}')
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    delivery = args.destination.resolve() / f'ZhiNengTiKa-native-1.0.1-{stamp}-{commit[:8]}'
    delivery.mkdir(parents=True, exist_ok=False)
    names = {'app.zhinengtika.nativeapp': 'ZhiNengTiKa-1.0.1-release.apk',
             'app.zhinengtika.nativeapp.debug': 'ZhiNengTiKa-1.0.1-debug.apk',
             'app.zhinengtika.nativeapp.debug.test': 'ZhiNengTiKa-1.0.1-ui-tests.apk'}
    for package, apk in packages.items():
        copy(ROOT / apk['file'], delivery / names[package])
    source_zip = delivery / 'ZhiNengTiKa-native-1.0.1-source.zip'
    subprocess.run(['git', 'archive', '--format=zip', '--prefix=ZhiNengTiKa-native/', f'--output={source_zip}', commit], cwd=ROOT, check=True)
    with zipfile.ZipFile(source_zip) as archive:
        entries = {name.removeprefix('ZhiNengTiKa-native/') for name in archive.namelist() if not name.endswith('/')}
        if archive.testzip() or entries != set(tracked):
            raise SystemExit('Source archive does not match the committed tree')
    reports = delivery / 'reports'
    copy(log, reports / 'build.log')
    copy(args.verification, reports / 'apk-verification.json')
    copy(args.verification.parent / 'release-certificate.pem', reports / 'release-certificate.pem')
    copy(corpus_path, reports / 'offline-library-verification.json')
    copy(ROOT / 'app/build/outputs/mapping/release/mapping.txt', reports / 'mapping.txt')
    copy(ROOT / 'app/build/reports/dependency-inventory.tsv', reports / 'dependency-inventory.tsv')
    for variant in ('debug', 'release'):
        for suffix in ('xml', 'html', 'txt'):
            path = ROOT / f'app/build/reports/lint-results-{variant}.{suffix}'
            if path.is_file():
                copy(path, reports / path.name)
    for path in (ROOT / 'protocol/build/test-results/test').glob('TEST-*.xml'):
        copy(path, reports / 'unit-tests' / path.name)
    manifest = {'createdAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'version': '1.0.1',
                'sourceCommit': commit, 'sourceTree': git('rev-parse', 'HEAD^{tree}'),
                'sameReleaseSignerAs100': True, 'unitTests': unit, 'fullCorpusAcceptance': corpus,
                'lint': lint, 'deviceTestsExecuted': False,
                'deviceScope': 'No new device claim is inferred from historical 1.0.0 evidence; any later phone acceptance is recorded separately.',
                'apks': [{key: apk[key] for key in ('package', 'versionName', 'versionCode', 'sha256', 'bytes', 'signerSha256')} for apk in verification['apks']]}
    (delivery / 'delivery-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    (delivery / '安装和整库导入.md').write_text('''# 1.0.1 离线资料库修复版

安装 `ZhiNengTiKa-1.0.1-release.apk`，直接覆盖原生 1.0.0；不要先卸载。签名相同，版本码从 10000 增加为 10001。已有题卡和目录自动迁移。

打开“离线资料库 → 整库 ZIP”，选择此前的 `ZhiNengTiKa-offline-library-20261128T062800Z.zip`，等待进度结束。已有的同编号卡片会更新，全部导入后为519张，目录也按六科分类。顶部可以切换目录/题卡、搜索或按科目查看。

每批只显示一次完成结果，异常集中列在“导入结果”，不会再每张重复弹出通用说明。容量提高为10,000张 / 512MiB，逐卡独立加密保存；界面列表只读元数据。

已通过60项JVM回归和完整519张真实库的主机加密落盘/迁移/重启/重复导入验证。Android UI与文件提供方实际验收如有执行会另行记录。图片仍来自原题库的外部链接；本版没有增加图片下载。

Debug和ui-tests APK仅供开发验收；source.zip为完整对应源码，reports保存构建、验签、Lint、单测和整库验证证据。签名私钥不在交付中。
''')
    with zipfile.ZipFile(delivery / 'ZhiNengTiKa-1.0.1-validation.zip', 'x', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(reports.rglob('*')):
            if path.is_file():
                archive.write(path, path.relative_to(delivery).as_posix())
    files = sorted(path for path in delivery.rglob('*') if path.is_file())
    (delivery / 'SHA256SUMS.txt').write_text(''.join(f'{sha(path)}  {path.relative_to(delivery).as_posix()}\n' for path in files))
    with zipfile.ZipFile(delivery / 'ZhiNengTiKa-1.0.1-validation.zip') as archive:
        if archive.testzip():
            raise SystemExit('Validation ZIP CRC failed')
    print(json.dumps({'delivery': str(delivery), 'releaseApk': str(delivery / names[release['package']]),
                      'sourceZip': str(source_zip), 'releaseSha256': release['sha256'],
                      'releaseBytes': release['bytes'], 'sourceCommit': commit}, ensure_ascii=False))


if __name__ == '__main__':
    main()
