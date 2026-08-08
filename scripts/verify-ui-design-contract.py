#!/usr/bin/env python3
"""Verify cross-platform design tokens and README screenshot invariants."""

from __future__ import annotations

import json
import re
import struct
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TOKENS_PATH = ROOT / "docs/design/alpine-product-tokens.json"
KOTLIN_COLORS = ROOT / (
    "alpine-chat-feature/src/main/java/dev/alpine/chat/feature/ui/theme/Color.kt"
)
KOTLIN_SHAPES = ROOT / (
    "alpine-chat-feature/src/main/java/dev/alpine/chat/feature/ui/theme/Shape.kt"
)
FLUTTER_APP = ROOT / "apps/mobile_agent/lib/src/mobile_agent_app.dart"
README = ROOT / "README.md"
SCREENSHOT_DIR = ROOT / "docs/assets/screenshots"
INTEGRATED_BUILD = ROOT / "integrated-app/build.gradle.kts"
DEV_APPLICATIONS = {
    "alpine-runtime-probe": ("[DEV] Runtime Probe", "@drawable/ic_dev_runtime"),
    "alpine-llm-bridge-probe": ("[DEV] Bridge Probe", "@drawable/ic_dev_bridge"),
    "alpine-integration-sample": ("[SAMPLE] Alpine XML", "@drawable/ic_dev_xml_sample"),
}


def fail(message: str) -> None:
    raise AssertionError(message)


def normalize_hex(value: str) -> str:
    value = value.upper()
    if not re.fullmatch(r"#[0-9A-F]{6}", value):
        fail(f"invalid RGB token: {value}")
    return value


def png_dimensions(path: Path) -> tuple[int, int]:
    data = path.read_bytes()[:24]
    if len(data) != 24 or data[:8] != b"\x89PNG\r\n\x1a\n":
        fail(f"not a PNG file: {path.relative_to(ROOT)}")
    return struct.unpack(">II", data[16:24])


def verify_tokens(tokens: dict[str, object]) -> None:
    colors = tokens["colors"]
    if not isinstance(colors, dict):
        fail("colors must be an object")
    expected = {key: normalize_hex(str(value)) for key, value in colors.items()}

    kotlin = KOTLIN_COLORS.read_text(encoding="utf-8")
    kotlin_names = {
        "paper": "Paper",
        "ink": "Ink",
        "acid": "Acid",
        "slate": "Slate",
        "warning": "Warning",
        "muted": "Muted",
        "outline_soft": "OutlineSoft",
        "surface_raised": "SurfaceRaised",
    }
    for token_name, kotlin_name in kotlin_names.items():
        hex_value = expected[token_name].removeprefix("#")
        pattern = rf"val\s+{re.escape(kotlin_name)}\s*=\s*Color\(0xFF{hex_value}\)"
        if not re.search(pattern, kotlin, flags=re.IGNORECASE):
            fail(f"Kotlin token drift: {token_name}={expected[token_name]}")

    flutter = FLUTTER_APP.read_text(encoding="utf-8")
    for token_name in ("ink", "paper", "acid", "slate"):
        hex_value = expected[token_name].removeprefix("#")
        pattern = rf"const\s+_{token_name}\s*=\s*Color\(0xFF{hex_value}\)"
        if not re.search(pattern, flutter, flags=re.IGNORECASE):
            fail(f"Flutter token drift: {token_name}={expected[token_name]}")

    shapes = tokens["radius_dp"]
    if not isinstance(shapes, dict):
        fail("radius_dp must be an object")
    shape_source = KOTLIN_SHAPES.read_text(encoding="utf-8")
    shape_names = {
        "extra_small": "extraSmall",
        "small": "small",
        "medium": "medium",
        "large": "large",
        "extra_large": "extraLarge",
    }
    for token_name, kotlin_name in shape_names.items():
        value = int(shapes[token_name])
        pattern = rf"{kotlin_name}\s*=\s*RoundedCornerShape\({value}\.dp\)"
        if not re.search(pattern, shape_source):
            fail(f"Kotlin radius drift: {token_name}={value}")


