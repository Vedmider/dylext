package com.example.contextmcp.adapter.out.summarize;

import com.example.contextmcp.domain.model.Symbol;
import com.example.contextmcp.domain.port.out.Summarizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/** Default summarizer: disabled. Serves skeletons only until a real provider bean is configured. */
@Configuration
public class SummarizerConfig {

    @Bean
    @ConditionalOnMissingBean(Summarizer.class)
    public Summarizer noOpSummarizer() {
        return new Summarizer() {
            @Override
            public Optional<Result> summarize(Symbol symbol, String sourceSpan) {
                return Optional.empty();
            }

            @Override
            public boolean enabled() {
                return false;
            }
        };
    }
}
