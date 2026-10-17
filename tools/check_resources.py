#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""A fast stand-in for a compile, run before Gradle ever starts.

There is no Android SDK, no Gradle and no Kotlin compiler on the machine you
develop on, so the first build happens on GitHub.  The thing that fails most
often on a first build is not the Kotlin - it is a reference to a screen, an
id, a word or a picture that does not exist.  This finds those in about a
second, with a message you can act on, instead of a wall of Gradle text.

It checks:

  * every R.id / R.string / R.drawable / R.layout used in Kotlin resolves
  * every findViewById() target is actually in the layout that screen inflates
  * every @string / @drawable / @color / @style used in XML resolves
  * every activity named in the manifest is a class that exists
  * braces and parentheses balance in every Kotlin file
  * the build file's namespace and JVM level agree with the code

It runs in both places it can live - as this app's own `tools/`, and beside
the till software in the parent project - by looking for app/src/main.

Exit code 0 = fine.  1 = fix these before pushing.
"""
from __future__ import annotations

import os
import re
import sys

# --------------------------------------------------------------------------- #
# where is the app?
# --------------------------------------------------------------------------- #
def _find_project() -> str:
    here = os.path.dirname(os.path.abspath(__file__))
    guesses = (
        os.path.join(here, ".."),                       # mobile/tools/
        os.path.join(here, "..", "..", "mobile"),       # tools/ in the till
        os.path.join(here, "..", "..", "..", "mobile"),
    )
    for guess in guesses:
        candidate = os.path.abspath(guess)
        if os.path.isdir(os.path.join(candidate, "app", "src", "main")):
            return candidate
    return ""


PROJECT = _find_project()
APP = os.path.join(PROJECT, "app", "src", "main") if PROJECT else ""
JAVA = os.path.join(APP, "java")
RES = os.path.join(APP, "res")
MANIFEST = os.path.join(APP, "AndroidManifest.xml")
GRADLE = os.path.join(PROJECT, "app", "build.gradle.kts")

# Styles that come from the Android / Material libraries rather than from this
# app's own themes.  Only OUR names are checked, so a library style never
# turns into a false failure while a typo in one of ours still does.
LIBRARY_STYLES = (
    "Widget.Material", "Widget.AppCompat", "Widget.Design",
    "Theme.Material", "Theme.AppCompat", "ThemeOverlay.",
    "TextAppearance.Material", "TextAppearance.AppCompat",
    "Base.", "RtlOverlay.", "Rtl.", "Platform.", "Animation.",
)

problems: list[str] = []


def bad(msg: str) -> None:
    problems.append(msg)
    print(f"FAIL | {msg}", flush=True)


def ok(msg: str) -> None:
    print(f"PASS | {msg}", flush=True)


def read(path: str) -> str:
    with open(path, "r", encoding="utf-8-sig") as fh:
        # utf-8-sig, not utf-8: a file saved with a byte-order mark starts with
        # an invisible character that is NOT whitespace, so "^package" would
        # silently stop matching and every class in the file would then be
        # reported as missing under a name that is not even its real one.
        return fh.read()


# --------------------------------------------------------------------------- #
def main() -> int:
    if not PROJECT:
        print("FAIL | could not find app/src/main beside or above this script")
        return 1
    print(f"checking the app in {os.path.relpath(PROJECT, os.getcwd()) or '.'}\n",
          flush=True)

    kt_files = _walk(JAVA, ".kt")
    layouts = {os.path.splitext(n)[0]: os.path.join(RES, "layout", n)
               for n in _listing(os.path.join(RES, "layout")) if n.endswith(".xml")}
    drawables = {os.path.splitext(n)[0]
                 for n in _listing(os.path.join(RES, "drawable"))}
    if not kt_files:
        print("FAIL | no Kotlin files found")
        return 1

    _selftest_braces()

    strings = _attrs(os.path.join(RES, "values", "strings.xml"), "string")
    colors = _attrs(os.path.join(RES, "values", "colors.xml"), "color")
    styles = _attrs(os.path.join(RES, "values", "themes.xml"), "style")

    ids_by_layout: dict[str, set[str]] = {}
    all_ids: set[str] = set()
    for key, path in layouts.items():
        found = set(re.findall(r'android:id="@\+id/(\w+)"', read(path)))
        ids_by_layout[key] = found
        all_ids |= found
    ok(f"{len(layouts)} layouts, {len(all_ids)} ids, {len(strings)} words, "
       f"{len(drawables)} pictures, {len(colors)} colours, {len(styles)} themes")

    # ----------------------------------------------------------- the classes --
    classes: set[str] = set()
    members: set[str] = set()          # classes, objects AND top-level functions
    for path in kt_files:
        text = read(path)
        m = re.search(r"^\s*package\s+([\w.]+)", text, re.M)
        pkg = m.group(1) if m else ""
        for cm in re.finditer(r"^\s*(?:\w+\s+)*class\s+(\w+)", text, re.M):
            classes.add(f"{pkg}.{cm.group(1)}")
            members.add(cm.group(1))
        for cm in re.finditer(r"^\s*(?:\w+\s+)*object\s+(\w+)", text, re.M):
            members.add(cm.group(1))
        for cm in re.finditer(r"^fun\s+(\w+)", text, re.M):    # column 0 only
            members.add(cm.group(1))
        if not _balanced(text):
            bad(f"{_rel(path)} has unbalanced braces or parentheses")
    ok(f"{len(classes)} classes and {len(members)} named things declared")

    # R and BuildConfig are generated by the build, not written by us
    class_leaves = members | {"R", "BuildConfig"}

    # ------------------------------------------------- Kotlin references ----
    item_ids: set[str] = set()
    for key in layouts:
        if key.startswith(("item_", "row_")):
            item_ids |= ids_by_layout.get(key, set())

    for path in kt_files:
        text = read(path)
        rel = _rel(path)
        screen = re.search(r"setContentView\(R\.layout\.(\w+)\)", text)
        if screen:
            allowed_ids = ids_by_layout.get(screen.group(1), set()) | item_ids
        else:
            allowed_ids = all_ids

        for rid in re.findall(r"R\.id\.(\w+)", text):
            where = (f"not in {screen.group(1)}.xml or an item layout"
                     if screen else "no layout to look in")
            _need(rel, rid in allowed_ids, f"R.id.{rid}",
                  "not declared in any layout" if rid not in all_ids else where)

        for rs in set(re.findall(r"R\.string\.(\w+)", text)):
            _need(rel, rs in strings, f"R.string.{rs}", "not in strings.xml")

        for rd in set(re.findall(r"R\.drawable\.(\w+)", text)):
            _need(rel, rd in drawables, f"R.drawable.{rd}", "no such drawable")

        for rl in set(re.findall(r"R\.layout\.(\w+)", text)):
            _need(rel, rl in layouts, f"R.layout.{rl}", "no such layout file")

        for imp in re.findall(r"^import\s+([\w.]*pos\.mobile[\w.]*)", text, re.M):
            leaf = imp.split(".")[-1]
            _need(rel, leaf in class_leaves, f"import {imp}",
                  f"no class, object or function named {leaf} in the app")

    # -------------------------------------------------- XML references ------
    for key, path in layouts.items():
        body = read(path)
        rel = _rel(path)
        for ref in set(re.findall(r'@string/(\w+)', body)):
            _need(rel, ref in strings, f"@string/{ref}", "not in strings.xml")
        for ref in set(re.findall(r'@drawable/(\w+)', body)):
            _need(rel, ref in drawables, f"@drawable/{ref}", "no such drawable")
        for ref in set(re.findall(r'@color/(\w+)', body)):
            _need(rel, ref in colors, f"@color/{ref}", "not in colours.xml")
        for ref in set(re.findall(r'@style/([\w.]+)', body)):
            if _from_library(ref):
                continue
            _need(rel, ref in styles, f"@style/{ref}", "not in themes.xml")

    # ------------------------------------------------------ the manifest ----
    manifest = read(MANIFEST)
    for name in set(re.findall(r'android:name="\.([\w.]+)"', manifest)):
        full = f"{_package_of()}.{name}"
        _need(_rel(MANIFEST), full in classes, f"activity {name}",
              "the manifest names a screen that does not exist")
    for ref in set(re.findall(r'@string/(\w+)', manifest)):
        _need(_rel(MANIFEST), ref in strings, f"@string/{ref}", "not in strings.xml")
    for ref in set(re.findall(r'@drawable/(\w+)', manifest)):
        _need(_rel(MANIFEST), ref in drawables, f"@drawable/{ref}", "no such drawable")
    for ref in set(re.findall(r'@xml/(\w+)', manifest)):
        _need(_rel(MANIFEST),
              os.path.exists(os.path.join(RES, "xml", f"{ref}.xml")),
              f"@xml/{ref}", "no such file in res/xml")
    for ref in set(re.findall(r'@style/([\w.]+)', manifest)):
        if _from_library(ref):
            continue
        _need(_rel(MANIFEST), ref in styles, f"@style/{ref}", "not in themes.xml")

    # --------------------------------------------------------- build file ---
    if os.path.exists(GRADLE):
        g = read(GRADLE)
        ns = re.search(r'namespace\s*=\s*"([^"]+)"', g)
        if ns and ns.group(1) != _package_of():
            bad(f"app/build.gradle.kts namespace {ns.group(1)!r} does not match "
                f"the package the Kotlin files use ({_package_of()})")
        else:
            ok(f"namespace matches the Kotlin package ({_package_of()})")
        if "jvmTarget" in g and "JavaVersion.VERSION_17" in g:
            ok("JVM target and Java level agree (17)")
        else:
            bad("app/build.gradle.kts JVM target / Java level disagree")
        ver = re.search(r"minSdk\s*=\s*(\d+)", g)
        if ver:
            ok(f"minSdk {ver.group(1)}")
    else:
        bad("app/build.gradle.kts is missing")

    print(f"\n{len(kt_files)} Kotlin files, {len(layouts)} layouts checked")
    if problems:
        print(f"{len(problems)} problem(s) - fix before pushing")
        return 1
    print("no problems found")
    return 0


# --------------------------------------------------------------------------- #
def _package_of() -> str:
    for path in _walk(JAVA, ".kt"):
        m = re.search(r"^\s*package\s+([\w.]+)", read(path), re.M)
        if m:
            return m.group(1)
    return "com.mughalezam.pos.mobile"


def _from_library(name: str) -> bool:
    return name.startswith(LIBRARY_STYLES)


def _selftest_braces() -> None:
    """A checker that reports nonsense is worse than none at all."""
    cases = [
        ("a sound file is accepted",
         _balanced('fun a() { val s = "http://x/y" ; val c = \'(\' }'), True),
        ("a missing } is caught",
         _balanced('fun a() { val s = "http://x/y"'), False),
        ("an extra ) is caught",
         _balanced("fun a() { val c = '(' } )"), False),
        ("a bracket inside a string is ignored",
         _balanced('val s = "(((" + ")))"'), True),
        ("a bracket inside a comment is ignored",
         _balanced("// ))))\nfun a() {}"), True),
        ("a bracket inside a URL is ignored",
         _balanced('val u = "http://a/b"'), True),
    ]
    for name, got, want in cases:
        if got == want:
            ok(f"self-test: {name}")
        else:
            bad(f"self-test: {name} (got {got}, expected {want})")


def _balanced(text: str) -> bool:
    """Braces and parentheses, ignoring strings and comments.

    Hand-scanned rather than regex'd: a naive "// strip comments first" eats
    the middle of "http://192.168.10.50", unbalances every quote and then
    reports false errors across the whole file.

    Only the OPENING brackets get a counter.  Counting the closers too is the
    trap: the totals then come out symmetric and look balanced while the real
    check never ran at all.
    """
    openers = ("{", "(", "[")
    closers = {"}": "{", ")": "(", "]": "["}
    depth = {c: 0 for c in openers}
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if ch == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if ch in ("\"", "'"):
            quote = ch
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == quote:
                    i += 1
                    break
                i += 1
            continue
        if ch in openers:
            depth[ch] += 1
        elif ch in closers:
            depth[closers[ch]] -= 1
            if depth[closers[ch]] < 0:
                return False
        i += 1
    return all(v == 0 for v in depth.values())


def _need(rel: str, passed: bool, what: str, why: str) -> None:
    if not passed:
        bad(f"{rel}: {what} - {why}")


def _attrs(path: str, tag: str) -> set[str]:
    """Every <tag name="..."> in a values file.  Names may hold a dot:
    Theme.MughalPos would otherwise be missed entirely."""
    if not os.path.exists(path):
        return set()
    return set(re.findall(rf'<{tag}\s+name="([\w.]+)"', read(path)))


def _walk(base: str, suffix: str) -> list[str]:
    out = []
    for dirpath, _dirs, files in os.walk(base):
        for name in files:
            if name.endswith(suffix):
                out.append(os.path.join(dirpath, name))
    return sorted(out)


def _listing(path: str) -> list[str]:
    return os.listdir(path) if os.path.isdir(path) else []


def _rel(path: str) -> str:
    return os.path.relpath(path, PROJECT).replace("\\", "/")


if __name__ == "__main__":
    sys.exit(main())
