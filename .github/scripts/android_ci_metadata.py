"""Read the rewrite version without configuring Gradle or accessing signing secrets."""
import os
from pathlib import Path
import re
import subprocess
import sys


def app_version(properties, app_gradle):
    values = dict(line.strip().split("=", 1) for line in properties.splitlines()
                  if line.strip() and not line.lstrip().startswith(("#", "!")))
    major, minor, patch, build = (int(values[key]) for key in ("major", "minor", "patch", "build"))
    stage = values["stage"]
    if not (all(0 <= value <= 99 for value in (major, minor, patch)) and 1 <= build <= 99
            and stage in ("alpha", "beta", "rc", "stable")):
        raise ValueError("Invalid rewrite version.properties")
    suffix = re.search(r'^val appVersionName\s*=\s*\(rootProject.extra\["appVersionName"\] as String\)\s*\+\s*"(-pixel[0-9]+)"\s*$', app_gradle, re.M)
    if not suffix:
        raise ValueError("Cannot read the app's Pixel version suffix")
    prerelease = "" if stage == "stable" else f"-{stage}.{build}"
    return f"{major}.{minor}.{patch}{prerelease}{suffix[1]}"


def release_request(event, ref, requested, tag):
    tagged = event == "push" and ref.startswith("refs/tags/")
    publish = tagged or (event == "workflow_dispatch" and requested == "true")
    tag = ref.removeprefix("refs/tags/") if tagged else tag
    if publish and not re.fullmatch(r"(?:v|pixel-)[A-Za-z0-9][A-Za-z0-9._-]*", tag):
        raise ValueError("Publishing requires an explicit v* or pixel-* release tag")
    return publish, tag if publish else ""


def self_test():
    properties = "major=2\nminor=0\npatch=0\nstage=alpha\nbuild=1\n"
    source = 'val appVersionName = (rootProject.extra["appVersionName"] as String) + "-pixel11"\n'
    assert app_version(properties, source) == "2.0.0-alpha.1-pixel11"
    assert app_version(properties.replace("alpha", "stable"), source) == "2.0.0-pixel11"
    for invalid_properties, invalid_source in ((properties.replace("build=1", "build=0"), source),
                                              (properties.replace("alpha", "unknown"), source),
                                              (properties, source.replace("-pixel11", "-pixel11\nbad=1"))):
        try:
            app_version(invalid_properties, invalid_source)
        except ValueError:
            pass
        else:
            raise AssertionError("Invalid version accepted")
    for event in ("push", "pull_request", "workflow_call", "workflow_dispatch"):
        assert release_request(event, "refs/heads/pixel-shizuku-rewrite", "false", "v1") == (False, "")
    assert release_request("workflow_dispatch", "refs/heads/main", "true", "pixel-11") == (True, "pixel-11")
    assert release_request("push", "refs/tags/v2.0.0", "false", "") == (True, "v2.0.0")
    for tag in ("", "other-1", "v1\ncommit=bad", "v$(echo bad)"):
        try:
            release_request("workflow_dispatch", "refs/heads/main", "true", tag)
        except ValueError:
            pass
        else:
            raise AssertionError("Invalid release tag accepted")
    print("Android CI metadata checks passed")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif sys.argv[1:]:
        raise SystemExit("Usage: android_ci_metadata.py [--self-test]")
    else:
        publish, tag = release_request(os.environ["GITHUB_EVENT_NAME"], os.environ["GITHUB_REF"],
                                       os.environ.get("REQUEST_RELEASE", "false"), os.environ.get("REQUEST_TAG", ""))
        if publish:
            subprocess.run(["git", "check-ref-format", f"refs/tags/{tag}"], check=True)
        version = app_version(Path("android/version.properties").read_text(), Path("android/app/build.gradle.kts").read_text())
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"commit={commit}\napp_version={version}\npublish_release={str(publish).lower()}\nrelease_tag={tag}\n")
