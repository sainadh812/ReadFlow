#!/usr/bin/env python3
"""Fetch the pinned llama.cpp runtime and apply the TeleOCR architecture patch.

Only build-time source is downloaded. User documents and model weights are untouched.
"""
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEST = ROOT / '.deps' / 'teleocr-llama'
COMMIT = '2280f0fd163b03d4331d49e08460f66502e35854'
PATCH = ROOT / 'scripts' / 'patches' / 'teleocr-qwen2vl.patch'


def run(*args):
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE).stdout.strip()


def main():
    if not (DEST / '.git').is_dir():
        DEST.parent.mkdir(parents=True, exist_ok=True)
        run('git', 'init', str(DEST))
        run('git', '-C', str(DEST), 'remote', 'add', 'origin', 'https://github.com/ggml-org/llama.cpp.git')
        run('git', '-C', str(DEST), 'fetch', '--depth', '1', 'origin', COMMIT)
        run('git', '-C', str(DEST), 'checkout', '--detach', 'FETCH_HEAD')
    actual = run('git', '-C', str(DEST), 'rev-parse', 'HEAD')
    if actual != COMMIT:
        raise SystemExit(f'{DEST} is at {actual}; expected {COMMIT}. Move it aside and rerun.')
    result = subprocess.run(['git', '-C', str(DEST), 'apply', '--reverse', '--check', str(PATCH)], capture_output=True)
    if result.returncode:
        run('git', '-C', str(DEST), 'apply', '--check', str(PATCH))
        run('git', '-C', str(DEST), 'apply', str(PATCH))
    actual_diff = run('git', '-C', str(DEST), 'diff', '--no-ext-diff')
    if actual_diff != PATCH.read_text().strip():
        raise SystemExit('Unexpected local changes in the pinned TeleOCR runtime; inspect before building.')
    print(f'TeleOCR runtime ready: {COMMIT} + teleocr-qwen2vl.patch')


if __name__ == '__main__':
    main()
