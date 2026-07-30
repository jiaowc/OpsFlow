package com.opsflow.web.controller;

import com.opsflow.api.dto.PipelineTemplateDTO;
import com.opsflow.service.PipelineTemplateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.opsflow.web.security.RequiresPermission;

/**
 * Pipeline 模版管理（Dockerfile / Deployment / Service）
 */
@RequiresPermission("pipeline_config:manage")
@RestController
@RequestMapping("/api/pipeline-template")
public class PipelineTemplateController {

    @Autowired
    private PipelineTemplateService pipelineTemplateService;

    @PostMapping("/create")
    public PipelineTemplateDTO create(@RequestBody PipelineTemplateDTO request) {
        return pipelineTemplateService.create(request);
    }

    @GetMapping("/list")
    public List<PipelineTemplateDTO> list(@RequestParam(required = false) String type) {
        return pipelineTemplateService.list(type);
    }

    @GetMapping("/{id}")
    public PipelineTemplateDTO get(@PathVariable Long id) {
        return pipelineTemplateService.getById(id);
    }

    @PutMapping("/{id}")
    public PipelineTemplateDTO update(@PathVariable Long id, @RequestBody PipelineTemplateDTO request) {
        return pipelineTemplateService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public boolean delete(@PathVariable Long id) {
        return pipelineTemplateService.delete(id);
    }
}
