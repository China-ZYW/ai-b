# -*- coding: utf-8 -*-
"""aibi e2e 回归套件公共设施（T4 收敛产物，纯标准库，零第三方依赖）。

设计约束（与重构任务清单 T4 一致）：
- 凭据/地址一律从环境读取：``AIBI_BASE_URL``（默认 http://localhost:8080），
  账号口令走 ``scripts/envutil.py``（环境变量 > backend/local.env），不落真实值进代码；
- 实例不可达时整套 SKIP 而非 FAIL（本地没起后端属正常状态）；
- 既兼容 pytest（``test_`` 函数 + pytest.skip），也可用 ``run_e2e.py`` 无 pytest 直跑
  （本机 Python 无 pytest/pip 模块，旧 e2e 脚本正是因此各写各的）。
"""
import json
import os
import sys
import urllib.error
import urllib.request

# envutil 在仓库根的 scripts/ 下（backend/tests/ 往上两层 = 仓库根）
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))), "scripts"))
from envutil import secret  # noqa: E402

BASE = os.environ.get("AIBI_BASE_URL", "http://localhost:8080").rstrip("/")

# 演示账号：DataSeeder 播种的公开默认值（见运维文档"默认账号"表），非敏感凭据
SUPERADMIN_USER = secret("AIBI_SUPERADMIN_USERNAME", "superadmin")
SUPERADMIN_PW = secret("AIBI_SUPERADMIN_PASSWORD", "[redacted-default-pwd]")
DEMO_USER = os.environ.get("E2E_DEMO_USERNAME", "admin")
DEMO_PW = os.environ.get("E2E_DEMO_PASSWORD", "")


class SkipTest(Exception):
    """实例不可达/前置条件不满足：runner 记 SKIP，pytest 场景由 run() 翻译为 pytest.skip。"""


def call(method, path, token=None, body=None, timeout=30):
    """返回 (http_status, parsed_json_or_{error})。网络层异常返回 (0, {...})。"""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json;charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {"raw": "non-json"}
    except Exception as e:  # noqa: BLE001
        return 0, {"error": str(e)}


def instance_alive():
    st, _ = call("GET", "/actuator/health", timeout=5)
    return st == 200


def ensure_instance():
    if not instance_alive():
        raise SkipTest("目标实例不可达: " + BASE + "（请先启动后端，或设置 AIBI_BASE_URL）")


def login_token(username, password, expect_ok=True):
    st, body = call("POST", "/api/auth/login", None, {"username": username, "password": password})
    if expect_ok:
        assert st == 200 and body.get("code") in (0, 200) and body.get("data", {}).get("token"), \
            "登录失败: status=%s body=%s" % (st, body)
        return body["data"]["token"]
    return st, body


def is_denied(status, body, *codes):
    """项目里 401/403 可能表现为 HTTP 状态或统一响应体 code，两种都判。"""
    if status in (401, 403):
        return True
    code = body.get("code") if isinstance(body, dict) else None
    return code in (codes or (40101, 40301))
