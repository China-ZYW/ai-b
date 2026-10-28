# -*- coding: utf-8 -*-
"""从 DDL 文本反解表结构元数据，并复刻 ``JoinGraph`` 的命名推断规则。

体检脚本（``_check.py``）与灌数后校验（``sales_verify.py``）都从这里取元数据：前者用它核对
``insert()`` 的列清单与推断额度，后者用它**自动生成**「每个 ``xxx_id`` 的孤儿行」检查。
两处若各写一套解析，规则一旦漂移就会互相掩盖问题，所以推断规则只在本模块实现一次。

推断规则与 ``JoinGraph.java`` 严格对齐（不是近似）：

- 规则①：列名形如 ``xxx_id``，剥掉 ``_id`` 后在 nameIndex 里**唯一**命中另一张表才算成功；
  命中多张（歧义）或零张都放弃。自引用（候选就是自己）也放弃。
- 规则②：列名恰好等于另一张表的**单列主键名**，且该主键名长度 ≥3 且不等于 ``id``。
  这条是为了让 ``carrier.carrier_code``、``customer_level.level_code`` 这类以业务编码为主键的表也能被关联上。
- ``nameIndex`` 为每张表登记 ``{norm, norm+s, norm+es, singular(norm), raw}``，
  ``norm`` 由 ``normTableName`` 剥掉数仓前后缀得到。
"""
import re

import sales_schema_master as _master
import sales_schema_txn as _txn
import sales_wide as _wide

PREFIXES = ("dwd_", "dws_", "ads_", "ods_", "dim_", "fact_", "tbl_", "tb_", "dw_", "t_")
SUFFIXES = ("_info", "_tbl", "_table", "_t")
DDL_SKIP = ("PRIMARY KEY", "UNIQUE KEY", "UNIQUE ", "KEY ", "INDEX ", "CONSTRAINT", "FOREIGN KEY")

# 与 JoinGraph 保持一致的额度上限；体检脚本用它判断「推断额度够不够」
MAX_INFERRED = 60
MAX_INFERRED_PER_TABLE = 6


class Table(object):
    """一张表的结构元数据。"""

    def __init__(self, name, domain, ddl):
        self.name, self.domain, self.ddl = name, domain, ddl
        self.columns, self.defs = [], {}
        body = ddl[ddl.index("(") + 1: ddl.rindex(")")]
        for line in body.split("\n"):
            s = line.strip().rstrip(",")
            if not s or s.upper().startswith(DDL_SKIP):
                continue
            m = re.match(r"`?(\w+)`?\s+", s)
            if m:
                self.columns.append(m.group(1))
                self.defs[m.group(1)] = s
        tm = re.search(r"COMMENT\s*=\s*'([^']*)'", ddl[ddl.rindex(")"):])
        self.comment = tm.group(1) if tm else ""
        pm = re.search(r"PRIMARY KEY\s*\(([^)]*)\)", ddl, re.I)
        self.pk = [c.strip().strip("`") for c in pm.group(1).split(",")] if pm else []
        self.fks = re.findall(
            r"FOREIGN KEY\s*\((\w+)\)\s+REFERENCES\s+(\w+)\s*\((\w+)\)", ddl, re.I)

    def comment_of(self, col):
        m = re.search(r"COMMENT\s+'([^']*)'", self.defs.get(col, ""))
        return m.group(1) if m else ""

    def not_null_no_default(self, col):
        d = self.defs.get(col, "").upper()
        return ("NOT NULL" in d and "DEFAULT" not in d and "AUTO_INCREMENT" not in d)


def load_tables():
    """返回 (base, wide, populate)：前两个是 {表名: Table}，populate 是 {宽表名: 派生SQL}。"""
    base, wide = {}, {}
    for name, domain, ddl in _master.MASTER_TABLES + _txn.TXN_TABLES:
        base[name] = Table(name, domain, ddl)
    for name, domain, ddl in _wide.WIDE_TABLES:
        wide[name] = Table(name, domain, ddl)
    return base, wide, _wide.WIDE_POPULATE


def norm_table_name(name):
    n = name
    for p in PREFIXES:
        if n.startswith(p):
            n = n[len(p):]
            break
    for s in SUFFIXES:
        if n.endswith(s):
            n = n[: -len(s)]
            break
    return n


def singular(word):
    if word.endswith("ies") and len(word) > 3:
        return word[:-3] + "y"
    if word.endswith("ses") and len(word) > 3:
        return word[:-2]
    if word.endswith("s") and not word.endswith("ss") and len(word) > 3:
        return word[:-1]
    return word


def build_name_index(tables):
    """nameIndex：键 → 命中该键的表名集合。多个表撞进同一个键就会造成推断歧义。"""
    index = {}
    for t in tables:
        n = norm_table_name(t)
        for key in {n, n + "s", n + "es", singular(n), t}:
            index.setdefault(key, set()).add(t)
    return index


def pk_index(tables_meta):
    """单列主键名 → 表名集合（规则②用）。复合主键不参与。"""
    index = {}
    for name, tb in tables_meta.items():
        if len(tb.pk) == 1 and len(tb.pk[0]) >= 3 and tb.pk[0] != "id":
            index.setdefault(tb.pk[0], set()).add(name)
    return index


def infer_relations(base, wide):
    """模拟 JoinGraph 的推断，返回 (成功, 歧义, 无候选)。

    成功项为 ``(表, 列, 被引用表, 被引用列, 规则)``；被引用列取被引用表的单列主键。
    真 FOREIGN KEY 与推断结果分别返回，便于核对三条来源路径（MANUAL / FOREIGN_KEY / INFERRED）。
    """
    all_meta = dict(base)
    all_meta.update(wide)
    names = sorted(all_meta)
    name_index = build_name_index(names)
    pki = pk_index(all_meta)

    ok, ambiguous, missed = [], [], []
    for t in names:
        tb = all_meta[t]
        for col in tb.columns:
            if col == "id" or not col.endswith("_id"):
                continue
            cands = name_index.get(col[:-3], set()) - {t}
            if len(cands) == 1:
                ref = next(iter(cands))
                rp = all_meta[ref].pk
                ok.append((t, col, ref, rp[0] if len(rp) == 1 else "id", "ID_SUFFIX"))
            elif len(cands) > 1:
                ambiguous.append((t, col, sorted(cands)))
            else:
                missed.append((t, col))
        # 规则②：列名恰等于另一张表的单列主键名
        for col in tb.columns:
            if col.endswith("_id") or col == "id":
                continue
            cands = pki.get(col, set()) - {t}
            if len(cands) == 1:
                ref = next(iter(cands))
                ok.append((t, col, ref, col, "PK_NAME"))
            elif len(cands) > 1:
                ambiguous.append((t, col, sorted(cands)))
    return ok, ambiguous, missed


def real_fks(base, wide):
    """DDL 里显式声明的 FOREIGN KEY：(表, 列, 被引用表, 被引用列)。"""
    out = []
    for group in (base, wide):
        for name, tb in group.items():
            for col, ref, refcol in tb.fks:
                out.append((name, col, ref, refcol))
    return sorted(out)
