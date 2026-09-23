package egovframework.unstructured.collector.swagger;

import io.swagger.v3.oas.models.OpenAPI;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
//import org.springdoc.core.customizers.OpenApiCustomiser;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class TagSorter implements OpenApiCustomizer {
    @Getter
    @Setter
    @AllArgsConstructor
    private static class TagOrder{
        private SwaggerMarkdown tag;
        private int order = 0;
    }

    @Override
    public void customise(OpenAPI openApi) {
        List<TagOrder> orders = Arrays.asList(
                new TagOrder(SwaggerMarkdown.LOGIN, 0),
                new TagOrder(SwaggerMarkdown.DESCRIPTION, 1),
                new TagOrder(SwaggerMarkdown.ACTUATOR, 2)
        );
        Map<String, Integer> orderMap = new HashMap<>();
        for (TagOrder order : orders) {
            orderMap.put(order.getTag().getName(), order.getOrder());
        }

        openApi.setTags(openApi.getTags().stream().sorted((a, b) -> {
            Integer aOrder = orderMap.getOrDefault(a.getName(), Integer.MAX_VALUE);  // null일 경우 가장 큰 값 사용
            Integer bOrder = orderMap.getOrDefault(b.getName(), Integer.MAX_VALUE);
            return aOrder.compareTo(bOrder);
        }).collect(Collectors.toList()));
    }
}
