package com.fdmultimedia.api.release;

import java.util.List;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Runs before any application bean (so before the database pool, Flyway and object storage are touched) and refuses to start a
 * production-profile instance with missing or placeholder critical configuration. The error lists names and reasons only.
 */
@Configuration
@Profile("prod")
class ProductionConfigurationGuard {

    @Bean
    static BeanFactoryPostProcessor productionConfigurationCheck() {
        return new Check();
    }

    static final class Check implements BeanFactoryPostProcessor, EnvironmentAware {
        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) { this.environment = environment; }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            List<String> problems = ProductionConfigurationValidator.validate(environment);
            if (!problems.isEmpty()) {
                throw new IllegalStateException("Invalid production configuration (" + problems.size() + " problem(s)); values are never printed:\n - "
                        + String.join("\n - ", problems) + "\nSee docs/CONFIGURATION.md.");
            }
        }
    }
}
