package tech.wenisch.proxera.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.multipart.MultipartResolver;

@SpringBootTest
class MultipartProxyConfigTest {

    private final ApplicationContext applicationContext;

    MultipartProxyConfigTest(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Test
    void multipartRequestsAreNotParsedBeforeProxying() {
        assertThat(BeanFactoryUtils.beansOfTypeIncludingAncestors(
                applicationContext, MultipartResolver.class)).isEmpty();
    }
}
