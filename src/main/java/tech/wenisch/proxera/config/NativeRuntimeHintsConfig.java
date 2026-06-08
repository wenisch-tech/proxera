package tech.wenisch.proxera.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.thymeleaf.expression.Lists;
import org.thymeleaf.expression.Numbers;
import org.thymeleaf.expression.Strings;
import org.thymeleaf.expression.Temporals;

import tech.wenisch.proxera.bus.TopologyEvent;
import tech.wenisch.proxera.bus.WsRelayMessage;
import tech.wenisch.proxera.domain.AccessLog;
import tech.wenisch.proxera.domain.Agent;
import tech.wenisch.proxera.domain.AgentStatus;
import tech.wenisch.proxera.domain.ApiKey;
import tech.wenisch.proxera.domain.IngressSpec;
import tech.wenisch.proxera.domain.RegistrationToken;
import tech.wenisch.proxera.domain.Route;
import tech.wenisch.proxera.domain.RouteDomain;
import tech.wenisch.proxera.domain.Settings;
import tech.wenisch.proxera.domain.User;
import tech.wenisch.proxera.service.RegistrationTokenService.ValidationResult;
import tech.wenisch.proxera.tunnel.FrameType;
import tech.wenisch.proxera.tunnel.RequestPayload;
import tech.wenisch.proxera.tunnel.ResponsePayload;
import tech.wenisch.proxera.tunnel.TunnelFrame;

@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeRuntimeHintsConfig.ThymeleafExpressionRuntimeHints.class)
public class NativeRuntimeHintsConfig {

    static class ThymeleafExpressionRuntimeHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            registerExpressionHelper(hints, Lists.class);
            registerExpressionHelper(hints, Numbers.class);
            registerExpressionHelper(hints, Strings.class);
            registerExpressionHelper(hints, Temporals.class);

            registerTemplateModel(hints, AccessLog.class);
            registerTemplateModel(hints, Agent.class);
            registerTemplateModel(hints, AgentStatus.class);
            registerTemplateModel(hints, ApiKey.class);
            registerTemplateModel(hints, IngressSpec.class);
            registerTemplateModel(hints, RegistrationToken.class);
            registerTemplateModel(hints, Route.class);
            registerTemplateModel(hints, RouteDomain.class);
            registerTemplateModel(hints, Settings.class);
            registerTemplateModel(hints, User.class);

            registerJsonPayload(hints, FrameType.class);
            registerJsonPayload(hints, RequestPayload.class);
            registerJsonPayload(hints, ResponsePayload.class);
            registerJsonPayload(hints, TopologyEvent.class);
            registerJsonPayload(hints, TunnelFrame.class);
            registerJsonPayload(hints, ValidationResult.class);
            registerJsonPayload(hints, WsRelayMessage.class);
        }

        private void registerExpressionHelper(RuntimeHints hints, Class<?> type) {
            hints.reflection().registerType(
                    type,
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS
            );
        }

        private void registerTemplateModel(RuntimeHints hints, Class<?> type) {
            hints.reflection().registerType(
                    type,
                    MemberCategory.DECLARED_FIELDS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS
            );
        }

        private void registerJsonPayload(RuntimeHints hints, Class<?> type) {
            hints.reflection().registerType(
                    type,
                    MemberCategory.DECLARED_FIELDS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.INVOKE_PUBLIC_METHODS
            );
        }
    }
}
