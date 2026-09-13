#!/usr/bin/env python3
"""
SiftAlpha OCI A1 Catcher v1

Portable Oracle Cloud Ampere A1 capacity catcher designed to run inside
SiftAlpha Studio's Python Runtime (Termux + Ubuntu PRoot).

No OCI private key is stored in this project.
"""

import os
import random
import signal
import sys
import time
from pathlib import Path

try:
    import oci
except Exception as exc:
    print(f"SIFTALPHA_OCI_CATCHER=IMPORT_ERROR type={type(exc).__name__} message={exc}", flush=True)
    raise

REGION = os.getenv("OCI_REGION", "ap-kulai-2").strip()
AVAILABILITY_DOMAIN = os.getenv(
    "OCI_AVAILABILITY_DOMAIN",
    "Teby:AP-KULAI-2-AD-1",
).strip()
SHAPE = os.getenv("OCI_SHAPE", "VM.Standard.A1.Flex").strip()

TARGET_OCPUS = float(os.getenv("OCI_TARGET_OCPUS", "2"))
TARGET_MEMORY_GB = float(os.getenv("OCI_TARGET_MEMORY_GB", "12"))

INSTANCE_DISPLAY_NAME = os.getenv(
    "OCI_INSTANCE_DISPLAY_NAME",
    "siftalpha-server",
).strip()

VCN_DISPLAY_NAME = os.getenv(
    "OCI_VCN_DISPLAY_NAME",
    "huntalpha-staging-vcn",
).strip()
SUBNET_CIDR = os.getenv(
    "OCI_SUBNET_CIDR",
    "10.0.0.0/24",
).strip()

UBUNTU_OS = os.getenv("OCI_OPERATING_SYSTEM", "Canonical Ubuntu").strip()
UBUNTU_VERSION = os.getenv("OCI_UBUNTU_VERSION", "26.04").strip()

NORMAL_RETRY_SEC = max(15, int(os.getenv("OCI_RETRY_INTERVAL_SEC", "60")))
RATE_LIMIT_MIN_SEC = max(60, int(os.getenv("OCI_RATE_LIMIT_MIN_SEC", "120")))
RATE_LIMIT_MAX_SEC = max(
    RATE_LIMIT_MIN_SEC,
    int(os.getenv("OCI_RATE_LIMIT_MAX_SEC", "300")),
)

FREE_MAX_OCPUS = float(os.getenv("OCI_FREE_MAX_OCPUS", "4"))
FREE_MAX_MEMORY_GB = float(os.getenv("OCI_FREE_MAX_MEMORY_GB", "24"))

PROFILE = os.getenv("OCI_PROFILE", "DEFAULT").strip() or "DEFAULT"

running = True


def log(message: str) -> None:
    print(message, flush=True)


def stop_handler(signum, _frame):
    global running
    running = False
    log(f"SIFTALPHA_OCI_CATCHER=STOP_REQUESTED signal={signum}")


signal.signal(signal.SIGTERM, stop_handler)
signal.signal(signal.SIGINT, stop_handler)


def sleep_interruptibly(seconds: int) -> None:
    end = time.monotonic() + seconds
    while running and time.monotonic() < end:
        time.sleep(min(1.0, end - time.monotonic()))


def first_existing(paths):
    for p in paths:
        if not p:
            continue
        path = Path(os.path.expanduser(p))
        if path.is_file():
            return path
    return None


def find_config_file() -> Path:
    explicit = os.getenv("OCI_CONFIG_FILE", "").strip()
    candidates = [
        explicit,
        "/data/data/com.termux/files/home/.oci/config",
        "~/.oci/config",
    ]
    found = first_existing(candidates)
    if found is None:
        searched = " | ".join(str(Path(os.path.expanduser(p))) for p in candidates if p)
        raise FileNotFoundError(
            "OCI config not found. Searched: " + searched
        )
    return found


