# Public Repository Bootstrap

Date: 2026-09-13

This public repository was bootstrapped from the verified SiftAlpha Studio alpha15 product snapshot. It starts a new Git history and intentionally does not carry private development history, old refs/tags, pull requests, issues, Actions runs, or signing material.

The public CI surface is fork-safe: pull requests use read-only permissions, require no repository secrets, and build with ordinary runner-generated Android debug signing. CI APKs are for testing and may not upgrade over privately signed builds.

Stable signing migration, signing certificate lineage, release credentials, and secret-backed release workflows are outside this bootstrap. No stable private key is created or stored by this repository.

The maintained checks are localization validation, exact-head/unit validation, and Android debug APK assembly. See the repository root README, SECURITY.md, and CONTRIBUTING.md for the public workflow contract.
