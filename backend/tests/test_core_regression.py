# -*- coding: utf-8 -*-
"""aibi 核心回归套件（T4：收敛自 30+ 一次性 e2e_/smoke_ 脚本中仍有效的确定性场景）。

覆盖"不花 LLM 调用、结果可复现"的主干回归：健康检查、登录矩阵（成功/错误口令）、
认证门禁（未登录 401）、权限 403 矩阵（USER 触 admin 面）、全局数据面读链路
（驾驶舱模板列表+详情、数据源、看板、历史、主题、生效配置）。

约定：**新增验证一律进本套件（或新建 test_*.py），不再往 backend/ 根目录扔一次性脚本**。
写型场景（建指标/实例化模板）不在默认集内——避免污染 dev 库数据；如确要跑，
新增函数并设置 E2E_ALLOW_WRITE=1 后由函数自行判断。

运行:
  python backend/tests/run_e2e.py                       # 自足 runner（无 pytest 依赖）
  pytest backend/tests                                   # 装了 pytest 的环境
环境:
  AIBI_BASE_URL=http://localhost:8080  目标实例
  实例不可达 → 整套 SKIP（不算失败）
"""
from e2e_lib import (DEMO_PW, DEMO_USER, SUPERADMIN_PW, SUPERADMIN_USER,
                     call, ensure_instance, is_denied, login_token)

# 模块级缓存：只登录一次
_tokens = {}


def _tok(kind):
    ensure_instance()
    if kind not in _tokens:
        if kind == "super":
            _tokens[kind] = login_token(SUPERADMIN_USER, SUPERADMIN_PW)
        else:
            _tokens[kind] = login_token(DEMO_USER, DEMO_PW)
    return _tokens[kind]


def _ok(body):
    return isinstance(body, dict) and body.get("code") in (0, 200)


# ==================== 健康与登录矩阵 ====================

def test_health_up():
    ensure_instance()
    st, body = call("GET", "/actuator/health")
    assert st == 200 and body.get("status") == "UP", "health 非 UP: %s %s" % (st, body)


def test_login_superadmin_and_user():
    ensure_instance()
    sup = login_token(SUPERADMIN_USER, SUPERADMIN_PW)
    usr = login_token(DEMO_USER, DEMO_PW)
    assert sup and usr and sup != usr


def test_login_wrong_password_rejected():
    ensure_instance()
    st, body = call("POST", "/api/auth/login", None,
                    {"username": DEMO_USER, "password": "definitely-wrong-pw"})
    token = (body.get("data") or {}).get("token") if isinstance(body, dict) else None
    assert not token, "错误口令不应签发 token: %s %s" % (st, str(body)[:200])


# ==================== 认证与权限门禁 ====================

def test_no_token_unauthorized():
    ensure_instance()
    st, body = call("GET", "/api/dashboard-template")
    assert is_denied(st, body), "无 token 应 401，实际 %s %s" % (st, body)


def test_user_forbidden_on_admin_apis():
    """USER 令牌触碰平台管理面必须 403（RBAC 落地回归；40301 或 HTTP 403 均算通过）。"""
    tok = _tok("user")
    for path in ("/api/admin/users", "/api/admin/tenants", "/api/admin/overview"):
        st, body = call("GET", path, tok)
        assert is_denied(st, body), "USER 访问 %s 应被拒，实际 %s %s" % (path, st, body)


def test_superadmin_can_list_users():
    st, body = call("GET", "/api/admin/users", _tok("super"))
    assert st == 200 and _ok(body), "超管读用户列表应 200: %s %s" % (st, str(body)[:200])


# ==================== 全局数据面读链路 ====================

def test_dashboard_templates_list_and_detail():
    tok = _tok("user")
    st, body = call("GET", "/api/dashboard-template", tok)
    assert st == 200 and _ok(body), "模板列表应 200: %s" % st
    rows = body.get("data") or []
    assert len(rows) >= 1, "dev 库模板播种后应非空"
    first = rows[0]
    st2, d2 = call("GET", "/api/dashboard-template/" + str(first.get("id")), tok)
    assert st2 == 200 and _ok(d2), "模板详情应 200: %s" % st2


def test_datasource_and_dashboard_list():
    tok = _tok("user")
    st, ds = call("GET", "/api/datasource", tok)
    assert st == 200 and _ok(ds), "数据源列表应 200: %s" % st
    st2, dash = call("GET", "/api/dashboard", tok)
    assert st2 == 200 and _ok(dash), "看板列表应 200: %s" % st2


def test_semantic_meta_list():
    """语义层管理读链路（T8.4 拆出 SemanticViewStore 后补的回归）：
    取可见数据源首个 id，GET /api/semantic?datasourceId=——纯读，
    命中 semanticService.list → store.list 的 Mapper 排序查询；空列表也算通过（骨架未建）。"""
    tok = _tok("user")
    st, ds = call("GET", "/api/datasource", tok)
    assert st == 200 and _ok(ds), "数据源列表应 200: %s" % st
    data = ds.get("data")
    rows = data if isinstance(data, list) else ((data or {}).get("items") or [])
    if not rows:
        return  # 无可见数据源，仅验证列表链路
    ds_id = rows[0].get("id") if isinstance(rows[0], dict) else rows[0]
    st2, sm = call("GET", "/api/semantic?datasourceId=%s" % ds_id, tok)
    assert st2 == 200 and _ok(sm), "语义行列表应 200: %s %s" % (st2, str(sm)[:200])


def test_history_and_theme_and_effective_config():
    tok = _tok("user")
    st, h = call("GET", "/api/history?limit=1&offset=0", tok)
    assert st == 200 and _ok(h), "历史分页应 200: %s" % st
    st2, th = call("GET", "/api/theme/list", tok)
    assert st2 == 200 and _ok(th), "主题列表应 200: %s" % st2
    st3, cfg = call("GET", "/api/config/effective", tok)
    assert st3 == 200 and _ok(cfg), "生效配置(白名单端点)任何角色可读: %s" % st3


def test_history_detail_replay():
    """历史详情回放（T8.3 抽出 HistoryReplayer 后补的回归）：
    取本人最早一条历史，GET /api/history/{id}/detail——纯读链路，
    快照命中零执行；无快照重跑 SQL 也**不耗 LLM、不计配额、不写脏数据**。
    回放链路对数据源失效/校验拒绝均降级返回 200（insight/verify 照常），
    故本用例只断言接口 200 + 结构存在，不要求 result 非空。
    """
    tok = _tok("user")
    st, body = call("GET", "/api/history?limit=1&offset=0", tok)
    assert st == 200 and _ok(body), "历史列表应 200: %s" % st
    items = ((body.get("data") or {}).get("items")) or []
    if not items:
        return  # 该用户无历史，只验证列表链路（dev 库新用户场景）
    hid = items[0].get("id")
    assert hid, "历史项应带 id: %s" % items[0]
    st2, d2 = call("GET", "/api/history/%s/detail" % hid, tok)
    assert st2 == 200 and _ok(d2), "历史详情回放应 200: %s %s" % (st2, str(d2)[:200])
    assert isinstance(d2.get("data"), dict) and "history" in (d2.get("data") or {}), \
        "详情应含 history 结构: %s" % str(d2)[:200]
