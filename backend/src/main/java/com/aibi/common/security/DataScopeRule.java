package com.aibi.common.security;

/**
 * {@link RowScope} 标注解析后的规则载体：一条 Mapper 动作（statementId）对应的行级过滤列声明。
 */
public record DataScopeRule(String uidColumn, String teamColumn) {

    /** 是否至少声明了一个可用过滤列。 */
    public boolean hasColumn() {
        return uidColumn != null && !uidColumn.isBlank()
                || teamColumn != null && !teamColumn.isBlank();
    }
}
