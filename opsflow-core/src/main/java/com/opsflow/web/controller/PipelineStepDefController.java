package com.opsflow.web.controller;

import com.opsflow.api.dto.PipelineStepDefDTO;
import com.opsflow.service.PipelineStepDefService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.opsflow.web.security.RequiresPermission;

/**
 * Pipeline 步骤定义管理
 */
@RequiresPermission("pipeline_config:manage")
@RestController
@RequestMapping("/api/pipeline-step-def")
public class PipelineStepDefController {

    @Autowired
    private PipelineStepDefService pipelineStepDefService;

    @PostMapping("/create")
    public PipelineStepDefDTO create(@RequestBody PipelineStepDefDTO request) {
        return pipelineStepDefService.create(request);
    }

    @GetMapping("/list")
    public List<PipelineStepDefDTO> list(
            @RequestParam(required = false) String phase,
            @RequestParam(required = false) String stepType) {
        return pipelineStepDefService.list(phase, stepType);
    }

    @GetMapping("/{id}")
    public PipelineStepDefDTO get(@PathVariable Long id) {
        return pipelineStepDefService.getById(id);
    }

    @PutMapping("/{id}")
    public PipelineStepDefDTO update(@PathVariable Long id, @RequestBody PipelineStepDefDTO request) {
        return pipelineStepDefService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public boolean delete(@PathVariable Long id) {
        return pipelineStepDefService.delete(id);
    }
}
