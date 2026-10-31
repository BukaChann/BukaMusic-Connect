#!/usr/bin/env python3
"""Generate BukaMusic launcher assets from the current source artwork.

Naming convention supplied by the project owner:
- ``* App.png`` is ONLY for launcher / executable icons.
- The matching file without ``App`` is for in-app branding and README art.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageOps


ROOT = Path(__file__).resolve().parents[1]

MAIN_LAUNCHER_SIZES = {
    "mipmap-mdpi/ic_launcher.png": 48,
    "mipmap-hdpi/ic_launcher.png": 72,
    "mipmap-xhdpi/ic_launcher.png": 96,
    "mipmap-xxhdpi/ic_launcher.png": 144,
    "mipmap-xxxhdpi/ic_launcher.png": 192,
}

PHONE_LAUNCHER_SIZES = {
    "mipmap-mdpi/ic_launcher.png": 48,
    "mipmap-mdpi/ic_launcher_round.png": 48,
    "mipmap-hdpi/ic_launcher.png": 72,
    "mipmap-hdpi/ic_launcher_round.png": 72,
    "mipmap-xhdpi/ic_launcher.png": 96,
    "mipmap-xhdpi/ic_launcher_round.png": 96,
    "mipmap-xxhdpi/ic_launcher.png": 144,
    "mipmap-xxhdpi/ic_launcher_round.png": 144,
    "mipmap-xxxhdpi/ic_launcher.png": 192,
    "mipmap-xxxhdpi/ic_launcher_round.png": 192,
}


def square_icon(path: Path, size: int) -> Image.Image:
    with Image.open(path) as source:
        return ImageOps.fit(
            source.convert("RGBA"),
            (size, size),
            method=Image.Resampling.LANCZOS,
            centering=(0.5, 0.5),
        )


def contained_mark(path: Path, size: int) -> Image.Image:
    with Image.open(path) as source:
        return ImageOps.pad(
            source.convert("RGBA"),
            (size, size),
            method=Image.Resampling.LANCZOS,
            color=(0, 0, 0, 0),
            centering=(0.5, 0.5),
        )


def save_png(source: Path, target: Path, size: int, preserve_aspect: bool = False) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    image = (contained_mark(source, size) if preserve_aspect
             else square_icon(source, size))
    image.save(target, format="PNG", optimize=True)
    print(f"PNG {size:>4}  {target}")


def save_ico(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    square_icon(source, 256).save(
        target,
        format="ICO",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48),
               (64, 64), (128, 128), (256, 256)],
    )
    print(f"ICO       {target}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--source",
        type=Path,
        default=Path.home() / "Desktop" / "新图标",
        help="Directory containing the six source PNG files",
    )
    parser.add_argument(
        "--desktop-repo",
        type=Path,
        default=ROOT.parent / "BukaMusicDesktop",
        help="Optional standalone BukaMusicDesktop checkout",
    )
    args = parser.parse_args()

    source = args.source
    connect_app = source / "BukaMusic Connect App.png"
    connect_mark = source / "BukaMusic Connect.png"
    phone_app = source / "BukaMusic App.png"
    phone_mark = source / "BukaMusic.png"
    desktop_app = source / "BukaMusic Desktop App.png"
    desktop_mark = source / "BukaMusic Desktop.png"

    required = [
        connect_app, connect_mark, phone_app, phone_mark, desktop_app, desktop_mark,
    ]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise SystemExit("Missing source images:\n" + "\n".join(missing))

    # BukaMusic Connect: launcher artwork only.
    main_res = ROOT / "app" / "src" / "main" / "res"
    for relative, size in MAIN_LAUNCHER_SIZES.items():
        save_png(connect_app, main_res / relative, size)

    # BukaMusic Connect: in-app branding and GitHub / promotional artwork.
    save_png(connect_mark, main_res / "drawable-nodpi" / "brand_mark.png", 512,
             preserve_aspect=True)
    save_png(connect_mark, ROOT / "app" / "src" / "main" / "assets" / "transfer_icon.png",
             256, preserve_aspect=True)
    save_png(connect_mark, ROOT / "docs" / "screenshots" / "icon.png", 384,
             preserve_aspect=True)
    save_png(connect_mark, ROOT / "docs" / "logo.png", 1024, preserve_aspect=True)

    # BukaMusic phone client: launcher artwork only.
    phone_res = ROOT / "mobile" / "phone" / "app" / "src" / "main" / "res"
    for relative, size in PHONE_LAUNCHER_SIZES.items():
        save_png(phone_app, phone_res / relative, size)

    # BukaMusic phone client: in-app branding and README artwork.
    save_png(phone_mark, phone_res / "drawable-nodpi" / "brand_mark.png", 512,
             preserve_aspect=True)
    save_png(phone_mark, ROOT / "mobile" / "phone" / "docs" / "icon.png", 384,
             preserve_aspect=True)

    # BukaMusic Desktop: executable icon and in-app / installer branding.
    desktop_repos = [ROOT / "desktop" / "BukaMusicDesktop"]
    if args.desktop_repo.is_dir() and args.desktop_repo.resolve() not in {
        path.resolve() for path in desktop_repos
    }:
        desktop_repos.append(args.desktop_repo)
    for repo in desktop_repos:
        save_ico(desktop_app, repo / "Assets" / "app.ico")
        save_png(desktop_mark, repo / "Assets" / "logo.png", 512,
                 preserve_aspect=True)


if __name__ == "__main__":
    main()
