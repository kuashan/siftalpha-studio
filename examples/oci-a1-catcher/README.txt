SiftAlpha OCI A1 Catcher v1
================================

Purpose
-------
Run the Oracle Cloud Ampere A1 capacity catcher directly inside SiftAlpha Studio.
It can remain RUNNING for a long time, so it is also useful as a practical Runtime
long-duration/keepalive test.

Target defaults
---------------
Region: ap-kulai-2
Availability Domain: Teby:AP-KULAI-2-AD-1
Shape: VM.Standard.A1.Flex
Target: 2 OCPU / 12 GB RAM
VCN: huntalpha-staging-vcn
Subnet CIDR: 10.0.0.0/24
OS: Canonical Ubuntu 26.04
Retry interval: 60 seconds
429 backoff: random 120-300 seconds

Security
--------
This project contains NO OCI user OCID, tenancy OCID, fingerprint, API private key,
or other OCI credentials.

The program searches for the OCI config in this order:
1. OCI_CONFIG_FILE environment variable
2. /data/data/com.termux/files/home/.oci/config
3. ~/.oci/config

The API private key path is read from that OCI config and its contents are never
printed.

SSH public key search order:
1. OCI_SSH_PUBLIC_KEY environment variable
2. OCI_SSH_PUBLIC_KEY_FILE environment variable
3. /data/data/com.termux/files/home/.ssh/id_ed25519.pub
4. ~/.ssh/id_ed25519.pub

How to use in SiftAlpha Studio
------------------------------
1. Package these four files as a ZIP or use the distributed import ZIP.
2. Import ZIP into SiftAlpha Studio as a project.
3. Tap "准备环境". This installs the Python OCI SDK into the project's isolated venv.
4. Tap "运行".
5. Tap "日志" or "状态" whenever desired.

Normal waiting output
---------------------
SIFTALPHA_OCI_A1_CATCHER_V1=START
OCI_CONFIG=PASS
SSH_PUBLIC_KEY=PASS
VCN=PASS ...
SUBNET=PASS ...
IMAGE=PASS ...
CATCHER_HEARTBEAT attempt=1
CAPACITY_UNAVAILABLE=RETRY ...
CATCHER_HEARTBEAT attempt=2
...

Successful capacity capture
---------------------------
INSTANCE_CREATED=PASS
INSTANCE_OCID=...
INSTANCE_STATE=PROVISIONING
SIFTALPHA_OCI_CATCHER=SUCCESS

The catcher then exits normally because the instance has already been accepted by OCI.

Safety protections
------------------
- Detects an existing non-terminated VM.Standard.A1.Flex instance with the same name.
- Sums existing active A1 OCPU/RAM and blocks launch if the configured guardrail would be exceeded.
- Does not specify a fault domain.
- Does not log OCI API private key contents.
- Does not store credentials inside the project.
- Uses a 60-second normal capacity retry.
- Uses randomized 120-300 second backoff for HTTP 429 rate limiting.

Important
---------
If the log says OCI config or API private key cannot be found, do not paste the
private key into chat. Migrate the existing Termux ~/.oci configuration into the
SiftAlpha Runtime securely without adding credentials to this repository.
