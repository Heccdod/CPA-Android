#!/usr/bin/env python3
"""诊断驱动：干净地起假上游 + 本地 CPA，跑一次流式请求，存档证据。"""
import json, os, shutil, signal, socket, subprocess, sys, time, urllib.request

DIAG = "/root/AgyAndriod/diag"
CPASRV = "/tmp/cpasrv"
KEY = "cpa-android-local"
MOCK_LOG = "/tmp/mock.log"
CPA_LOG = "/tmp/cpa.log"


def free_port(port):
    try:
        out = subprocess.run(["ss", "-ltnpH"], capture_output=True, text=True, timeout=5).stdout
    except Exception:
        return
    for line in out.splitlines():
        if f":{port} " in line and "pid=" in line:
            pid = line.split("pid=")[1].split(",")[0]
            try:
                os.kill(int(pid), signal.SIGKILL)
                print(f"  killed pid {pid} on :{port}")
            except Exception as e:
                print(f"  kill {pid} failed: {e}")


def wait_port(port, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        s = socket.socket()
        s.settimeout(0.4)
        try:
            s.connect(("127.0.0.1", port))
            s.close()
            return True
        except Exception:
            time.sleep(0.2)
        finally:
            try:
                s.close()
            except Exception:
                pass
    return False


def start(scenario, delay=0, probe="ok"):
    free_port(18317)
    free_port(9911)
    time.sleep(1.0)
    for f in (MOCK_LOG, CPA_LOG):
        try:
            os.remove(f)
        except FileNotFoundError:
            pass
    env = dict(os.environ, SCENARIO=scenario, DELAY=str(delay), PROBE=probe)
    mock = subprocess.Popen([sys.executable, f"{DIAG}/mock_upstream.py", "9911"], cwd=DIAG,
                            env=env, stdout=open(MOCK_LOG, "w"), stderr=subprocess.STDOUT,
                            start_new_session=True)
    assert wait_port(9911), "mock did not listen"
    cpa = subprocess.Popen([CPASRV, "--config", f"{DIAG}/config.yaml", "--local-model"], cwd=DIAG,
                           stdout=open(CPA_LOG, "w"), stderr=subprocess.STDOUT,
                           start_new_session=True)
    assert wait_port(18317), "cpa did not listen"
    # 等模型注册完成
    for _ in range(60):
        try:
            req = urllib.request.Request("http://127.0.0.1:18317/v1/models",
                                         headers={"Authorization": "Bearer " + KEY})
            data = json.load(urllib.request.urlopen(req, timeout=5))
            ids = [m["id"] for m in data.get("data", [])]
            if ids:
                return mock, cpa, ids
        except Exception:
            pass
        time.sleep(0.5)
    return mock, cpa, []


def stop(p):
    try:
        os.killpg(os.getpgid(p.pid), signal.SIGKILL)
    except Exception:
        pass


if __name__ == "__main__":
    scen = sys.argv[1]
    delay = float(sys.argv[2]) if len(sys.argv) > 2 else 0
    mock, cpa, ids = start(scen, delay)
    print("models:", [i for i in ids if "3.8" in i] or ids[:5])
    out = subprocess.run(["bash", "-c",
        "curl -sS -m 90 -N -D /tmp/last-hdr.txt -X POST http://127.0.0.1:18317/v1/chat/completions "
        "-H 'Authorization: Bearer " + KEY + "' -H 'Content-Type: application/json' "
        "-d '{\"model\":\"gemini-3.8-flash-high\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":40}'"],
        capture_output=True, text=True, timeout=120)
    open("/tmp/sse-last.txt", "w").write(out.stdout)
    print("=== HTTP ===")
    print(open("/tmp/last-hdr.txt").readline().strip())
    print("=== SSE ===")
    print(out.stdout if out.stdout else "(empty body)")
    print("=== mock hit? ===")
    print(open(MOCK_LOG).read().strip()[-300:] or "(no request reached the mock)")
    print("=== bytes:", len(out.stdout))
