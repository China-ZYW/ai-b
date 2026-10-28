# -*- coding: utf-8 -*-
"""e2e 回归套件自足 runner（本机 Python 无 pytest 时的入口；有 pytest 也可直接 pytest）。

用法:  python backend/tests/run_e2e.py [test_module ...]
退出码: 0 全绿（允许 SKIP），1 有 FAIL。
"""
import importlib
import os
import sys
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from e2e_lib import SkipTest  # noqa: E402

DEFAULT_MODULES = ["test_core_regression"]


def main():
    modules = sys.argv[1:] or DEFAULT_MODULES
    passed = failed = skipped = 0
    for mod_name in modules:
        mod = importlib.import_module(mod_name)
        funcs = [f for name in sorted(dir(mod))
                 if name.startswith("test_") and callable(f := getattr(mod, name))]
        print("== %s (%d 用例) ==" % (mod_name, len(funcs)))
        for f in funcs:
            try:
                f()
                passed += 1
                print("  [PASS] " + f.__name__)
            except SkipTest as e:
                skipped += 1
                print("  [SKIP] %s  (%s)" % (f.__name__, e))
                break   # 实例不可达：本模块剩余用例同样跳过
            except Exception:  # noqa: BLE001
                failed += 1
                print("  [FAIL] " + f.__name__)
                traceback.print_exc(limit=2)
    print("\n结果: PASS=%d SKIP=%d FAIL=%d" % (passed, skipped, failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
