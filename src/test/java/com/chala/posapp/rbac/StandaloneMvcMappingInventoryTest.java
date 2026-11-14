package com.chala.posapp.rbac;

import com.chala.posapp.module.ModuleRouteResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.AnnotatedElement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Standalone Spring MVC annotation inventory; deliberately does not create a Boot context. */
class StandaloneMvcMappingInventoryTest {
    private static final String CONTROLLER_RESOURCE = "classpath*:com/chala/posapp/controller/*Controller.class";
    private static final String OUTPUT = "target/rbac-mapping-inventory.json";
    private static final List<String> EXPLICIT_METHODS = List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");

    @Test
    void exportSpringResolvedMappingsWithoutInstantiatingControllers() throws Exception {
        List<Class<?>> controllerTypes = discoverControllerTypes();
        assertEquals(50, controllerTypes.size(), "Controller inventory changed; review exporter scope");

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.setId("rbac-standalone-mvc-mapping-inventory");
            for (Class<?> type : controllerTypes) {
                RootBeanDefinition definition = new RootBeanDefinition(type);
                definition.setLazyInit(true);
                definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_NO);
                context.registerBeanDefinition(type.getName(), definition);
            }

            assertEquals(50, context.getBeanDefinitionCount());
            assertNoDatabaseOrApplicationBeans(context);
            context.refresh();

            RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
            mapping.setApplicationContext(context);
            mapping.afterPropertiesSet();

            assertEquals(50, context.getBeanDefinitionCount(), "MVC discovery added unexpected bean definitions");
            for (String beanName : context.getBeanDefinitionNames()) {
                assertFalse(context.getBeanFactory().containsSingleton(beanName),
                        "Mapping discovery instantiated bean: " + beanName);
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            ModuleRouteResolver moduleResolver = new ModuleRouteResolver();
            mapping.getHandlerMethods().forEach((info, handler) -> rows.addAll(toRows(info, handler, moduleResolver)));
            rows.sort(Comparator.comparing(row -> row.get("path") + "|" + row.get("methods") + "|" + row.get("handler")));

            assertTrue(rows.size() > 0, "Spring mapping discovery returned no handlers");

            Path output = Path.of(OUTPUT);
            Files.createDirectories(output.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                    "context", context.getId(),
                    "controllerCount", controllerTypes.size(),
                    "mappingCount", rows.size(),
                    "rows", rows));
        }
    }

    private static List<Class<?>> discoverControllerTypes() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(CONTROLLER_RESOURCE);
        SimpleMetadataReaderFactory metadata = new SimpleMetadataReaderFactory();
        List<Class<?>> types = new ArrayList<>();
        for (Resource resource : resources) {
            MetadataReader reader = metadata.getMetadataReader(resource);
            String className = reader.getClassMetadata().getClassName();
            types.add(Class.forName(className, false, Thread.currentThread().getContextClassLoader()));
        }
        types.sort(Comparator.comparing(Class::getName));
        return types;
    }

    private static void assertNoDatabaseOrApplicationBeans(GenericApplicationContext context) {
        for (String beanName : context.getBeanDefinitionNames()) {
            String className = context.getBeanDefinition(beanName).getBeanClassName();
            assertFalse(className == null || className.contains("DataSource") || className.contains("EntityManager")
                            || className.contains("Flyway") || className.contains("Tenant")
                            || className.contains("Runner") || className.contains("Scheduler"),
                    "Unexpected application/database bean definition: " + beanName + " -> " + className);
        }
    }

    private static List<Map<String, Object>> toRows(RequestMappingInfo info, HandlerMethod handler,
                                                     ModuleRouteResolver moduleResolver) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> methods = info.getMethodsCondition().getMethods().stream().map(Enum::name).sorted().toList();
        List<String> paths = info.getPatternValues().stream().sorted().toList();
        for (String path : paths) {
            List<String> effectiveMethods = methods.isEmpty() ? EXPLICIT_METHODS : methods;
            for (String method : effectiveMethods) {
                String normalized = moduleResolver.normalize(path);
                var module = moduleResolver.resolve(normalized, method);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", path);
                row.put("normalizedPath", normalized);
                row.put("method", methods.isEmpty() ? "ANY" : method);
                row.put("moduleEvaluationMethod", method);
                row.put("mappingMethodsUnspecified", methods.isEmpty());
                row.put("handler", handler.getBeanType().getName() + "#" + handler.getMethod().getName());
                row.put("classMapping", mappingMetadata(handler.getBeanType()));
                row.put("methodMapping", mappingMetadata(handler.getMethod()));
                row.put("classPreAuthorize", preAuthorize(handler.getBeanType()));
                row.put("methodPreAuthorize", preAuthorize(handler.getMethod()));
                row.put("classSecurityAnnotations", securityAnnotations(handler.getBeanType()));
                row.put("methodSecurityAnnotations", securityAnnotations(handler.getMethod()));
                row.put("params", info.getParamsCondition().toString());
                row.put("headers", info.getHeadersCondition().toString());
                row.put("consumes", info.getConsumesCondition().toString());
                row.put("produces", info.getProducesCondition().toString());
                row.put("subscriptionExempt", moduleResolver.isSubscriptionExempt(normalized, method));
                row.put("moduleExempt", moduleResolver.isModuleExempt(normalized, method));
                row.put("moduleKey", module.orElse(null));
                row.put("unmatchedModule", module.isEmpty());
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, Object> mappingMetadata(AnnotatedElement element) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(element, RequestMapping.class);
        if (mapping == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", Arrays.asList(mapping.path()));
        result.put("method", Arrays.stream(mapping.method()).map(Enum::name).toList());
        result.put("params", Arrays.asList(mapping.params()));
        result.put("headers", Arrays.asList(mapping.headers()));
        result.put("consumes", Arrays.asList(mapping.consumes()));
        result.put("produces", Arrays.asList(mapping.produces()));
        result.put("name", mapping.name());
        return result;
    }

    private static String preAuthorize(AnnotatedElement element) {
        PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(element, PreAuthorize.class);
        return annotation == null ? null : annotation.value();
    }

    private static List<String> securityAnnotations(AnnotatedElement element) {
        return Arrays.stream(element.getAnnotations())
                .map(annotation -> annotation.annotationType().getName())
                .filter(name -> name.contains("PreAuthorize") || name.contains("PostAuthorize")
                        || name.contains("Secured") || name.contains("RolesAllowed"))
                .sorted().toList();
    }
}