def load_oci_config():
    config_file = find_config_file()
    log(f"OCI_CONFIG_FILE={config_file}")
    config = oci.config.from_file(
        file_location=str(config_file),
        profile_name=PROFILE,
    )
    config["region"] = REGION
    oci.config.validate_config(config)
    key_file = Path(os.path.expanduser(str(config.get("key_file", ""))))
    if not key_file.is_file():
        raise FileNotFoundError(
            f"OCI API private key referenced by config is not readable: {key_file}"
        )
    log("OCI_CONFIG=PASS")
    log("OCI_API_PRIVATE_KEY=FOUND_NOT_LOGGED")
    return config


def find_ssh_public_key() -> str:
    literal = os.getenv("OCI_SSH_PUBLIC_KEY", "").strip()
    if literal:
        if not literal.startswith("ssh-"):
            raise ValueError("OCI_SSH_PUBLIC_KEY does not look like an SSH public key")
        log("SSH_PUBLIC_KEY=FROM_ENV")
        return literal

    explicit_file = os.getenv("OCI_SSH_PUBLIC_KEY_FILE", "").strip()
    candidates = [
        explicit_file,
        "/data/data/com.termux/files/home/.ssh/id_ed25519.pub",
        "~/.ssh/id_ed25519.pub",
    ]
    found = first_existing(candidates)
    if found is None:
        searched = " | ".join(str(Path(os.path.expanduser(p))) for p in candidates if p)
        raise FileNotFoundError(
            "SSH public key not found. Searched: " + searched
        )

    value = found.read_text(encoding="utf-8").strip()
    if not value.startswith("ssh-"):
        raise ValueError(f"SSH public key file is invalid: {found}")
    log(f"SSH_PUBLIC_KEY_FILE={found}")
    log("SSH_PUBLIC_KEY=PASS")
    return value


def paginate_list(call, **kwargs):
    return oci.pagination.list_call_get_all_results(call, **kwargs).data


def discover_vcn_and_subnet(network, compartment_id):
    vcns = paginate_list(
        network.list_vcns,
        compartment_id=compartment_id,
        display_name=VCN_DISPLAY_NAME,
    )
    active = [v for v in vcns if str(v.lifecycle_state).upper() == "AVAILABLE"]
    if len(active) != 1:
        raise RuntimeError(
            f"Expected exactly one AVAILABLE VCN named {VCN_DISPLAY_NAME!r}; found {len(active)}"
        )
    vcn = active[0]
    log(f"VCN=PASS name={vcn.display_name}")

    subnets = paginate_list(
        network.list_subnets,
        compartment_id=compartment_id,
        vcn_id=vcn.id,
    )
    matches = [
        s for s in subnets
        if str(s.lifecycle_state).upper() == "AVAILABLE"
        and str(s.cidr_block) == SUBNET_CIDR
    ]
    if len(matches) != 1:
        raise RuntimeError(
            f"Expected exactly one AVAILABLE subnet with CIDR {SUBNET_CIDR}; found {len(matches)}"
        )
    subnet = matches[0]
    log(f"SUBNET=PASS cidr={subnet.cidr_block}")
    return vcn.id, subnet.id


def discover_image(compute, compartment_id):
    explicit = os.getenv("OCI_IMAGE_OCID", "").strip()
    if explicit:
        image = compute.get_image(explicit).data
        log(f"IMAGE=PASS source=OCI_IMAGE_OCID os={image.operating_system} version={image.operating_system_version}")
        return explicit

    images = compute.list_images(
        compartment_id=compartment_id,
        operating_system=UBUNTU_OS,
        operating_system_version=UBUNTU_VERSION,
        shape=SHAPE,
        sort_by="TIMECREATED",
        sort_order="DESC",
        limit=50,
    ).data

    available = [img for img in images if str(img.lifecycle_state).upper() == "AVAILABLE"]
    if not available:
        raise RuntimeError(
            f"No AVAILABLE {UBUNTU_OS} {UBUNTU_VERSION} image compatible with {SHAPE}"
        )

    image = available[0]
    log(
        "IMAGE=PASS "
        f"os={image.operating_system} "
        f"version={image.operating_system_version} "
        f"name={image.display_name}"
    )
    return image.id


def list_active_a1_instances(compute, compartment_id):
    instances = paginate_list(
        compute.list_instances,
        compartment_id=compartment_id,
    )
    result = []
    for inst in instances:
        state = str(inst.lifecycle_state).upper()
        if inst.shape == SHAPE and state not in {"TERMINATED", "TERMINATING"}:
            result.append(inst)
    return result


