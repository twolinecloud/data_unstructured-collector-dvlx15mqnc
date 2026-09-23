package egovframework.unstructured.collector.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Data
public class SampleVo {
    @Schema(description = "id")
    String id;

    @Schema(description = "content")
    String content;

    @Schema(description = "post")
    String post;
}
