package com.aibi.module.datasource.adapter;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.datasource.DataSourceType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 适配器注册表：Spring 自动注入所有 {@link DataSourceAdapter} 实现，按类型派发到合适的适配器。
 *
 * <p>新增数据库类型无需改此处——只要类型能被某个已注册适配器 {@code supports}，即自动生效。
 */
@Component
public class DataSourceAdapterRegistry {

    private final List<DataSourceAdapter> adapters;

    public DataSourceAdapterRegistry(List<DataSourceAdapter> adapters) {
        this.adapters = adapters;
    }

    public DataSourceAdapter get(DataSourceType type) {
        for (DataSourceAdapter adapter : adapters) {
            if (adapter.supports(type)) {
                return adapter;
            }
        }
        throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED, "不支持的数据源类型: " + type);
    }
}