def usage_of(instances):
    ocpus = 0.0
    memory = 0.0
    for inst in instances:
        shape_config = getattr(inst, "shape_config", None)
        if shape_config is not None:
            ocpus += float(getattr(shape_config, "ocpus", 0) or 0)
            memory += float(getattr(shape_config, "memory_in_gbs", 0) or 0)
    return ocpus, memory


def duplicate_or_guardrail(compute, compartment_id):
    active = list_active_a1_instances(compute, compartment_id)

    same_name = [
        inst for inst in active
        if str(inst.display_name or "").strip() == INSTANCE_DISPLAY_NAME
    ]
    if same_name:
        inst = same_name[0]
        log(
            "INSTANCE_ALREADY_EXISTS=TRUE "
            f"name={INSTANCE_DISPLAY_NAME} "
            f"state={inst.lifecycle_state} "
            f"ocid={inst.id}"
        )
        return True

    used_ocpus, used_memory = usage_of(active)
    log(
        "A1_USAGE "
        f"existing_instances={len(active)} "
        f"existing_ocpus={used_ocpus:g} "
        f"existing_memory_gb={used_memory:g}"
    )

    if used_ocpus + TARGET_OCPUS > FREE_MAX_OCPUS:
        raise RuntimeError(
            f"Safety guard: requested A1 OCPUs would exceed configured free cap "
            f"({used_ocpus:g}+{TARGET_OCPUS:g}>{FREE_MAX_OCPUS:g})"
        )
    if used_memory + TARGET_MEMORY_GB > FREE_MAX_MEMORY_GB:
        raise RuntimeError(
            f"Safety guard: requested A1 memory would exceed configured free cap "
            f"({used_memory:g}+{TARGET_MEMORY_GB:g}>{FREE_MAX_MEMORY_GB:g})"
        )

    return False


def is_out_of_capacity(exc) -> bool:
    text = f"{getattr(exc, 'code', '')} {getattr(exc, 'message', '')} {exc}".lower()
    return (
        "out of host capacity" in text
        or "out of capacity" in text
        or ("internalerror" in text and "capacity" in text)
    )


def is_rate_limit(exc) -> bool:
    status = getattr(exc, "status", None)
    code = str(getattr(exc, "code", "")).lower()
    text = f"{code} {getattr(exc, 'message', '')}".lower()
    return status == 429 or "toomanyrequests" in text or "too many requests" in text


def fatal_service_error(exc) -> bool:
    status = getattr(exc, "status", None)
    return status in {400, 401, 403, 404} and not is_rate_limit(exc)


def launch_details(compartment_id, subnet_id, image_id, ssh_public_key):
    return oci.core.models.LaunchInstanceDetails(
        availability_domain=AVAILABILITY_DOMAIN,
        compartment_id=compartment_id,
        display_name=INSTANCE_DISPLAY_NAME,
        shape=SHAPE,
        shape_config=oci.core.models.LaunchInstanceShapeConfigDetails(
            ocpus=TARGET_OCPUS,
            memory_in_gbs=TARGET_MEMORY_GB,
        ),
        source_details=oci.core.models.InstanceSourceViaImageDetails(
            image_id=image_id,
        ),
        create_vnic_details=oci.core.models.CreateVnicDetails(
            subnet_id=subnet_id,
            assign_public_ip=True,
        ),
        metadata={
            "ssh_authorized_keys": ssh_public_key,
        },
    )


