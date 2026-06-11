#!/usr/bin/env python3
"""
version-manager.py

Show or update the app version (versionName / versionCode).

Usage:
    python3 version-manager.py              # Show current version
    python3 version-manager.py 0.2.0        # Set versionName to 0.2.0
    python3 version-manager.py 0.2.0 200    # Set versionName and versionCode
"""

import argparse
import os
import re
import sys

BUILD_GRADLE = os.path.join('app', 'build.gradle')


def get_current_version():
    """Read versionName and versionCode from build.gradle."""
    if not os.path.exists(BUILD_GRADLE):
        print(f"ERROR: {BUILD_GRADLE} not found")
        return None

    with open(BUILD_GRADLE, 'r', encoding='utf-8') as f:
        content = f.read()

    name_match = re.search(r'versionName\s+"([^"]+)"', content)
    code_match = re.search(r'versionCode\s+(\d+)', content)

    if not name_match or not code_match:
        print("ERROR: Could not find versionName or versionCode in build.gradle")
        return None

    return {
        'versionName': name_match.group(1),
        'versionCode': int(code_match.group(1)),
    }


def set_version(version_name, version_code=None):
    """Update versionName and optionally versionCode in build.gradle."""
    if not os.path.exists(BUILD_GRADLE):
        print(f"ERROR: {BUILD_GRADLE} not found")
        return False

    with open(BUILD_GRADLE, 'r', encoding='utf-8') as f:
        content = f.read()

    old_name = re.search(r'versionName\s+"([^"]+)"', content)
    old_code = re.search(r'versionCode\s+(\d+)', content)

    if not old_name or not old_code:
        print("ERROR: Could not find version fields in build.gradle")
        return False

    # Update versionName
    content = content.replace(
        f'versionName "{old_name.group(1)}"',
        f'versionName "{version_name}"'
    )

    # Update versionCode if provided
    if version_code is not None:
        content = content.replace(
            f'versionCode {old_code.group(1)}',
            f'versionCode {version_code}'
        )
    else:
        # Auto-increment versionCode (simple heuristic)
        new_code = int(old_code.group(1)) + 1
        content = content.replace(
            f'versionCode {old_code.group(1)}',
            f'versionCode {new_code}'
        )
        version_code = new_code

    with open(BUILD_GRADLE, 'w', encoding='utf-8') as f:
        f.write(content)

    return True


def validate_version_name(name):
    """Validate semantic version format: major.minor.patch"""
    if not re.match(r'^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?(\+[a-zA-Z0-9.]+)?$', name):
        print(f"WARNING: '{name}' does not look like a semantic version (e.g., 0.2.0)")
        ans = input("Continue anyway? [y/N]: ").strip().lower()
        return ans in ('y', 'yes')
    return True


def main():
    parser = argparse.ArgumentParser(
        description='Show or update app version',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='''
Examples:
  python3 version-manager.py              # Show current version
  python3 version-manager.py 0.2.0        # Set versionName, auto-increment versionCode
  python3 version-manager.py 0.2.0 200    # Set both versionName and versionCode
        '''
    )
    parser.add_argument('version_name', nargs='?', help='New versionName (e.g., 0.2.0)')
    parser.add_argument('version_code', nargs='?', type=int, help='New versionCode (e.g., 200)')
    args = parser.parse_args()

    # Show current version
    current = get_current_version()
    if not current:
        sys.exit(1)

    print(f"Current version: {current['versionName']} ({current['versionCode']})")

    if not args.version_name:
        # Just showing, done
        return

    # Set new version
    if not validate_version_name(args.version_name):
        sys.exit(1)

    new_code = args.version_code
    if new_code is None:
        new_code = current['versionCode'] + 1
        print(f"Auto-increment versionCode: {current['versionCode']} -> {new_code}")

    print(f"\nSetting:")
    print(f"  versionName: {current['versionName']} -> {args.version_name}")
    print(f"  versionCode: {current['versionCode']} -> {new_code}")

    ans = input("\nApply? [Y/n]: ").strip().lower()
    if ans and ans not in ('y', 'yes'):
        print("Cancelled.")
        sys.exit(0)

    if set_version(args.version_name, new_code):
        print(f"\nUpdated to {args.version_name} ({new_code})")
        print("Build: ./gradlew assembleRelease")
    else:
        sys.exit(1)


if __name__ == '__main__':
    main()
