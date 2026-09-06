"""Run deployment scripts against temporary data and a fake Docker CLI.

python -m unittest discover -s deploy/linux/tests -v
Requires Bash and tar; never connects to Docker or a production server.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[3]
BASH = os.environ.get("BASH_FOR_TESTS") or (
    "C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else shutil.which("bash")
)


def shell_path(path):
    value = Path(path).resolve().as_posix()
    return f"/{value[0].lower()}{value[2:]}" if os.name == "nt" else value


FAKE_DOCKER = r'''#!/bin/bash
set -eu
printf '%s\n' "$*" >> "$MOCK_ROOT/docker.log"
printf '%s\n' "${TRAINING_API_KEY:-}" > "$MOCK_ROOT/api-key"
case "$1" in
  info) exit 0 ;;
  compose)
    if [[ "$2" == version ]]; then echo 2.30.0; exit 0; fi
    case " $* " in
      *' build '*) [[ "${FAIL_BUILD:-0}" != 1 ]] ;;
      *' up '*) echo true > "$MOCK_ROOT/running"; [[ "${FAIL_UP:-0}" != 1 ]] ;;
      *) exit 0 ;;
    esac ;;
  image)
    [[ "$*" == *training-center-base:17* || "$*" == *training-center:existing* ]] ;;
  inspect)
    case "$3" in
      *State.Running*) cat "$MOCK_ROOT/running" ;;
      *Mounts*) printf '%s\n' "$TRAINING_DATA_DIR/training-runtime" ;;
      *com.docker.compose.project*) echo deploy ;;
      *Image*) echo sha256:previous-image ;;
      *) exit 1 ;;
    esac ;;
  stop) echo false > "$MOCK_ROOT/running" ;;
  start) echo true > "$MOCK_ROOT/running" ;;
  build) [[ "${FAIL_BUILD:-0}" != 1 ]] ;;
  tag|run) exit 0 ;;
  *) echo "Unexpected fake Docker command: $*" >&2; exit 1 ;;
esac
'''


@unittest.skipUnless(BASH, "Bash is required")
class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="training-deployment-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "training-center"
        shutil.copytree(REPO / "deploy", self.repo / "deploy")
        for name in ("Dockerfile", ".dockerignore", "Dockerfile.dockerignore"):
            if (REPO / name).exists():
                shutil.copy(REPO / name, self.repo / name)
        catalog = self.root / "output/coding-ai-exam/catalog"
        catalog.mkdir(parents=True)
        (catalog / "questions.json").write_text("[]", encoding="utf-8")
        (self.root / "output/interview").mkdir()
        self.data = self.root / "data with spaces"
        (self.data / "training-runtime/database").mkdir(parents=True)
        (self.data / "training-runtime/database/training.db").write_bytes(b"test-data")
        self.backups = self.root / "backups with spaces"
        self.backups.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.write_executable("docker", FAKE_DOCKER)
        self.write_executable("git", '#!/bin/bash\n[[ "$*" == *rev-parse* ]] && echo abc1234\nexit 0\n')
        self.write_executable("chown", "#!/bin/bash\nexit 0\n")
        self.write_executable("id", "#!/bin/bash\necho 1000\n")
        self.write_executable("curl", "#!/bin/bash\necho '{\"status\":\"UP\"}'\n")
        (self.root / "running").write_text("true\n", encoding="utf-8")
        self.env = os.environ.copy()
        for key in list(self.env):
            if key.startswith("TRAINING_") or key in ("BACKUP_DIR", "KEEP", "COMPOSE_PROJECT_NAME"):
                del self.env[key]
        self.env.update({
            "MOCK_ROOT": shell_path(self.root),
            "TRAINING_DATA_DIR": shell_path(self.data),
            "BACKUP_DIR": shell_path(self.backups),
            "MOCK_BIN": shell_path(self.bin),
            "MSYS_NO_PATHCONV": "1",
        })

    def write_executable(self, name, text):
        path = self.bin / name
        path.write_text(text, encoding="utf-8", newline="\n")
        path.chmod(0o755)

    def run_script(self, name, *args, extra_env=None):
        env = self.env | (extra_env or {})
        return subprocess.run(
            [BASH, "-c", 'export PATH="$MOCK_BIN:$PATH"; bash "$@"', "test",
             shell_path(self.repo / "deploy/linux" / name), *args],
            cwd=self.root, env=env, capture_output=True, text=True, encoding="utf-8", timeout=20,
        )

    def docker_log(self):
        path = self.root / "docker.log"
        return path.read_text(encoding="utf-8") if path.exists() else ""

    def test_daemon_template_is_valid_json(self):
        config = json.loads((REPO / "deploy/linux/docker-daemon.json").read_text(encoding="utf-8"))
        self.assertEqual(config["log-opts"]["max-size"], "10m")

    def test_backup_stops_writer_and_restarts_after_archive(self):
        self.write_executable("tar", '#!/bin/bash\n[[ "$(cat "$MOCK_ROOT/running")" == false ]] || exit 97\nexec /usr/bin/tar "$@"\n')
        result = self.run_script("backup.sh")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        log = self.docker_log()
        self.assertIn("stop", log)
        self.assertIn("start", log)
        self.assertLess(log.index("stop"), log.index("start"))
        self.assertEqual((self.root / "running").read_text().strip(), "true")
        self.assertEqual(len(list(self.backups.glob("training-data-*.tar.gz"))), 1)

    def test_backup_failure_restarts_writer_without_publishing_partial_archive(self):
        self.write_executable("tar", "#!/bin/bash\nexit 2\n")
        result = self.run_script("backup.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("start", self.docker_log())
        self.assertEqual(list(self.backups.glob("*.tar.gz")), [])
        self.assertEqual((self.root / "running").read_text().strip(), "true")

    def test_backup_does_not_start_a_previously_stopped_service(self):
        (self.root / "running").write_text("false\n")
        result = self.run_script("backup.sh")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("start training-center", self.docker_log())

    def test_invalid_retention_is_rejected_before_deleting_backups(self):
        old = self.backups / "training-data-old.tar.gz"
        old.write_bytes(b"keep")
        result = self.run_script("backup.sh", extra_env={"KEEP": "0"})
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(old.exists())

    def test_backup_rejects_a_destination_inside_the_data_directory(self):
        result = self.run_script("backup.sh", extra_env={"BACKUP_DIR": shell_path(self.data / "backups")})
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("stop", self.docker_log())

    def test_deploy_build_failure_keeps_existing_service_running(self):
        result = self.run_script("deploy.sh", "--no-base", extra_env={"FAIL_BUILD": "1"})
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("stop", self.docker_log())
        self.assertNotIn(" up ", self.docker_log())
        self.assertEqual((self.root / "running").read_text().strip(), "true")

    def test_deploy_preserves_parent_ignore_file_and_records_release(self):
        parent_ignore = self.root / ".dockerignore"
        parent_ignore.write_text("keep-parent-policy\n")
        result = self.run_script("deploy.sh", "--no-base")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(parent_ignore.read_text(), "keep-parent-policy\n")
        log = self.docker_log()
        self.assertIn("--wait", log)
        self.assertIn("--no-build", log)
        self.assertIn("stop", log)
        self.assertTrue((self.data / "releases.log").exists())
        self.assertEqual(len(list(self.backups.glob("training-data-*.tar.gz"))), 1)

    def test_env_file_is_loaded_without_executing_shell_code(self):
        env_file = self.repo / "deploy/linux/.env"
        env_file.write_text('TRAINING_API_KEY=$(touch should-not-exist)\nTRAINING_PORT=18080\n', encoding="utf-8")
        result = self.run_script("deploy.sh", "--image", "training-center:existing")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse((self.root / "should-not-exist").exists())
        self.assertEqual((self.root / "api-key").read_text().strip(), "$(touch should-not-exist)")
        self.assertIn("18080", result.stdout)

    def test_failed_start_retains_snapshot_and_records_previous_image(self):
        result = self.run_script("deploy.sh", "--no-base", extra_env={"FAIL_UP": "1"})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(list(self.backups.glob("training-data-*.tar.gz"))), 1)
        record = (self.data / "releases.log").read_text()
        self.assertIn("\tfailed\t", record)
        self.assertIn("training-center:rollback-", record)

    def test_backup_archive_contains_data_and_excludes_operation_lock(self):
        import tarfile
        result = self.run_script("backup.sh")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        with tarfile.open(next(self.backups.glob("*.tar.gz"))) as archive:
            names = archive.getnames()
            self.assertFalse(any(".deploy.lock" in name for name in names))
            member = archive.extractfile(f"{self.data.name}/training-runtime/database/training.db")
            self.assertEqual(member.read(), b"test-data")

    def test_existing_operation_lock_rejects_a_second_backup(self):
        (self.data / ".deploy.lock").mkdir()
        result = self.run_script("backup.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("stop", self.docker_log())
        self.assertTrue((self.data / ".deploy.lock").exists())


@unittest.skipUnless(shutil.which("docker"), "Docker CLI is required (engine is not needed)")
class ComposeConfigTest(unittest.TestCase):
    def test_production_overrides_preserve_auth_ports_storage_and_shutdown(self):
        env = os.environ.copy()
        env.update({
            "TRAINING_IMAGE": "training-center:release-test",
            "TRAINING_PORT": "18080",
            "TRAINING_BIND_ADDRESS": "127.0.0.1",
            "TRAINING_DATA_DIR": "/srv/training-data",
            "TRAINING_API_KEY": "config-test-key-with-#-characters",
            "JAVA_OPTS": "-Xmx384m",
            "TRAINING_MEMORY_LIMIT": "3G",
        })
        with tempfile.TemporaryDirectory() as env_directory:
            empty_env = Path(env_directory) / "empty.env"
            empty_env.touch()
            output = subprocess.run(
                ["docker", "compose", "--env-file", str(empty_env),
                 "-f", str(REPO / "deploy/docker-compose.yml"),
                 "-f", str(REPO / "deploy/linux/docker-compose.prod.yml"),
                 "config", "--format", "json"],
                env=env, capture_output=True, text=True, check=True,
            )
        service = json.loads(output.stdout)["services"]["training-center"]
        self.assertEqual(service["image"], "training-center:release-test")
        self.assertEqual(service["ports"][0]["published"], "18080")
        self.assertEqual(service["ports"][0]["host_ip"], "127.0.0.1")
        self.assertEqual(service["environment"]["TRAINING_API_KEY"], env["TRAINING_API_KEY"])
        self.assertEqual(service["environment"]["SERVER_SHUTDOWN"], "graceful")
        self.assertEqual(service["environment"].get("MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED"), "true")
        self.assertIn("/actuator/health/readiness", " ".join(service["healthcheck"]["test"]))
        self.assertEqual(service["environment"]["JAVA_OPTS"], "-Xmx384m")
        self.assertEqual(service["volumes"][0]["type"], "bind")
        self.assertEqual(service["volumes"][0]["source"], "/srv/training-data/training-runtime")
        self.assertEqual(service["logging"]["options"]["max-file"], "3")


if __name__ == "__main__":
    unittest.main()
