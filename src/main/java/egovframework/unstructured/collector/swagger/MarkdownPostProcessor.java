package egovframework.unstructured.collector.swagger;

import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

@Log4j2
@Configuration
@AllArgsConstructor
public class MarkdownPostProcessor implements BeanPostProcessor {
    private final ApplicationContext applicationContext;

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName)
    throws BeansException {
        try {
            MarkdownDescription classDescription = applicationContext.findAnnotationOnBean(beanName, MarkdownDescription.class);
            if (classDescription != null) {
                SwaggerMarkdown markdown = classDescription.markdown();
                replaceClass(bean, markdown);
            }
        } catch (
        BeansException e) {
            return bean;
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return bean;
    }

    public void replaceClass(Object bean, SwaggerMarkdown markdown) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        if (markdown.getType() == SwaggerType.Tag) {
            Map<String, Object> valuesMap = new HashMap<>();
            valuesMap.put("name", markdown.getName());
            valuesMap.put("description", markdown.asString());
            valuesMap.put("externalDocs", externalDocumentation());
            valuesMap.put("extensions", externalDocumentation().extensions());
            RuntimeAnnotations.putAnnotation(bean.getClass(), Tag.class, valuesMap);
            //RuntimeAnnotations.putAnnotation(bean.getClass(), Tag.class, valuesMap);
        }
    }

    private ExternalDocumentation externalDocumentation(){
        return new ExternalDocumentation(){
            @Override
            public Class<? extends Annotation> annotationType() {
                return ExternalDocumentation.class;
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public String url() {
                return "";
            }

            @Override
            public Extension[] extensions() {
                return new Extension[0];
            }
        };
    }
}
