package com.aibi.module.chat.nl2sql;

import com.aibi.module.datasource.model.TableMeta;

import java.util.List;

/**
 * 关联推导策略（一次问数内贯穿 选表 → 切片 → Prompt → 校验 的同一份口径）。
 *
 * <p>单独抽出来是因为这三个环节必须看到<b>完全一致</b>的连接键：选表阶段靠它把维度表扩散进切片，
 * Prompt 阶段靠它告知 LLM 可用的键，校验阶段靠它判断 SQL 有没有错连。任一处口径不同就会出现
 * 「提示词没给这条键、校验却按它判错」这类自相矛盾。
 *
 * @param infer  是否允许按命名约定推断关联（配置 {@code NL2SQL/relation-infer}）；
 *               业务库普遍没有外键约束，关掉它就等于放弃多表问数的连接键来源
 * @param manual 人工登记的关联（「表关联」维护页），可信度最高，会覆盖同键的外键与推断结果
 */
public record RelationHints(boolean infer, List<TableRelation> manual) {

    public RelationHints {
        manual = manual == null ? List.of() : manual;
    }

    /** 不推断、无人工登记：只用库中外键约束（引入关联能力之前的行为）。 */
    public static RelationHints none() {
        return new RelationHints(false, List.of());
    }

    /** 按此策略推导给定表集合内的全部关联。 */
    public List<TableRelation> relationsOf(List<TableMeta> tables) {
        return JoinGraph.relations(tables, infer, manual);
    }
}
