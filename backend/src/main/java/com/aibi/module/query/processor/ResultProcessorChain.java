package com.aibi.module.query.processor;

import com.aibi.module.datasource.model.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * {@link ResultProcessor} SPI 的默认执行器：按 {@code order()} 升序串联全部处理器。
 *
 * <p><b>容错即设计</b>：单个处理器抛异常时记日志并<b>跳过它继续跑</b>，而不是让整次查询失败。
 * 后置处理链的定位是"锦上添花"，不能因为一个可选增强坏了就让用户的 SQL 白跑——
 * 这与 {@code AskSkillRouter.safePlan} 的哲学一致。
 *
 * <p>无处理器时直接返回原结果，不做任何复制。
 */
@Component
public class ResultProcessorChain implements ResultProcessor {

    private static final Logger log = LoggerFactory.getLogger(ResultProcessorChain.class);

    private final List<ResultProcessor> processors;

    public ResultProcessorChain(List<ResultProcessor> processors) {
        this.processors = processors == null ? List.of() : processors.stream()
                .filter(p -> p != null)
                .sorted(Comparator.comparingInt(ResultProcessor::order))
                .toList();
        if (!this.processors.isEmpty()) {
            log.info("结果处理器已注册 {} 个: {}", this.processors.size(),
                    this.processors.stream().map(p -> p.getClass().getSimpleName()).toList());
        }
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE - 1;
    }

    @Override
    public boolean supports(QueryResult result, RunContext ctx) {
        return true;
    }

    @Override
    public QueryResult process(QueryResult result, RunContext ctx) {
        QueryResult current = result;
        for (ResultProcessor p : processors) {
            if (!p.supports(current, ctx)) {
                continue;
            }
            try {
                QueryResult processed = p.process(current, ctx);
                if (processed != null) {
                    current = processed;
                }
            } catch (RuntimeException e) {
                log.warn("结果处理器[{}]执行失败，已跳过（不影响本次查询）：{}",
                        p.getClass().getSimpleName(), e.getMessage());
            }
        }
        return current;
    }

    /** 供调试/管理页查看已注册的处理链。 */
    public List<String> registeredNames() {
        return processors.stream().map(p -> p.getClass().getSimpleName()
                + "(" + p.order() + ")").toList();
    }
}
