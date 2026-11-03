#!/usr/bin/env python3
"""Package the Linux app image (`:app-desktop:createDistributable`) as a Flatpak bundle.

The Flatpak (com.edde746.tortuga.json next to this script) holds the app image as built, with its JetBrains Runtime
and libmpv, and in /app/lib the Ubuntu 24.04 libraries mpv-build's libmpv links under sonames the freedesktop runtime
lacks: liblua5.2.so.0, libmujs.so.3 and libdisplay-info.so.1, and libbz2.so.1.0 (Debian's
soname; the runtime has libbz2.so.1). Each Ubuntu package's copyright file, which carries its licence (MIT, ISC, the
bzip2 licence) and copyright notices, is in the repository's licenses/linux/<library>/copyright, which the app image
carries with the other licence texts of the Linux app (licenses/apps.json) in lib/app/resources/licenses, where About
reads them; this script refuses a package whose copyright file differs from it, and the Flatpak copies that folder to
/app/share/licenses/com.edde746.tortuga, and libmpv's licence texts (the libmpv artifact's licenses/, which the app
image carries in lib/app/resources/mpv/licenses) to its mpv-build/. The sandbox gets an X11 socket and no Wayland
one: the app is an Xwayland client inside a Wayland session. It also gets the network, PulseAudio, the GPU (dri), the
Secret Service, where LibsecretStore keeps the account's secrets, and Tortuga in the user's Videos folder
(xdg-videos/Tortuga, created on the host), the default downloads folder: the sandbox's home is in memory. Its file
dialogs are the desktop portal's.

It also holds Noto Sans in /app/share/fonts, where the runtime's fontconfig looks (freedesktop-sdk's 50-flatpak.conf),
with its licence (SIL OFL 1.1) among the licence texts (licenses/linux/noto-sans). Compose's sans serif on Linux is
Noto Sans, else DejaVu Sans, and the runtime has only DejaVu: without it the app's font, and whether a navigation label
fits its 72 dp, depended on the host's fonts. The release's hinted TTFs (those Arch's noto-fonts installs) at the
weights the Compose UI draws: Regular, Medium, SemiBold (the theme's headlines and titles) and Bold. Static files, not
the variable font: Skia's fontconfig font manager opens the face fontconfig matched without its variations, so a
variable font's weights would rest on fontconfig always matching one of its named instances.

Needs flatpak, and flatpak-builder (with unzip) or the org.flatpak.Builder flatpak, with org.freedesktop.Platform and
org.freedesktop.Sdk 26.08 for the target architecture installed for the user. Downloads only the Noto Sans release the
manifest pins by sha256.

  build-flatpak.py --app desktop/app/build/compose/binaries/main/app/Tortuga --libs <dir> --arch x64 [--output <dir>]

--libs is searched for those libraries by name and for their packages' copyright files (usr/share/doc/<package>/
copyright; the release workflow passes the tree dpkg-deb unpacked the Ubuntu packages into). Writes
<output>/tortuga-linux-<arch>.flatpak; check-flatpak.py checks it once installed.
"""

import argparse
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import xml.etree.ElementTree as ET

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[3]
APP_ID = "com.edde746.tortuga"
# Release architecture → (Flatpak architecture, ELF e_machine).
ARCHITECTURES = {"x64": ("x86_64", 62), "arm64": ("aarch64", 183)}
# soname → the Ubuntu package that ships it, and the licence folder of the app's notice of it, which holds that
# package's copyright file.
UBUNTU_LIBS = {
    "liblua5.2.so.0": ("liblua5.2-0", "lua"),
    "libmujs.so.3": ("libmujs3", "mujs"),
    "libdisplay-info.so.1": ("libdisplay-info1", "libdisplay-info"),
    "libbz2.so.1.0": ("libbz2-1.0", "bzip2"),
}


def elf_machine(path: Path) -> int | None:
    """The e_machine of a little-endian 64-bit ELF file, else None."""
    with path.open("rb") as file:
        header = file.read(20)
    if len(header) != 20 or header[:6] != b"\x7fELF\x02\x01":
        return None
    return struct.unpack_from("<H", header, 18)[0]


def marketing_version() -> str:
    match = re.search(r"^MARKETING_VERSION\s*=\s*(\S+)\s*$", (ROOT / "version.xcconfig").read_text(), re.MULTILINE)
    if match is None:
        raise SystemExit("version.xcconfig: no MARKETING_VERSION")
    return match.group(1)


