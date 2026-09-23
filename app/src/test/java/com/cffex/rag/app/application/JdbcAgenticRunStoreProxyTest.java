package com.cffex.rag.app.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

class JdbcAgenticRunStoreProxyTest {

    @Test
    void supportsSpringClassBasedProxying() {
        JdbcAgenticRunStore store = new JdbcAgenticRunStore(
                mock(JdbcTemplate.class),
                new ObjectMapper()
        );
        ProxyFactory proxyFactory = new ProxyFactory(store);
        proxyFactory.setProxyTargetClass(true);

        assertThatCode(proxyFactory::getProxy).doesNotThrowAnyException();
    }
}
