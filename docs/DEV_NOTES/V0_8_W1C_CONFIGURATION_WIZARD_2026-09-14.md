# v0.8 W1C Python configuration detection and wizard

Date: 2026-09-14

Branch: `w1c-config-wizard`

## Scope

W1C extends Runtime Center's existing Configuration action so a Python project can expose useful
configuration inputs without requiring a project author to add metadata first. Detection is
read-only and bounded; Studio never executes a project during inspection and never rewrites its
source files.

## Detection rules

- `.project.json.requiredEnv` remains the authoritative explicit contract.
- `os.environ["NAME"]` and `environ["NAME"]` are inferred as required because Python raises when
  the variable is absent.
- `os.getenv("NAME")`, `os.environ.get("NAME")`, and `environ.get("NAME")` are surfaced as
  optional candidates. Defaults are not treated as proof that an input is required.
- `.env.example` assignments are surfaced as optional candidates, including non-secret values such
  as ports or regions.
- Common process variables such as `PATH`, `HOME`, and `TERM` are ignored. Names and values are
  handled separately: only names enter the inspection result, while values remain in the protected
  configuration store or the project runtime environment.
- Inspection reads at most eight eligible Python files and skips common generated/dependency
  directories. Runtime errors can still add an explicitly reported missing environment name as a
  configuration hint.

## Wizard behavior

- Tapping Configuration opens a sequential wizard when any detected item is not configured.
- Required items cannot be skipped; optional candidates can be skipped.
- Save and continue advances to the next pending item. After the final item, Runtime Center
  refreshes the project card.
- Once all detected items are configured, the existing list editor remains available for update or
  clearing Studio-managed values.
- Launch preflight uses the same one-item-at-a-time wizard for missing required values, then
  re-checks readiness before starting the process.

## Secret boundary

Entered values continue to use the existing Android Keystore-backed `ProjectSecretStore` and the
one-shot runtime payload. They are not written to project source, `.env`, logs, or GitHub. This
slice does not attempt to infer arbitrary file-based credentials (for example, the contents of
`~/.oci/config`); those remain an environment/runtime setup concern until a dedicated file-picker
contract is designed.

## Validation

The parser has unit coverage for required direct access, optional getters, duplicate handling,
common-variable filtering, exact variable spelling, and secret classification. XML and all five
localized resource sets pass the repository localization validators. Cloud Android/Gradle CI and
real-device wizard acceptance are required before merging.
