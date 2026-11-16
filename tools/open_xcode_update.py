#!/usr/bin/env python3
"""Open a pinned release in a separate worktree, carrying over this Mac's Xcode settings."""
import json
import os
import plistlib
import re
import subprocess
import sys
import tempfile
from pathlib import Path

PROJECT = "iosApp/iosApp.xcodeproj"
PBX = PROJECT + "/project.pbxproj"
INFO = "iosApp/iosApp/Info.plist"
CONFIG = "iosApp/iosApp/Config.xcconfig"
MISSING = object()


def git(*args, cwd=None):
    return subprocess.check_output(["git", *args], cwd=cwd)


def plist(data):
    # Apple's parser handles native OpenStep projects, XML and binary plists.
    xml = subprocess.run(["plutil", "-convert", "xml1", "-o", "-", "--", "-"],
                         input=data, stdout=subprocess.PIPE, check=True).stdout
    return plistlib.loads(xml)


def merge_keys(base, local, incoming, path, conflicts):
    if local == base or local == incoming:
        return incoming
    if incoming == base:
        return local
    if isinstance(local, dict) and isinstance(incoming, dict) and (base is MISSING or isinstance(base, dict)):
        old = {} if base is MISSING else base
        result = {}
        for key in dict.fromkeys([*incoming, *local]):
            value = merge_keys(old.get(key, MISSING), local.get(key, MISSING), incoming.get(key, MISSING),
                               path + "." + key, conflicts)
            if value is not MISSING:
                result[key] = value
        return result
    conflicts.append(path)
    return incoming


def merge_text(base, local, incoming):
    with tempfile.TemporaryDirectory() as folder:
        files = [Path(folder) / name for name in ("local", "base", "incoming")]
        for file, data in zip(files, (local, base, incoming)):
            file.write_bytes(data)
        result = subprocess.run(["git", "merge-file", "-p", "-L", "Updated upstream", "-L", "Stash base",
                                 "-L", "Stashed changes", *files], stdout=subprocess.PIPE)
        if result.returncode < 0 or result.returncode > 127:
            raise RuntimeError("git merge-file недоступен")
        return result.stdout, result.returncode == 0


def openstep(value, level=0):
    # project.pbxproj contains dictionaries, arrays and strings. Quote all keys
    # and values so paths, build variables and multiline shell commands round-trip.
    tab = "\t" * level
    if isinstance(value, dict):
        lines = [openstep(k) + " = " + openstep(v, level + 1) + ";" for k, v in value.items()]
        left, right = "{", "}"
    elif isinstance(value, list):
        lines = [openstep(item, level + 1) + "," for item in value]
        left, right = "(", ")"
    elif isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    else:
        raise ValueError("Неожиданный тип в project.pbxproj")
    return left + "".join("\n" + tab + "\t" + line for line in lines) + "\n" + tab + right


def merge_settings(name, base, local, incoming, conflicts):
    if local == base or local == incoming:
        return incoming
    if incoming == base:
        return local
    text, clean = merge_text(base, local, incoming)
    if name == CONFIG:
        if not clean:
            conflicts.append(name)
        return text if clean else incoming
    expected = merge_keys(plist(base), plist(local), plist(incoming), name, conflicts)
    candidates = [text] if clean else []
    if name == PBX:
        # Xcode can reindent the complete file. This release changes one build
        # phase; transplant just that assignment if it preserves every property.
        script = rb'(?m)^(\s*shellScript\s*=\s*)("(?:\\.|[^"\\])*");'
        source = re.findall(script, incoming)
        if len(source) == 1:
            candidates.append(re.sub(script, lambda m: m[1] + source[0][1] + b";", local, count=1))
    for candidate in candidates + [incoming, local]:
        try:
            if plist(candidate) == expected:
                return candidate
        except (ValueError, plistlib.InvalidFileException, subprocess.CalledProcessError):
            continue
    if name == PBX:
        data = ("// !$*UTF8*$!\n" + openstep(expected) + "\n").encode()
        if plist(data) != expected:
            raise ValueError("Не удалось перенести все настройки project.pbxproj")
        return data
    return plistlib.dumps(expected, sort_keys=False)


def atomic_write(path, data):
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as file:
        temp = Path(file.name)
        try:
            file.write(data)
            file.close()
            temp.chmod(path.stat().st_mode & 0o777)
            os.replace(temp, path)
        finally:
            if temp.exists():
                temp.unlink()


