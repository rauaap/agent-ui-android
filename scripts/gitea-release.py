#!/usr/bin/env python3
"""Build the committed version and publish it to Gitea; never allocate codes."""

import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.error import HTTPError
from urllib.parse import quote, urlsplit
from urllib.request import build_opener, HTTPRedirectHandler, Request


class ReleaseError(Exception):
    pass


class APIError(ReleaseError):
    def __init__(self, method, path, status):
        super().__init__(f"Gitea API {method} {path}: HTTP {status}")
        self.status = status


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward the API token to a redirect destination.
        return None


class API:
    def __init__(self, server, repository, token):
        parts = urlsplit(server)
        if parts.scheme not in ("http", "https") or not parts.netloc:
            raise ReleaseError("GITEA_SERVER_URL must be an HTTP(S) URL")
        if parts.username or parts.password or parts.query or parts.fragment:
            raise ReleaseError("GITEA_SERVER_URL must not contain credentials, query, or fragment")
        if not re.fullmatch(r"[^/]+/[^/]+", repository):
            raise ReleaseError("GITEA_REPOSITORY must be owner/repo")
        self.base = server.rstrip("/") + "/api/v1/repos/" + quote(repository, safe="/")
        self.token = token
        self.opener = build_opener(NoRedirects())

    def request(self, method, path, payload=None, data=None, content_type=None):
        if payload is not None:
            data = json.dumps(payload).encode()
            content_type = "application/json"
        headers = {"Authorization": f"token {self.token}", "Accept": "application/json"}
        if content_type:
            headers["Content-Type"] = content_type
        request = Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=120) as response:
                body = response.read()
                return json.loads(body) if body else None
        except HTTPError as error:
            # Avoid logging response bodies or request headers containing secrets.
            raise APIError(method, path, error.code) from None

    def optional(self, path):
        try:
            return self.request("GET", path)
        except APIError as error:
            if error.status == 404:
                return None
            raise

    def items(self, path):
        page = 1
        while True:
            items = self.request("GET", f"{path}?limit=100&page={page}")
            if not items:
                return
            yield from items
            page += 1


def is_current_main(api, sha):
    head = api.request("GET", "/branches/main").get("commit", {}).get("id")
    if not isinstance(head, str) or not head:
        raise ReleaseError("Gitea did not return the main branch commit")
    if head.lower() != sha.lower():
        print(f"Skipping superseded commit {sha}; main is now {head}", flush=True)
        return False
    return True


def upload(api, release_id, apk):
    boundary = "android-release-" + os.urandom(16).hex()
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="attachment"; filename="{apk.name}"\r\n'
        "Content-Type: application/vnd.android.package-archive\r\n\r\n"
    ).encode() + apk.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    return api.request(
        "POST", f"/releases/{release_id}/assets?name={quote(apk.name, safe='')}",
        data=body, content_type=f"multipart/form-data; boundary={boundary}",
    )


def publish(api, sha, name, code, apk):
    tag = f"build-{code}"  # Identifies the committed version, not a counter.
    existing_tag = api.optional(f"/tags/{tag}")
    if existing_tag and existing_tag.get("commit", {}).get("sha", "").lower() != sha.lower():
        raise ReleaseError(f"{tag} already identifies another commit; bump versionCode before publishing")
    release = api.optional(f"/releases/tags/{tag}")
    if release and existing_tag is None:
        raise ReleaseError(f"Release {tag} exists without its tag; cannot verify its commit")
    if release and not release["draft"]:
        print(f"{tag} is already published; leaving it unchanged", flush=True)
        return
    if not is_current_main(api, sha):
        return
    if release is None:
        # Tag/release conflicts fail closed rather than modifying another job's
        # release. Gitea creates the identifying tag at this exact commit.
        release = api.request("POST", "/releases", payload={
            "tag_name": tag, "target_commitish": sha, "name": name, "draft": True,
            "body": f"Android versionCode: {code}\nCommit: {sha}\n",
        })
    else:
        # A same-commit retry may replace attachments only while still a draft.
        # Never delete or update attachments of a published release.
        assets = list(api.items(f'/releases/{release["id"]}/assets'))
        for asset in assets:
            api.request("DELETE", f'/releases/{release["id"]}/assets/{asset["id"]}')
    if not is_current_main(api, sha):
        return
    upload(api, release["id"], apk)
    if not is_current_main(api, sha):
        return
    published = api.request("PATCH", f'/releases/{release["id"]}', payload={"draft": False})
    print(f"Published {apk}: {published['html_url']}", flush=True)


def build_and_publish(api, sha, key):
    # Runs inside the host lock. Older queued pushes do not even build.
    if not is_current_main(api, sha):
        return
    cert = Path("release-cert.sha256")
    if not cert.is_file() or not re.fullmatch(r"[0-9a-f]{64}", cert.read_text().strip()):
        raise ReleaseError("Commit release-cert.sha256 with the expected signing certificate first")
    signing = Path(os.environ.get("SIGNING_DIR", str(Path.home() / ".android-signing"))).resolve()
    if not (signing / "signing.properties").is_file():
        raise ReleaseError(f"Restore the shared release signing key into {signing} first")
    env = os.environ.copy()
    env.pop("GITEA_RELEASE_TOKEN", None)
    subprocess.run([
        "make", "release", f"SIGNING_DIR={signing}",
        f"IMAGE=android-builder-{key}", f"GRADLE_CACHE=android-gradle-cache-{key}",
    ], env=env, check=True)
    app_id, name, code = Path("build/release/info").read_text().splitlines()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.]*", app_id):
        raise ReleaseError("Invalid built applicationId")
    if not re.fullmatch(r"[A-Za-z0-9._+-]+", name):
        raise ReleaseError("Invalid built versionName")
    if not re.fullmatch(r"[1-9][0-9]*", code) or int(code) > 2_100_000_000:
        raise ReleaseError("Invalid built versionCode")
    apk = Path("dist") / f"{app_id}-{name}.apk"
    if not is_current_main(api, sha):
        return
    publish(api, sha, name, code, apk)


def main():
    required = ["GITEA_SERVER_URL", "GITEA_REPOSITORY", "GITEA_SHA", "GITEA_RELEASE_TOKEN"]
    for name in required:
        if not os.environ.get(name):
            raise ReleaseError(f"Missing {name}")
    server, repository, sha, token = (os.environ[name] for name in required)
    if not re.fullmatch(r"(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})", sha):
        raise ReleaseError("GITEA_SHA must be a full commit hash")
    api = API(server, repository, token)
    key = hashlib.sha256(f"{server.rstrip('/')}/{repository}".encode()).hexdigest()[:20]
    lock_dir = Path.home() / ".cache" / "android-release-locks"
    lock_dir.mkdir(parents=True, exist_ok=True)
    # One host/user per repository: protect shared images/caches and publication.
    # This lock contains no version state and doesn't rely on Actions concurrency.
    with (lock_dir / f"{key}.lock").open("a") as lock:
        print("Waiting for this repository's host release lock", flush=True)
        fcntl.flock(lock, fcntl.LOCK_EX)
        build_and_publish(api, sha, key)


if __name__ == "__main__":
    try:
        main()
    except (ReleaseError, OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"gitea-release: {error}", file=sys.stderr)
        sys.exit(1)
