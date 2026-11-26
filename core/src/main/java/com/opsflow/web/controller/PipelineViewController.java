package com.opsflow.web.controller;

import com.opsflow.api.dto.PipelineBoardViewDTO;
import com.opsflow.service.PipelineViewService;
import com.opsflow.web.security.RequiresPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 流水线列表视图管理（按环境筛选任务）
 */
@RestController
@RequestMapping("/api/pipeline-view")
public class PipelineViewController {

    @Autowired
    private PipelineViewService pipelineViewService;

    @RequiresPermission("pipeline_config:manage")
    @PostMapping("/create")
    public PipelineBoardViewDTO create(@RequestBody PipelineBoardViewDTO request) {
        return pipelineViewService.create(request);
    }

    @RequiresPermission({"pipeline:view", "pipeline_config:manage"})
    @GetMapping("/list")
    public List<PipelineBoardViewDTO> list() {
        return pipelineViewService.list();
    }

    @RequiresPermission({"pipeline:view", "pipeline_config:manage"})
    @GetMapping("/{id}")
    public PipelineBoardViewDTO get(@PathVariable Long id) {
        return pipelineViewService.getById(id);
    }

    @RequiresPermission("pipeline_config:manage")
    @PutMapping("/{id}")
    public PipelineBoardViewDTO update(@PathVariable Long id, @RequestBody PipelineBoardViewDTO request) {
        return pipelineViewService.update(id, request);
    }

    @RequiresPermission("pipeline_config:manage")
    @DeleteMapping("/{id}")
    public boolean delete(@PathVariable Long id) {
        return pipelineViewService.delete(id);
    }

    @RequiresPermission("pipeline_config:manage")
    @PutMapping("/reorder")
    public boolean reorder(@RequestBody List<Long> ids) {
        pipelineViewService.reorder(ids);
        return true;
    }
}
