package egovframework.unstructured.collector.swagger;

import io.swagger.v3.oas.models.Operation;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

@Component
public class MarkdownOperationCustomizer implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        MarkdownDescription markdownDescription = handlerMethod.getMethodAnnotation(MarkdownDescription.class);
        if(markdownDescription != null){
            SwaggerMarkdown markdown = markdownDescription.markdown();
            if(markdown != null){
                operation.setSummary(markdown.getName());
                operation.setDescription(markdown.asString());
            }
        }
        return operation;
    }
}
