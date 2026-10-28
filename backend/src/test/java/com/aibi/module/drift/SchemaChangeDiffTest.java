package com.aibi.module.drift;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aibi.module.drift.SchemaChangeDiff.Kind;
import static com.aibi.module.drift.SchemaChangeDiff.Kind.COLUMN_ADDED;
import static com.aibi.module.drift.SchemaChangeDiff.Kind.COLUMN_DROPPED;
import static com.aibi.module.drift.SchemaChangeDiff.Kind.TABLE_ADDED;
import static com.aibi.module.drift.SchemaChangeDiff.Kind.TABLE_DROPPED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SchemaChangeDiff} 纯函数单测（T12-3 K1）。
 */
class SchemaChangeDiffTest {

    @Test
    @DisplayName("表级增删事件：新增/删除表各一条，稳定排序")
    void tableLevelEvents() {
        Map<String, Set<String>> prev = Map.of(
                "orders", Set.of("id"),
                "ghost", Set.of());
        Map<String, Set<String>> cur = Map.of(
                "orders", Set.of("id"),
                "news", Set.of());
        List<SchemaChangeDiff.Change> out = SchemaChangeDiff.diff(prev, cur);
        assertEquals(List.of(
                new SchemaChangeDiff.Change(TABLE_DROPPED, "ghost", null),
                new SchemaChangeDiff.Change(TABLE_ADDED, "news", null)), out);
    }

    @Test
    @DisplayName("列级增删事件：同表先删后增、按列名排序；空列名忽略")
    void columnLevelEvents() {
        Map<String, Set<String>> prev = Map.of("t", Set.of("amount", "city", " "));
        Map<String, Set<String>> cur = Map.of("t", Set.of("amount", "province"));
        List<SchemaChangeDiff.Change> out = SchemaChangeDiff.diff(prev, cur);
        assertEquals(List.of(
                new SchemaChangeDiff.Change(COLUMN_DROPPED, "t", "city"),
                new SchemaChangeDiff.Change(COLUMN_ADDED, "t", "province")), out);
    }

    @Test
    @DisplayName("drops 只留打断引用的删除类事件")
    void dropsFiltersAdds() {
        Map<String, Set<String>> prev = Map.of("gone", Set.of("a"), "t", Set.of("x"));
        Map<String, Set<String>> cur = Map.of("fresh", Set.of(), "t", Set.of("y"));
        List<SchemaChangeDiff.Change> all = SchemaChangeDiff.diff(prev, cur);
        List<SchemaChangeDiff.Change> drops = SchemaChangeDiff.drops(all);
        assertEquals(4, all.size(), "删表+新增表+删列+新增列");
        assertEquals(2, drops.size());
        assertEquals(TABLE_DROPPED, drops.get(0).kind());
        assertEquals(COLUMN_DROPPED, drops.get(1).kind());
        assertTrue(all.stream().noneMatch(c -> c.kind().isDrop() && c.kind() == Kind.TABLE_ADDED));
    }

    @Test
    @DisplayName("改名启发：同表一删一增成对提示，纯删除不提示")
    void renameHints() {
        List<SchemaChangeDiff.Change> changes = List.of(
                new SchemaChangeDiff.Change(COLUMN_DROPPED, "orders", "city"),
                new SchemaChangeDiff.Change(COLUMN_ADDED, "orders", "province"),
                new SchemaChangeDiff.Change(COLUMN_DROPPED, "users", "age"));
        Map<String, List<String[]>> hints = SchemaChangeDiff.renameHints(changes);
        assertEquals(1, hints.size(), "users 只有删除，不给改名提示");
        assertEquals("city", hints.get("orders").get(0)[0]);
        assertEquals("province", hints.get("orders").get(0)[1]);
    }

    @Test
    @DisplayName("null 安全：prev 空=全新增，cur 空=全删除")
    void nullSafe() {
        assertEquals(1, SchemaChangeDiff.diff(null, Map.of("t", Set.of())).size());
        assertEquals(TABLE_ADDED, SchemaChangeDiff.diff(null, Map.of("t", Set.of())).get(0).kind());
        List<SchemaChangeDiff.Change> dropped = SchemaChangeDiff.diff(Map.of("t", Set.of()), null);
        assertEquals(TABLE_DROPPED, dropped.get(0).kind());
        assertTrue(SchemaChangeDiff.diff(null, null).isEmpty());
    }

    @Test
    @DisplayName("同列集合幂等：无变更返回空表")
    void noChange() {
        Map<String, Set<String> > s = Map.of("t", Set.of("a", "b"), "u", Set.of("c"));
        assertTrue(SchemaChangeDiff.diff(s, s).isEmpty());
    }
}
