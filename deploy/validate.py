"""Check deployment configuration using synthetic values; never start the application."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

from release_env import checked_worker_values, WORKER_NAMES


DEPLOY = Path(__file__).resolve().parent
REQUIRED = {
    "BACKEND_IMAGE": "hanmaum-backend:validation-only",
    "POSTGRES_ADMIN_PASSWORD": "validation-only-admin-password",
    "DB_PASSWORD": "validation-only-app-password",
    "GOOGLE_CLIENT_ID": "validation.apps.googleusercontent.com",
    "GOOGLE_CLIENT_SECRET": "validation-only-google-secret",
    "KAKAO_CLIENT_ID": "validation-only-kakao-rest-key",
    "KAKAO_CLIENT_SECRET": "validation-only-kakao-secret",
    "COMMAND_DIGEST_KEY": "validation-only-digest-key-at-least-32-bytes",
    "INVITATION_ENCRYPTION_KEY": "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "AI_INTERNAL_API_KEY": "validation-only-internal-key-at-least-32-bytes",
}
WORKER_EXAMPLE = {
    "ANALYSIS_WORKER_ENABLED": "true",
    "ANALYSIS_WORKER_CONCURRENCY": "1",
    "ANALYSIS_WORKER_POLL_INTERVAL": "2s",
    "ANALYSIS_WORKER_LEASE_DURATION": "120s",
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(args, env=None):
    # Audited call sites pass argv lists: fixed docker/openssl commands, repository paths,
    # UUID names and synthetic settings. No shell parses these argument values.
    # shlex.quote/escape would corrupt argv; only explicit in-container sh -ec uses fixed code.
    if not isinstance(args, list) or not args or args[0] not in ("docker", "openssl"):
        raise ValueError("Validation accepts only docker/openssl argv lists")
    return subprocess.run(args, env=env, shell=False, capture_output=True, text=True, check=False)


def compose_args(filename):
    return [
        "docker", "compose", "--project-name", "hanmaum-deploy-validation", "--profile", "tools",
        "--env-file", str(DEPLOY / ".env.prod.example"),
        "-f", str(DEPLOY / filename), "config",
    ]


def validate_compose():
    env = os.environ.copy()
    # Real developer credentials and an ambient Compose project must not enter this check.
    for name in ("COMPOSE_FILE", "COMPOSE_PROJECT_NAME", "COMPOSE_PROFILES"):
        env.pop(name, None)
    env.update(REQUIRED)
    env.update(WORKER_EXAMPLE)
    result = run(compose_args("compose.prod.yml") + ["--format", "json"], env)
    require(result.returncode == 0, f"Production Compose validation failed: {result.stderr}")
    config = json.loads(result.stdout)
    services = config["services"]
    require(set(services) == {"postgres", "backend", "nginx", "certbot"}, "Unexpected service")
    for name, service in services.items():
        require(name == "nginx" or not service.get("ports"), f"{name} exposes a host port")
    ports = services["nginx"]["ports"]
    require(
        {(int(port["published"]), port["target"]) for port in ports} == {(80, 80), (443, 443)},
        "Only Nginx HTTP and HTTPS may be published",
    )
    require(config["networks"]["database"]["internal"], "Database network is not internal")
    require(set(services["postgres"]["networks"]) == {"database"}, "Database has external network")
    require("database" not in services["nginx"]["networks"], "Proxy can reach database directly")
    backend = services["backend"]
    settings = backend["environment"]
    require(settings["SPRING_PROFILES_ACTIVE"] == "prod,oauth", "Production OAuth profile missing")
    require(checked_worker_values(settings) == WORKER_EXAMPLE,
            "Enabled worker operating values were not forwarded")
    require(settings["AI_BASE_URL"] == "https://hanmaum-ai:8443", "AI must use internal HTTPS")
    require(settings["AI_VERIFY_ON_STARTUP"] == "true", "AI readiness must gate startup")
    require(settings["ANALYSIS_CONTRACT_VERSION"] == "v2", "New analyses must use v2")
    require(config["networks"]["ai-link"]["external"] and
            config["networks"]["ai-link"]["name"] == "hanmaum-ai-link", "AI network mismatch")
    require(set(backend["networks"]) == {"edge", "database", "ai-link"}, "BE networks mismatch")
    require(all("ai-link" not in service["networks"] for name, service in services.items()
                if name != "backend"), "Only BE may join the AI network")
    ca = [mount for mount in backend["volumes"] if mount["target"] == "/run/hanmaum-ai/ca.crt"]
    require(len(ca) == 1 and ca[0]["source"] == "/opt/hanmaum-ai/tls/ca.crt"
            and ca[0]["read_only"] and not ca[0]["bind"].get("create_host_path", False),
            "Only the existing public CA may be mounted read-only")
    require(backend["entrypoint"] == ["bash", "/opt/hanmaum/backend-entrypoint.sh"],
            "AI trust bootstrap is required")
    require(settings["DB_USERNAME"] != services["postgres"]["environment"]["POSTGRES_USER"],
            "Application must not use the database administrator")
    require(backend["pull_policy"] == "never", "Release must use the explicitly loaded image")
    require(backend["read_only"], "Backend filesystem should be read-only")
    require(backend["depends_on"]["postgres"]["condition"] == "service_healthy",
            "Backend must wait for the database")
    require(services["nginx"]["depends_on"]["backend"]["condition"] == "service_healthy",
            "Proxy must wait for the backend")
    allowed = json.loads(settings["SPRING_APPLICATION_JSON"])["app"]["security"]["allowed-origins"]
    require(allowed == ["https://hanmaum.site"], "Unexpected production CORS origin")
    for name in ("nginx", "certbot"):
        require(not services[name].get("environment"), f"{name} receives unnecessary credentials")

    # Both explicit shutdown and an older release with no opt-in must stay disabled.
    for worker_values in ({**WORKER_EXAMPLE, "ANALYSIS_WORKER_ENABLED": "false"}, {}):
        disabled_env = {name: value for name, value in env.items() if name not in WORKER_NAMES}
        disabled_env.update(worker_values)
        result = run(compose_args("compose.prod.yml") + ["--format", "json"], disabled_env)
        require(result.returncode == 0, "Disabled worker Compose validation failed")
        disabled = json.loads(result.stdout)["services"]["backend"]["environment"]
        require(checked_worker_values(disabled) == checked_worker_values(worker_values),
                "Disabled or missing worker settings did not remain disabled")

    # A forgotten OAuth key/password must fail before containers are created.
    for name in REQUIRED:
        missing_env = env.copy()
        missing_env.pop(name, None)
        result = run(compose_args("compose.prod.yml") + ["--quiet"], missing_env)
        require(result.returncode != 0 and name in result.stderr,
                f"Missing {name} was not rejected")
    result = run(compose_args("compose.bootstrap.yml") + ["--format", "json"], env)
    require(result.returncode == 0, f"Bootstrap Compose validation failed: {result.stderr}")
    bootstrap = json.loads(result.stdout)
    require(set(bootstrap["services"]) == {"nginx", "certbot"}, "Bootstrap starts application services")
    require({int(port["published"]) for port in bootstrap["services"]["nginx"]["ports"]} == {80},
            "Bootstrap should expose only HTTP")
    for name in ("acme-webroot", "letsencrypt"):
        require(config["volumes"][name]["name"] == bootstrap["volumes"][name]["name"],
                "Bootstrap and production certificate volumes differ")
    print("Compose: topology, required values, OAuth, CORS, AI isolation, worker on/off and bootstrap passed.")
    return services["nginx"]["image"]


def validate_nginx(image):
    # A short-lived self-signed certificate is used only for nginx -t, never for deployment.
    with tempfile.TemporaryDirectory(prefix="hanmaum-deploy-check-") as directory:
        cert_root = Path(directory)
        live = cert_root / "live" / "api.hanmaum.site"
        live.mkdir(parents=True)
        result = run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-keyout", str(live / "privkey.pem"), "-out", str(live / "fullchain.pem"),
            "-days", "1", "-subj", "/CN=api.hanmaum.site",
        ])
        require(result.returncode == 0, "Validation certificate generation failed")
        for name in ("http.conf", "https.conf"):
            result = run([
                "docker", "run", "--rm", "--network", "none", "--entrypoint", "nginx",
                "--mount", f"type=bind,src={DEPLOY / 'nginx' / name},dst=/etc/nginx/nginx.conf,readonly",
                "--mount", f"type=bind,src={cert_root},dst=/etc/letsencrypt,readonly",
                image, "-t", "-q",
            ])
            require(result.returncode == 0, f"Nginx {name} validation failed: {result.stderr}")
    print("Nginx: HTTP and HTTPS configuration syntax passed; no server was started.")


def validate_postgres():
    # Isolated, synthetic database only: no host ports, external network or persistent volume.
    name = f"hanmaum-db-validation-{uuid.uuid4().hex[:12]}"
    try:
        result = run([
            "docker", "run", "--detach", "--name", name, "--network", "none",
            "--memory", "512m", "--tmpfs", "/var/lib/postgresql/data",
            "--env", "POSTGRES_USER=hanmaum_admin", "--env", "POSTGRES_DB=hanmaum",
            "--env", f"POSTGRES_PASSWORD={REQUIRED['POSTGRES_ADMIN_PASSWORD']}",
            "--env", f"HANMAUM_APP_PASSWORD={REQUIRED['DB_PASSWORD']}",
            "--env", "POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256 --auth-local=trust",
            "--mount", f"type=bind,src={DEPLOY / 'postgres' / '10-app-role.sql'},"
                       "dst=/docker-entrypoint-initdb.d/10-app-role.sql,readonly",
            "postgres:17.11",
        ])
        require(result.returncode == 0, f"Isolated PostgreSQL start failed: {result.stderr}")
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            ready = run(["docker", "exec", name, "pg_isready", "-h", "127.0.0.1",
                         "-U", "hanmaum_admin", "-d", "hanmaum"])
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Isolated PostgreSQL initialization timed out")
        psql = [
            "docker", "exec", "--env", f"PGPASSWORD={REQUIRED['DB_PASSWORD']}", name,
            "psql", "-X", "-h", "127.0.0.1", "-U", "hanmaum_app", "-d", "hanmaum",
            "-v", "ON_ERROR_STOP=1", "-qAt",
        ]
        result = run(psql + ["-c", "SELECT current_user, rolsuper, rolcreatedb, rolcreaterole "
                            "FROM pg_roles WHERE rolname = current_user"])
        require(result.returncode == 0 and result.stdout.strip() == "hanmaum_app|f|f|f",
                "Application login or non-administrator role validation failed")
        result = run([
            "docker", "exec", "--env", "PGPASSWORD=deliberately-wrong", name,
            "psql", "-X", "-h", "127.0.0.1", "-U", "hanmaum_app", "-d", "hanmaum",
            "-c", "SELECT 1",
        ])
        require(result.returncode != 0, "Wrong database password was accepted")
        result = run(psql + ["-c", "CREATE TABLE deployment_probe (id integer PRIMARY KEY); "
                            "INSERT INTO deployment_probe VALUES (1);"])
        require(result.returncode == 0, f"Application schema permissions failed: {result.stderr}")
        result = run([
            "docker", "exec", name, "sh", "-ec",
            "pg_dump -U hanmaum_admin -d hanmaum -Fc --no-owner --no-acl -f /tmp/check.dump\n"
            "createdb -U hanmaum_admin -O hanmaum_app hanmaum_restore_check\n"
            "pg_restore -U hanmaum_app -d hanmaum_restore_check "
            "--no-owner --no-acl --exit-on-error /tmp/check.dump\n"
            "psql -X -U hanmaum_app -d hanmaum_restore_check -qAt "
            "-c 'SELECT count(*) FROM deployment_probe'",
        ])
        require(result.returncode == 0 and result.stdout.strip() == "1",
                f"Synthetic backup/restore failed: {result.stderr}")
    finally:
        # This uniquely named container was allocated by this invocation; no volumes are removed.
        run(["docker", "rm", "--force", name])
    print("PostgreSQL: initialization, password, app privileges and synthetic backup/restore passed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nginx", action="store_true", help="Also run nginx -t in disposable containers")
    parser.add_argument("--postgres", action="store_true", help="Check initialization in a disposable database")
    args = parser.parse_args()
    nginx_image = validate_compose()
    if args.nginx:
        validate_nginx(nginx_image)
    if args.postgres:
        validate_postgres()
