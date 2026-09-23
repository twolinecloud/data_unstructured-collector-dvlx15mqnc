package egovframework.unstructured.collector.swagger;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.servers.Server;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.log4j.Log4j2;

@Log4j2
@Configuration
class SwaggerConfig {

    private final String moduleName;
    private final String apiVersion;
    private final String description;
    private final String servicePath;

    public SwaggerConfig(
        @Value("${spring.application.name}") String moduleName,
        @Value("${spring.application.version}") String apiVersion,
        @Value("${spring.application.desc}") String desc,
        @Value("${service-path}") String servicePath
    ) {
        this.description = desc;
        this.moduleName = moduleName;
        this.apiVersion = apiVersion;
        this.servicePath = servicePath;
    }

    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "Authentication";
        final String apiTitle = String.format("%s API", StringUtils.capitalize(moduleName));
        Server server = new Server().url(servicePath);

        Contact contact = new Contact()
            .name("Turaco")
            .email("mailto:turaco.team@twolinecloud.com")
            .url("https://console.turacocloud.com");

        License license = new License()
            .name("Apache 2.0")
            .url("http://www.apache.org/licenses/LICENSE-2.0.html");

        Info info = new Info()
            .title(apiTitle)
            .version(apiVersion)
            .extensions(extensions())
            .contact(contact)
            .license(license)
            .description(SwaggerMarkdown.DESCRIPTION.asString());

        return new OpenAPI()
            .servers(Arrays.asList(server))
            .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
            .components(
                new Components()
                    .addSecuritySchemes(securitySchemeName,
                        new io.swagger.v3.oas.models.security.SecurityScheme()
                            .name(securitySchemeName)
                            .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                    )
            )
            .info(info);
    }

    private Map<String, Object> extensions() {
        Map<String, Object> ext = new HashMap<>();

        xLogo xLogo = new xLogo();
        xLogo.setUrl("https://ci3.googleusercontent.com/mail-sig/AIorK4zxC16Rz0s4OHzJ-dw6JgzUJpocxGJNCnH7UFPc1wVg2ZKSai18i0TpKoRegFwfJSfIHcB_--w/");
        xLogo.setAltText("Turaco Cloud");

        ext.put("x-logo", xLogo);
        return ext;
    }

    @Getter
    @Setter
    class xLogo{
        private String url;
        private String altText;
    }
}

