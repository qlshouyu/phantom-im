package im.phantom.search;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import im.phantom.search.index.InMemorySearchIndex;
import im.phantom.search.spi.SearchIndex;

/** 搜索：联系人、群组和聊天记录索引与查询。 自动装配入口。 */
@AutoConfiguration
@ComponentScan(basePackageClasses = SearchAutoConfiguration.class)
public class SearchAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SearchIndex.class)
    public SearchIndex searchIndex() {
        return new InMemorySearchIndex();
    }
}