def verify_screenshots(tokens: dict[str, object]) -> None:
    contract = tokens["readme_screenshot"]
    if not isinstance(contract, dict):
        fail("readme_screenshot must be an object")
    expected_source = (
        int(contract["source_width_px"]),
        int(contract["source_height_px"]),
    )
    screenshots = sorted(SCREENSHOT_DIR.glob("*.png"))
    if len(screenshots) != int(contract["count"]):
        fail(f"expected {contract['count']} screenshots, found {len(screenshots)}")
    for screenshot in screenshots:
        actual = png_dimensions(screenshot)
        if actual != expected_source:
            fail(
                f"screenshot size mismatch: {screenshot.name} "
                f"expected={expected_source} actual={actual}"
            )

    readme = README.read_text(encoding="utf-8")
    try:
        section = readme.split("## 📱 앱 화면", 1)[1].split(
            "## 🧭 두 가지 실행 모드", 1
        )[0]
    except IndexError as error:
        raise AssertionError("README screenshot section not found") from error
    image_tags = re.findall(
        r'<img\b[^>]*src="(docs/assets/screenshots/[^"]+\.png)"[^>]*>',
        section,
    )
    if len(image_tags) != int(contract["count"]):
        fail(f"README expected {contract['count']} screenshot tags, found {len(image_tags)}")
    if len(set(image_tags)) != len(image_tags):
        fail("README screenshot references must be unique")

    width = int(contract["preview_width"])
    height = int(contract["preview_height"])
    full_tags = re.findall(r"<img\b[^>]*docs/assets/screenshots/[^>]+>", section)
    for tag in full_tags:
        if f'width="{width}"' not in tag or f'height="{height}"' not in tag:
            fail(f"README preview size drift: {tag}")
    for reference in image_tags:
        if not (ROOT / reference).is_file():
            fail(f"README screenshot missing: {reference}")


def verify_product_artifact_boundary() -> None:
    """Keep diagnostic launchers identifiable and outside the product dependency graph."""
    integrated_build = INTEGRATED_BUILD.read_text(encoding="utf-8")
    forbidden_modules = (*DEV_APPLICATIONS.keys(), "sample", "demo-chatbot")
    for module in forbidden_modules:
        dependency = f'project(":{module}")'
        if dependency in integrated_build:
            fail(f"product artifact must not depend on diagnostic module: {module}")

    integrated_manifest = (
        ROOT / "integrated-app/src/main/AndroidManifest.xml"
    ).read_text(encoding="utf-8")
    for marker in ("Probe", "[DEV]", "[SAMPLE]"):
        if marker in integrated_manifest:
            fail(f"product manifest contains diagnostic marker: {marker}")

    icons: list[bytes] = []
    for module, (expected_label, expected_icon) in DEV_APPLICATIONS.items():
        manifest_path = ROOT / module / "src/main/AndroidManifest.xml"
        manifest = manifest_path.read_text(encoding="utf-8")
        if f'android:label="{expected_label}"' not in manifest:
            fail(f"diagnostic label drift: {module}")
        if f'android:icon="{expected_icon}"' not in manifest:
            fail(f"diagnostic icon missing: {module}")
        icon_name = expected_icon.rsplit("/", 1)[1]
        icon_path = ROOT / module / "src/main/res/drawable" / f"{icon_name}.xml"
        if not icon_path.is_file():
            fail(f"diagnostic icon file missing: {icon_path.relative_to(ROOT)}")
        icons.append(icon_path.read_bytes())
    if len(set(icons)) != len(icons):
        fail("diagnostic launchers must use distinct icons")

    sample_strings = (ROOT / "sample/src/main/res/values/strings.xml").read_text(
        encoding="utf-8"
    )
    if "[SAMPLE] Alpine OAuth" not in sample_strings:
        fail("OAuth sample launcher must keep the [SAMPLE] label")


def main() -> int:
    tokens = json.loads(TOKENS_PATH.read_text(encoding="utf-8"))
    if tokens.get("schema_version") != 1:
        fail("unsupported token schema")
    verify_tokens(tokens)
    verify_screenshots(tokens)
    verify_product_artifact_boundary()
    print("UI design contract: OK")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, TypeError, ValueError) as error:
        print(f"UI design contract: FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
