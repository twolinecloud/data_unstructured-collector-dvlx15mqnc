package egovframework.unstructured.collector.controller;

import egovframework.unstructured.collector.response.Response;
import egovframework.unstructured.collector.service.SampleMyBatisService;
import egovframework.unstructured.collector.vo.SampleVo;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class SampleMyBatisController {

    private final SampleMyBatisService service;

    @PostMapping(value = "/db-test")
    public Callable<Response<Object>> post(@RequestBody SampleVo vo) {
        return () -> service.post(vo);
    }

    @GetMapping(value = "/db-test")
    public Callable<Response<Object>> get() {
        return () -> service.get();
    }

    @DeleteMapping("/db-test/{id}")
    public Callable<Response<Object>> delete(@PathVariable String id) {
        return () -> service.delete(id);
    }
}