def stash_conflict(name, stages, working, current):
    if set(stages) != {1, 2, 3} or git("cat-file", "blob", stages[2]) != current:
        return False
    # Never replace hand edits (including a partially resolved conflict), staged
    # project changes, or a conflict that did not come from a retained stash.
    marker_text, clean = merge_text(git("cat-file", "blob", stages[1]), current, git("cat-file", "blob", stages[3]))
    if clean or marker_text != working:
        return False
    for stash in git("stash", "list", "--format=%H").decode().splitlines():
        result = subprocess.run(["git", "rev-parse", stash + "^1:" + name, stash + "^2:" + name, stash + ":" + name],
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        if result.returncode == 0 and result.stdout.decode().splitlines() == [stages[1], stages[1], stages[3]]:
            return True
    return False


def open_project(folder):
    print("Проект готов:", folder / PROJECT)
    if sys.platform == "darwin":
        subprocess.run(["open", str(folder / PROJECT)], check=True)


def main():
    if len(sys.argv) != 2:
        raise ValueError("Передайте один SHA релиза после имени скрипта.")
    root = Path(git("rev-parse", "--show-toplevel").decode().strip())
    os.chdir(root)
    rev = git("rev-parse", "--verify", "--end-of-options", sys.argv[1] + "^{commit}").decode().strip()
    folder = root.with_name(root.name + "-ios-" + rev[:10])
    if folder.exists():
        if (folder / ".git").is_file() and git("rev-parse", "HEAD", cwd=folder).decode().strip() == rev and (
            git("rev-parse", "--path-format=absolute", "--git-common-dir", cwd=folder) ==
            git("rev-parse", "--path-format=absolute", "--git-common-dir")
        ):
            open_project(folder)
            return
        raise ValueError("Папка уже существует, выберите другое имя исходной папки: " + str(folder))

    unmerged = git("ls-files", "-u").decode().splitlines()
    conflicts = []
    notes = []
    writes = {}
    repair = None
    for name in (PBX, INFO, CONFIG):
        current = git("show", "HEAD:" + name)
        incoming = git("show", rev + ":" + name)
        if not Path(name).is_file():
            notes.append(name + " отсутствует в исходной папке")
            continue
        working = Path(name).read_bytes()
        stages = {int(line.split()[2]): line.split()[1] for line in unmerged if line.endswith("\t" + name)}
        base, local = current, working
        if stages:
            if set(stages) != {1, 2, 3}:
                conflicts.append(name + " (конфликт удаления)")
                continue
            base, local = (git("cat-file", "blob", stages[n]) for n in (1, 3))
            if b"<<<<<<< " not in working:
                base, local = current, working
            elif name == PBX and len(unmerged) == 3 and stash_conflict(name, stages, working, current):
                issues = []
                fixed = merge_settings(name, base, local, current, issues)
                if not issues:
                    plist(fixed)
                    repair = working, fixed
            if repair is None and b"<<<<<<< " in working:
                notes.append(name + ": взята локальная сторона конфликта; ручные исправления остались в исходном файле")
        try:
            merged = merge_settings(name, base, local, incoming, conflicts)
            if name != CONFIG:
                plist(merged)
        except (ValueError, plistlib.InvalidFileException, subprocess.CalledProcessError):
            notes.append(name + ": локальный файл не удалось разобрать, использована версия релиза")
            continue
        if merged != incoming:
            writes[name] = merged
    git("worktree", "add", "--detach", str(folder), rev)
    try:
        for name, content in writes.items():
            atomic_write(folder / name, content)
    except BaseException:
        git("worktree", "remove", "--force", str(folder))
        raise
    if repair and git("ls-files", "-u").decode().splitlines() == unmerged and Path(PBX).read_bytes() == repair[0]:
        atomic_write(Path(PBX), repair[1])
        try:
            git("restore", "--staged", "--source=HEAD", "--", PBX)
        except subprocess.CalledProcessError:
            atomic_write(Path(PBX), repair[0])
            raise
        print("В исходной папке восстановлен project.pbxproj. Личные настройки остались локальными, stash сохранён.")
    for message in notes:
        print(message)
    if conflicts:
        print("В этих настройках обе стороны изменили одно значение. В новой папке использована версия релиза:")
        print("\n".join("  " + key for key in conflicts))
        print("Локальный вариант сохранён в исходной папке или stash.")
    open_project(folder)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, plistlib.InvalidFileException, subprocess.CalledProcessError) as error:
        sys.exit("Не удалось подготовить проект: " + str(error))
