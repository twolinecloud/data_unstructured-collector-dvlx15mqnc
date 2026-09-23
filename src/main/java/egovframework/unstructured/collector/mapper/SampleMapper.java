package egovframework.unstructured.collector.mapper;

import egovframework.unstructured.collector.dto.SampleDto;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Mapper
public interface SampleMapper {
    void insert(SampleDto dto);

    List<SampleDto> selectAll();

    int delete(String id);
}

