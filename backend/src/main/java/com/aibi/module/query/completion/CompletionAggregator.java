package com.aibi.module.query.completion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 补全聚合器：按 {@link CompletionProvider#order()} 升序串联全部来源并去重。
 *
 * <p><b>容错</b>：单个来源抛异常时记 warn 并跳过（补全只是输入辅助，绝不能把编辑器的输入体验搞坏）；
 * 全部失败时返回空列表——编辑器的原生输入不受影响。
 *
 * <p><b>去重</b>：以 {@code text} 为准，保留 order 更小者。这样"物理列名"来源永远压过"别名"来源，
 * 不会让用户选到一个补全后就写出语义层名字（MySQL 并不认）。
 */
@Component
public class CompletionAggregator {

    private final List<CompletionProvider> providers;

    public CompletionAggregator(List<CompletionProvider> providers) {
        this.providers = providers == null ? List.of() : providers.stream()
                .filter(p -> p != null)
                .sorted(Comparator.comparingInt(CompletionProvider::order))
                .toList();
    }

    /** 聚合一次补全请求。 */
    public List<CompletionItem> complete(CompletionRequest request) {
        List<CompletionItem> merged = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (CompletionProvider p : providers) {
            if (!p.supports(request)) {
                continue;
            }
            try {
                List<CompletionItem> items = p.complete(request);
                if (items == null) {
                    continue;
                }
                for (CompletionItem item : items) {
                    if (item == null || item.text() == null || item.text().isBlank()) {
                        continue;
                    }
                    if (seen.contains(item.text())) {
                        continue;
                    }
                    seen.add(item.text());
                    merged.add(item);
                }
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(CompletionAggregator.class)
                        .warn("补全来源[{}]失败，已跳过：{}", p.name(), e.getMessage());
            }
        }
        return merged;
    }

    /** 已注册来源清单（供管理页核对）。 */
    public List<String> registeredNames() {
        return providers.stream().map(p -> p.name() + "(" + p.order() + ")").toList();
    }
}
