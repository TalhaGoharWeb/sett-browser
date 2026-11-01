#!/usr/bin/env python3
"""Upload a release asset to uploads.github.com using the custom.github
connector credential (surrogate swapped in by authd, same as gh_full.py)."""
import json
import os
import sys
import urllib.request
import urllib.error

sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/bin")
from dynamic_credentials import add_surrogate_to_request, read_json_response


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        raise RuntimeError("redirect refused")


def main():
    release_id, filepath = sys.argv[1], sys.argv[2]
    name = os.path.basename(filepath)
    url = (
        f"https://uploads.github.com/repos/TalhaGoharWeb/sett-browser"
        f"/releases/{release_id}/assets?name={name}"
    )
    with open(filepath, "rb") as fh:
        data = fh.read()
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/octet-stream")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Content-Length", str(len(data)))
    add_surrogate_to_request(req, "custom.github", allowed_hosts=["uploads.github.com"])
    try:
        with urllib.request.build_opener(NoRedirect()).open(req, timeout=1800) as resp:
            out = read_json_response(resp)
            print(json.dumps({"status": resp.status, "name": out.get("name"),
                              "size": out.get("size"),
                              "download": out.get("browser_download_url")}))
    except urllib.error.HTTPError as e:
        print(json.dumps({"error": f"HTTP {e.code}", "body": e.read().decode()[:500]}))
        sys.exit(1)


if __name__ == "__main__":
    main()
