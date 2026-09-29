package egovframework.unstructured.collector.common.config;

import org.apache.ibatis.session.SqlSessionFactory;
import org.egovframe.rte.psl.dataaccess.mapper.EgovMapper;
import org.egovframe.rte.psl.dataaccess.mapper.MapperConfigurer;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

/**
 * 전자정부 표준프레임워크 데이터 접근(psl-dataaccess) 설정 — 커넥터와 동일한 표준 패턴.
 *
 * <p>eGov {@link MapperConfigurer} 는 이름이 {@code sqlSession} 인 SqlSessionFactory 빈을
 * 쓰도록 기본 설정되어 있어, 그 이름으로 직접 정의한다.</p>
 */
@Configuration
public class EgovConfigDataAccess {

    @Bean
    public static MapperConfigurer mapperConfigurer() {
        MapperConfigurer mapperConfigurer = new MapperConfigurer();
        mapperConfigurer.setBasePackage("egovframework.unstructured.collector.voice.mapper");
        mapperConfigurer.setAnnotationClass(EgovMapper.class);
        return mapperConfigurer;
    }

    @Bean(name = "sqlSession")
    public SqlSessionFactory sqlSession(DataSource dataSource) throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setMapperLocations(resolver.getResources("classpath*:/mappers/**/*.xml"));
        return bean.getObject();
    }
}