def builder() -> list[str]:
    """flatpak-builder from the host, else the org.flatpak.Builder flatpak."""
    if shutil.which("flatpak-builder"):
        return ["flatpak-builder"]
    if subprocess.run(["flatpak", "info", "--user", "org.flatpak.Builder"], capture_output=True).returncode == 0:
        return ["flatpak", "run", "org.flatpak.Builder"]
    raise SystemExit("Neither flatpak-builder nor the org.flatpak.Builder flatpak (--user) is installed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--app", required=True, type=Path, help="the app image (createDistributable's Tortuga/)")
    parser.add_argument("--libs", required=True, type=Path, help="a tree holding the Ubuntu libraries")
    parser.add_argument("--arch", required=True, choices=ARCHITECTURES)
    parser.add_argument("--output", type=Path, default=Path.cwd())
    args = parser.parse_args()
    architecture, machine = ARCHITECTURES[args.arch]

    app = args.app.resolve()
    launcher = app / "bin" / "Tortuga"
    if not launcher.is_file() or elf_machine(launcher) != machine:
        parser.error(f"{launcher} is not a 64-bit {architecture} executable")
    # The app needs JetBrains Runtime on Linux: on another runtime AWT misses resizes by the window manager.
    # jlink's release file names no vendor; the java.base image carries it (java.vendor).
    modules = app / "lib" / "runtime" / "lib" / "modules"
    if not modules.is_file() or b"JetBrains s.r.o." not in modules.read_bytes():
        parser.error(f"{modules}: the app image's runtime is not JetBrains Runtime")
    if not any((app / "lib" / "app" / "resources" / "mpv").rglob("libmpv.so.2")):
        parser.error(f"{app}: no libmpv.so.2 in the app resources")
    libraries = {}
    for soname, (package, folder) in UBUNTU_LIBS.items():
        found = sorted(args.libs.rglob(soname))
        if not found or elf_machine(found[0].resolve()) != machine:
            parser.error(f"{args.libs}: no {architecture} {soname}")
        notice = sorted(args.libs.rglob(f"doc/{package}/copyright"))
        if not notice:
            parser.error(f"{args.libs}: no usr/share/doc/{package}/copyright")
        shipped = app / "lib" / "app" / "resources" / "licenses" / folder / "copyright"
        if not shipped.is_file() or shipped.read_bytes() != notice[0].read_bytes():
            parser.error(f"{shipped}: not {package}'s copyright file (refresh licenses/linux/{folder} with the package)")
        libraries[soname] = found[0].resolve()
    # The release notes are written into the metainfo with the version bump: its first release must be this version.
    metainfo = SCRIPT_DIR / f"{APP_ID}.metainfo.xml"
    releases = [release.get("version") for release in ET.parse(metainfo).getroot().iter("release")]
    if releases[:1] != [marketing_version()]:
        parser.error(f"{metainfo.name}: the first release is {releases[:1]}, not {marketing_version()} (version.xcconfig)")

    args.output.mkdir(parents=True, exist_ok=True)
    output = args.output.resolve() / f"tortuga-linux-{args.arch}.flatpak"
    # The staging directory sits beside the output, not in /tmp: the org.flatpak.Builder flatpak has its own /tmp.
    with tempfile.TemporaryDirectory(prefix=".tortuga-flatpak-", dir=args.output) as temporary:
        stage = Path(temporary)
        shutil.copytree(app, stage / "Tortuga", symlinks=True)
        (stage / "ubuntu-libs").mkdir()
        for soname, library in libraries.items():
            shutil.copyfile(library, stage / "ubuntu-libs" / soname)
        shutil.copytree(ROOT / "desktop" / "app" / "icons" / "hicolor", stage / "hicolor")
        shutil.copy2(SCRIPT_DIR / f"{APP_ID}.desktop", stage)
        shutil.copy2(SCRIPT_DIR / f"{APP_ID}.json", stage)
        shutil.copy2(metainfo, stage)

        subprocess.run([
            *builder(), "--user", "--disable-rofiles-fuse", "--force-clean", f"--arch={architecture}",
            f"--state-dir={stage / 'state'}", f"--repo={stage / 'repo'}", str(stage / "build"), f"{APP_ID}.json",
        ], check=True, cwd=stage)
        # Published only once flatpak has written the whole bundle.
        bundle = stage / output.name
        subprocess.run([
            "flatpak", "build-bundle", f"--arch={architecture}",
            "--runtime-repo=https://flathub.org/repo/flathub.flatpakrepo",
            str(stage / "repo"), str(bundle), APP_ID, "master",
        ], check=True)
        shutil.move(bundle, output)
    print(f"Created: {output}")


if __name__ == "__main__":
    main()
