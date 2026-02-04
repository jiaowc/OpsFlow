package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.ServiceDTO;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.model.Service;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 服务管理控制器
 */
@RestController
@RequestMapping("/api/service")
public class ServiceController {

    @Autowired
    private ServiceMapper serviceMapper;

    /**
     * 创建服务
     */
    @PostMapping("/create")
    public ServiceDTO createService(@RequestBody ServiceDTO request) {
        Service service = new Service();
        BeanUtils.copyProperties(request, service);
        service.setStatus(1);
        service.setCreateTime(LocalDateTime.now());
        service.setUpdateTime(LocalDateTime.now());
        
        serviceMapper.insert(service);
        
        ServiceDTO dto = new ServiceDTO();
        BeanUtils.copyProperties(service, dto);
        return dto;
    }

    /**
     * 查询服务列表
     */
    @GetMapping("/list")
    public List<ServiceDTO> listServices() {
        List<Service> services = serviceMapper.selectList(new QueryWrapper<>());
        return services.stream().map(service -> {
            ServiceDTO dto = new ServiceDTO();
            BeanUtils.copyProperties(service, dto);
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 查询服务详情
     */
    @GetMapping("/{id}")
    public ServiceDTO getService(@PathVariable Long id) {
        Service service = serviceMapper.selectById(id);
        if (service == null) {
            return null;
        }
        ServiceDTO dto = new ServiceDTO();
        BeanUtils.copyProperties(service, dto);
        return dto;
    }

    /**
     * 更新服务
     */
    @PutMapping("/{id}")
    public ServiceDTO updateService(@PathVariable Long id, @RequestBody ServiceDTO request) {
        Service service = serviceMapper.selectById(id);
        if (service == null) {
            return null;
        }
        
        BeanUtils.copyProperties(request, service, "id", "createTime");
        service.setUpdateTime(LocalDateTime.now());
        serviceMapper.updateById(service);
        
        ServiceDTO dto = new ServiceDTO();
        BeanUtils.copyProperties(service, dto);
        return dto;
    }

    /**
     * 删除服务
     */
    @DeleteMapping("/{id}")
    public boolean deleteService(@PathVariable Long id) {
        return serviceMapper.deleteById(id) > 0;
    }
}


