#!/usr/bin/env python3
"""Build this project without putting signing credentials in Gradle files or argv."""
import argparse
import datetime
import os
from pathlib import Path
import secrets
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
JAVA17 = Path('/usr/lib/jvm/java-17-openjdk-arm64')


def signing_environment(directory, initialize):
    directory = directory.expanduser().resolve()
    if directory.is_relative_to(ROOT):
        raise SystemExit('Keep release signing materials outside the source repository')
    key = directory / 'native-release.p12'
    password_file = directory / 'store-password.txt'
    env = os.environ.copy()
    if not key.exists():
        if not initialize:
            raise SystemExit('Signing key missing. After choosing an independent identity, use --init-signing once.')
        if password_file.exists():
            raise SystemExit('Incomplete existing signing directory; inspect it before proceeding')
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        directory.chmod(0o700)
        password = secrets.token_urlsafe(36)
        with password_file.open('x') as target:
            target.write(password + '\n')
        password_file.chmod(0o600)
        env['ZHINENGTIKA_INIT_PASSWORD'] = password
        java_bin = Path(env.get('JAVA_HOME', str(JAVA17))) / 'bin' / 'keytool'
        subprocess.run([
            str(java_bin), '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12',
            '-alias', 'native-release', '-keyalg', 'RSA', '-keysize', '3072',
            '-sigalg', 'SHA256withRSA', '-validity', '10000',
            '-dname', 'CN=ZhiNengTiKa Native Release, OU=Independent Android Rewrite',
            '-storepass:env', 'ZHINENGTIKA_INIT_PASSWORD', '-keypass:env', 'ZHINENGTIKA_INIT_PASSWORD',
        ], env=env, check=True)
        key.chmod(0o600)
        env.pop('ZHINENGTIKA_INIT_PASSWORD', None)
        print('Created independent release key outside repository:', directory, flush=True)
    if not password_file.is_file():
        raise SystemExit('Signing password file missing; never overwrite an existing identity')
    password = password_file.read_text().strip()
    env.update({
        'ZHINENGTIKA_KEYSTORE': str(key),
        'ZHINENGTIKA_KEY_ALIAS': 'native-release',
        'ZHINENGTIKA_STORE_PASSWORD': password,
        'ZHINENGTIKA_KEY_PASSWORD': password,
    })
    return env


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', action='store_true', help='Load external signing identity')
    parser.add_argument('--init-signing', action='store_true', help='Create a new local identity if none exists; explicit consent required')
    parser.add_argument('--signing-dir', type=Path, default=ROOT.parent / '.zhinengtika-native-signing')
    parser.add_argument('--workers', type=int, choices=range(1, 5), default=1, help='Maximum concurrent Gradle workers (default: 1 for phone hosts)')
    parser.add_argument('tasks', nargs='*', default=[':app:assembleDebug'])
    args = parser.parse_args()
    if args.init_signing and not args.release:
        parser.error('--init-signing requires --release')
    env = signing_environment(args.signing_dir, args.init_signing) if args.release else os.environ.copy()
    if 'JAVA_HOME' not in env and JAVA17.exists():
        env['JAVA_HOME'] = str(JAVA17)
    local_sdk = ROOT / 'tools' / 'android-sdk'
    if 'ANDROID_HOME' not in env and local_sdk.exists():
        env['ANDROID_HOME'] = str(local_sdk)
    # proot JDK networking workaround, unrelated to app networking.
    env.setdefault('JAVA_TOOL_OPTIONS', '-Djdk.net.usePlainSocketImpl=true')
    command = ['bash', str(ROOT / 'gradlew'), '--no-daemon', f'--max-workers={args.workers}', '--console=plain']
    native_aapt2 = ROOT / 'tools' / 'arm64-sdk-tools' / 'build-tools' / 'aapt2'
    if native_aapt2.is_file() and os.uname().machine in ('aarch64', 'arm64'):
        command.append('-Pandroid.aapt2FromMavenOverride=' + str(native_aapt2))
    command.extend(args.tasks)
    logs = ROOT / 'artifacts' / 'logs'
    logs.mkdir(parents=True, exist_ok=True)
    now = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    log = logs / (now + '.log')
    print('Build log:', log, flush=True)
    with log.open('x') as target:
        process = subprocess.Popen(command, cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        for line in process.stdout:
            target.write(line)
            target.flush()
            sys.stdout.write(line)
            sys.stdout.flush()
        code = process.wait()
        target.write(f'\nBuild exit code: {code}\n')
    sys.exit(code)
