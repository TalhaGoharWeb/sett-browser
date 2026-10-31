#!/usr/bin/env python3
"""Vendor org.mozilla.* Maven artifacts into ./local-maven using curl.

Java/Gradle cannot reach maven.mozilla.org through this sandbox's proxy
(read timeouts, proven), while curl can. This walks POM + Gradle module
metadata from seed artifacts and downloads every org.mozilla.* file
(pom, module metadata, aar/jar) in Maven layout. Non-Mozilla deps still
resolve from google()/mavenCentral() at build time.
"""
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import deque

MOZILLA = "https://maven.mozilla.org/maven2"
CENTRAL = "https://repo.maven.apache.org/maven2"
GOOGLE = "https://dl.google.com/dl/android/maven2"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "local-maven")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

SEEDS = [
    "org.mozilla.components:browser-engine-gecko:159.0",
    "org.mozilla.components:browser-state:159.0",
    "org.mozilla.components:browser-session-storage:159.0",
    "org.mozilla.components:feature-session:159.0",
    "org.mozilla.components:feature-tabs:159.0",
    "org.mozilla.components:feature-findinpage:159.0",
    "org.mozilla.components:feature-downloads:159.0",
    "org.mozilla.components:lib-fetch-httpurlconnection:159.0",
    "org.mozilla.components:support-ktx:159.0",
    "org.mozilla.components:support-utils:159.0",
]


def path_for(g, a, v, filename):
    return f"{g.replace('.', '/')}/{a}/{v}/{filename}"


def fetch(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return True
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    subprocess.run(["sleep", "0.2"])
    r = subprocess.run(
        ["curl", "-sfL", "--retry", "6", "--retry-delay", "4", "--retry-all-errors",
         "-o", dest + ".tmp", url],
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        if os.path.exists(dest + ".tmp"):
            os.unlink(dest + ".tmp")
        return False
    os.rename(dest + ".tmp", dest)
    return True


def fetch_mozilla(g, a, v, filename):
    dest = os.path.join(OUT, path_for(g, a, v, filename))
    return fetch(f"{MOZILLA}/{path_for(g, a, v, filename)}", dest)


def text(el, tag):
    child = el.find(f"m:{tag}", NS)
    return child.text.strip() if child is not None and child.text else None


def resolve_props(value, props):
    if value is None:
        return None
    for _ in range(5):
        m = re.search(r"\$\{([^}]+)\}", value)
        if not m:
            break
        value = value.replace(m.group(0), props.get(m.group(1), m.group(0)))
    return value


def parse_pom(g, a, v):
    """Return (deps, packaging). deps = list of (g, a, v). Mozilla groups only are followed by caller."""
    pom_path = os.path.join(OUT, path_for(g, a, v, f"{a}-{v}.pom"))
    if not os.path.exists(pom_path):
        return [], "jar"
    tree = ET.parse(pom_path)
    root = tree.getroot()
    props = {}
    props_el = root.find("m:properties", NS)
    if props_el is not None:
        for child in props_el:
            props[child.tag.split("}")[-1]] = (child.text or "").strip()
    props.setdefault("project.version", v)
    props.setdefault("pom.version", v)
    props.setdefault("project.groupId", g)
    props.setdefault("pom.groupId", g)
    packaging = text(root, "packaging") or "jar"

    managed = {}
    dm = root.find("m:dependencyManagement/m:dependencies", NS)
    if dm is not None:
        for dep in dm.findall("m:dependency", NS):
            dg, da = text(dep, "groupId"), text(dep, "artifactId")
            dv = resolve_props(text(dep, "version"), props)
            if dg and da and dv:
                managed[(dg, da)] = dv

    deps = []
    deps_el = root.find("m:dependencies", NS)
    if deps_el is not None:
        for dep in deps_el.findall("m:dependency", NS):
            scope = text(dep, "scope") or "compile"
            optional = text(dep, "optional") == "true"
            if scope in ("test", "provided", "system") or optional:
                continue
            dg = resolve_props(text(dep, "groupId"), props)
            da = resolve_props(text(dep, "artifactId"), props)
            dv = resolve_props(text(dep, "version"), props) or managed.get((dg, da))
            if dg and da and dv and not dv.startswith("$"):
                deps.append((dg, da, dv))
    return deps, packaging


def parse_module(g, a, v):
    """Gradle module metadata: return dep GAVs, following available-at redirects."""
    mod_path = os.path.join(OUT, path_for(g, a, v, f"{a}-{v}.module"))
    deps = []
    if not os.path.exists(mod_path):
        return deps
    try:
        data = json.load(open(mod_path))
    except Exception:
        return deps
    for variant in data.get("variants", []):
        for dep in variant.get("dependencies", []):
            ver = dep.get("version", {})
            dv = ver.get("requires") or ver.get("strictly")
            if dep.get("group") and dep.get("module") and dv:
                deps.append((dep["group"], dep["module"], dv))
        for avail in variant.get("available-at", []) if isinstance(variant.get("available-at"), list) else []:
            pass
        avail = variant.get("available-at")
        if isinstance(avail, dict):
            url = avail.get("url", "")
            m = re.match(r"\.\./\.\./([^/]+)/([^/]+)/([^/]+)\.module", url)
            if m:
                deps.append((m.group(1).replace("/", "."), m.group(2), m.group(3)))
    return deps


def main():
    queue = deque()
    seen = set()
    for seed in SEEDS:
        g, a, v = seed.split(":")
        queue.append((g, a, v))
    downloaded = 0
    while queue:
        g, a, v = queue.popleft()
        key = (g, a, v)
        if key in seen:
            continue
        seen.add(key)
        if not g.startswith("org.mozilla"):
            continue
        # metadata + pom
        ok_pom = fetch_mozilla(g, a, v, f"{a}-{v}.pom")
        fetch_mozilla(g, a, v, f"{a}-{v}.module")
        if not ok_pom:
            print(f"  !! POM missing: {g}:{a}:{v}")
            continue
        deps, packaging = parse_pom(g, a, v)
        deps += parse_module(g, a, v)
        # artifact file
        if packaging != "pom":
            ext = "aar" if packaging == "aar" else "jar"
            if fetch_mozilla(g, a, v, f"{a}-{v}.{ext}"):
                downloaded += 1
            else:
                # try the other extension before giving up
                alt = "jar" if ext == "aar" else "aar"
                if fetch_mozilla(g, a, v, f"{a}-{v}.{alt}"):
                    downloaded += 1
                else:
                    print(f"  !! artifact missing: {g}:{a}:{v} ({packaging})")
        for dg, da, dv in deps:
            if dg.startswith("org.mozilla") and (dg, da, dv) not in seen:
                queue.append((dg, da, dv))
    print(f"vendored GAVs: {len(seen)}, artifacts downloaded this run: {downloaded}")
    total = subprocess.run(["du", "-sh", OUT], capture_output=True, text=True).stdout.strip()
    print(total)


if __name__ == "__main__":
    main()
