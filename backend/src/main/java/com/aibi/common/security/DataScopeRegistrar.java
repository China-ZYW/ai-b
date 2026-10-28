package com.aibi.common.security;

import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 启动时扫描所有 Mapper 的 {@link RowScope} 标注（类级或方法级），把 statementId→规则注册进
 * {@link AibiDataScopeHandler}；请求期 {@code DataPermissionInterceptor} 据此追加行级条件。
 *
 * <p>类级标注对本 Mapper 全部动作生效（含继承自 {@code BaseMapper} 的 selectById/selectList 等）；
 * 方法级标注精确覆盖单个方法。{@code @InterceptorIgnore(dataPermission="true")} 的方法会被
 * DataPermissionInterceptor 整体跳过，但 Registrar 仍会注册（无害，handler 不会被调用）。
 */
@Component
public class DataScopeRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataScopeRegistrar.class);

    private final SqlSessionFactory sqlSessionFactory;
    private final AibiDataScopeHandler handler;

    public DataScopeRegistrar(SqlSessionFactory sqlSessionFactory, AibiDataScopeHandler handler) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.handler = handler;
    }

    @Override
    public void run(ApplicationArguments args) {
        Configuration configuration = sqlSessionFactory.getConfiguration();
        int count = 0;
        // 注意：getMappedStatements() 底层是 MyBatis 的 StrictMap，除完整 statementId 外还会为「短名」
        // （如 selectById）额外存一个键；同名短名冲突时该键的值是 Ambiguity 占位对象而非 MappedStatement。
        // 直接按 MappedStatement 迭代会 ClassCastException，故先做类型过滤再处理。
        for (Object value : (java.util.Collection<?>) configuration.getMappedStatements()) {
            if (!(value instanceof MappedStatement ms)) {
                continue;
            }
            String id = ms.getId(); // com.aibi.module.history.mapper.QueryHistoryMapper.selectById
            if (id == null || !id.contains(".")) {
                continue;
            }
            int lastDot = id.lastIndexOf('.');
            String namespace = id.substring(0, lastDot);
            String methodName = id.substring(lastDot + 1);
            try {
                Class<?> mapperClass = Class.forName(namespace);
                RowScope anno = mapperClass.getAnnotation(RowScope.class); // 类级优先
                if (anno == null) {
                    Method m = findMethod(mapperClass, methodName); // 含 BaseMapper 继承方法
                    if (m != null) {
                        anno = m.getAnnotation(RowScope.class);
                    }
                }
                if (anno != null) {
                    handler.register(id, new DataScopeRule(anno.uidColumn(), anno.teamColumn()));
                    count++;
                }
            } catch (ClassNotFoundException e) {
                log.debug("跳过非 Mapper statement：{}", id);
            } catch (Throwable t) {
                log.warn("DataScope 扫描异常（statement={}）：{}", id, t.getMessage());
            }
        }
        log.info("DataScope 行级规则注册完成：{} 条 Mapper 动作受 SELF 范围约束", count);
    }

    /** 沿本类及其全部父接口向上查找方法（BaseMapper 方法声明在父接口）。 */
    private static Method findMethod(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
            for (Class<?> iface : c.getInterfaces()) {
                Method m = findMethod(iface, name);
                if (m != null) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