def main():
    log("SIFTALPHA_OCI_A1_CATCHER_V1=START")
    log(f"OCI_REGION={REGION}")
    log(f"OCI_AD={AVAILABILITY_DOMAIN}")
    log(f"OCI_SHAPE={SHAPE}")
    log(f"TARGET_OCPUS={TARGET_OCPUS:g}")
    log(f"TARGET_MEMORY_GB={TARGET_MEMORY_GB:g}")
    log(f"INSTANCE_DISPLAY_NAME={INSTANCE_DISPLAY_NAME}")
    log(f"VCN_DISPLAY_NAME={VCN_DISPLAY_NAME}")
    log(f"SUBNET_CIDR={SUBNET_CIDR}")
    log(f"NORMAL_RETRY_SEC={NORMAL_RETRY_SEC}")
    log(f"RATE_LIMIT_BACKOFF_SEC={RATE_LIMIT_MIN_SEC}-{RATE_LIMIT_MAX_SEC}")
    log("OCI_PRIVATE_KEY_LOGGED=FALSE")
    log("RETRY_UNTIL_SUCCESS_OR_STOP=TRUE")

    config = load_oci_config()
    compartment_id = config["tenancy"]
    log("COMPARTMENT=TENANCY_ROOT")

    ssh_public_key = find_ssh_public_key()

    compute = oci.core.ComputeClient(config)
    network = oci.core.VirtualNetworkClient(config)

    _, subnet_id = discover_vcn_and_subnet(network, compartment_id)
    image_id = discover_image(compute, compartment_id)

    attempt = 0

    while running:
        attempt += 1
        log(f"CATCHER_HEARTBEAT attempt={attempt}")

        try:
            if duplicate_or_guardrail(compute, compartment_id):
                log("SIFTALPHA_OCI_CATCHER=ALREADY_EXISTS")
                return 0

            details = launch_details(
                compartment_id=compartment_id,
                subnet_id=subnet_id,
                image_id=image_id,
                ssh_public_key=ssh_public_key,
            )

            log(f"LAUNCH_ATTEMPT={attempt} BEGIN")
            response = compute.launch_instance(details)
            instance = response.data

            log("INSTANCE_CREATED=PASS")
            log(f"INSTANCE_OCID={instance.id}")
            log(f"INSTANCE_STATE={instance.lifecycle_state}")
            log(f"INSTANCE_NAME={instance.display_name}")
            log("SIFTALPHA_OCI_CATCHER=SUCCESS")
            return 0

        except oci.exceptions.ServiceError as exc:
            if is_rate_limit(exc):
                delay = random.randint(RATE_LIMIT_MIN_SEC, RATE_LIMIT_MAX_SEC)
                log(
                    "RATE_LIMIT=BACKOFF "
                    f"attempt={attempt} "
                    f"status={getattr(exc, 'status', None)} "
                    f"code={getattr(exc, 'code', '')} "
                    f"seconds={delay}"
                )
                sleep_interruptibly(delay)
                continue

            if is_out_of_capacity(exc):
                log(
                    "CAPACITY_UNAVAILABLE=RETRY "
                    f"attempt={attempt} "
                    f"status={getattr(exc, 'status', None)} "
                    f"code={getattr(exc, 'code', '')} "
                    f"seconds={NORMAL_RETRY_SEC}"
                )
                sleep_interruptibly(NORMAL_RETRY_SEC)
                continue

            if fatal_service_error(exc):
                log(
                    "OCI_SERVICE_ERROR=FATAL "
                    f"status={getattr(exc, 'status', None)} "
                    f"code={getattr(exc, 'code', '')} "
                    f"message={str(getattr(exc, 'message', exc))[:300]}"
                )
                return 2

            log(
                "OCI_SERVICE_ERROR=RETRY "
                f"attempt={attempt} "
                f"status={getattr(exc, 'status', None)} "
                f"code={getattr(exc, 'code', '')} "
                f"seconds={NORMAL_RETRY_SEC}"
            )
            sleep_interruptibly(NORMAL_RETRY_SEC)

        except (TimeoutError, ConnectionError, OSError) as exc:
            log(
                "NETWORK_ERROR=RETRY "
                f"attempt={attempt} "
                f"type={type(exc).__name__} "
                f"seconds={NORMAL_RETRY_SEC} "
                f"message={str(exc)[:220]}"
            )
            sleep_interruptibly(NORMAL_RETRY_SEC)

        except Exception as exc:
            log(
                "CATCHER_ERROR=FATAL "
                f"type={type(exc).__name__} "
                f"message={str(exc)[:500]}"
            )
            return 3

    log("SIFTALPHA_OCI_CATCHER=STOPPED_BY_USER")
    return 0


if __name__ == "__main__":
    sys.exit(main())
