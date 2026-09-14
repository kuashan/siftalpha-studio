# W1A alpha7 launcher icon repair

Date: 2026-09-14

## Finding

The alpha6 launcher entry points referenced a build-generated `siftalpha_app_icon_v2.webp`.
The generated payload was not a reliable decodable image, and the checked-in
`siftalpha_app_icon.png` was a different artwork from the approved terminal-prompt image.
This could leave the installed application without the intended launcher artwork.

## Repair

- Added the approved uploaded artwork as `drawable-nodpi/siftalpha_launcher_art.jpg`.
- Pointed the manifest launcher resources, legacy launcher resources, and API 26+ resource
  overrides directly at that one JPEG.
- Removed the build-time icon decoding task so resource availability no longer depends on a
  generated source-tree file.
- Added `tools/validate_launcher_icon.py` and ran it from Localization and Trusted Signed Debug
  APK workflows.
- Bumped the candidate to `0.8.0-alpha7` / `versionCode 83` for an in-place update over trusted
  alpha6 builds.

This repair changes only launcher artwork/resource wiring and build validation. Runtime,
Termux/PRoot execution, process ownership, SAF handling, Web endpoint validation, secret
transport, and cleanup guards are unchanged.
