"""Releases a new version: bumps mod_version, commits, tags v<version>-<minecraft> and pushes.

GitHub Actions (.github/workflows/release.yml) then builds, tests and publishes the release.

Usage: python tools/release.py 0.2.0
"""
import re
import subprocess
import sys
from pathlib import Path

PROPERTIES = Path("gradle.properties")


def git(*args, capture=False):
    result = subprocess.run(["git", *args], check=True, text=True, capture_output=capture)
    return result.stdout.strip() if capture else None


def fail(message):
    print(f"error: {message}")
    sys.exit(1)


def main():
    if len(sys.argv) != 2 or not re.fullmatch(r"\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?", sys.argv[1]):
        fail("usage: python tools/release.py <version>, e.g. 0.2.0 or 0.2.0-beta.1")
    version = sys.argv[1]

    if git("status", "--porcelain", capture=True):
        fail("there are uncommitted changes; commit or stash them first")
    branch = git("branch", "--show-current", capture=True)
    if branch != "main":
        fail(f"releases are made from main, you are on {branch}")

    text = PROPERTIES.read_text(encoding="utf-8")
    minecraft = re.search(r"^minecraft_version=(.+)$", text, re.M).group(1).strip()
    current = re.search(r"^mod_version=(.+)$", text, re.M).group(1).strip()
    tag = f"v{version}-{minecraft}"
    if git("tag", "--list", tag, capture=True):
        fail(f"tag {tag} already exists")

    print(f"Releasing {current} -> {version} for Minecraft {minecraft} as {tag}")
    git("pull", "--ff-only")
    if version != current:
        PROPERTIES.write_text(re.sub(r"^mod_version=.+$", f"mod_version={version}", text, flags=re.M),
                              encoding="utf-8", newline="")
        git("commit", "-am", f"Release {version}")
    git("tag", "-a", tag, "-m", f"Land Claims {version} for Minecraft {minecraft}")
    git("push", "origin", "main")
    git("push", "origin", tag)

    remote = git("remote", "get-url", "origin", capture=True).removesuffix(".git")
    print(f"Done. Follow the build at {remote}/actions")


if __name__ == "__main__":
    main()
