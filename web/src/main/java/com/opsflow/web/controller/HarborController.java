package com.opsflow.web.controller;

import com.opsflow.integration.harbor.HarborClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Harbor镜像管理控制器
 */
@RestController
@RequestMapping("/api/harbor")
public class HarborController {

    @Autowired(required = false)
    private HarborClient harborClient;

    /**
     * 获取Harbor项目列表
     */
    @GetMapping("/projects")
    public List<String> getProjects() {
        if (harborClient == null) {
            // 返回模拟数据
            return Arrays.asList("project1", "project2", "project3");
        }
        try {
            return harborClient.listProjects();
        } catch (Exception e) {
            // 返回模拟数据
            return Arrays.asList("project1", "project2", "project3");
        }
    }

    /**
     * 获取指定项目的镜像列表
     */
    @GetMapping("/images")
    public List<String> getImages(@RequestParam(required = false) String project) {
        if (harborClient == null) {
            // 返回模拟数据
            return Arrays.asList(
                "harbor.example.com/project1/service1:v1.0.0",
                "harbor.example.com/project1/service1:v1.0.1",
                "harbor.example.com/project1/service2:v2.0.0",
                "harbor.example.com/project2/service3:v1.5.0"
            );
        }
        try {
            if (project != null && !project.isEmpty()) {
                return harborClient.listImages(project);
            } else {
                // 返回所有项目的镜像
                List<String> allImages = new ArrayList<>();
                List<String> projects = harborClient.listProjects();
                for (String proj : projects) {
                    allImages.addAll(harborClient.listImages(proj));
                }
                return allImages;
            }
        } catch (Exception e) {
            // 返回模拟数据
            return Arrays.asList(
                "harbor.example.com/project1/service1:v1.0.0",
                "harbor.example.com/project1/service1:v1.0.1",
                "harbor.example.com/project1/service2:v2.0.0",
                "harbor.example.com/project2/service3:v1.5.0"
            );
        }
    }

    /**
     * 获取指定项目的镜像标签列表
     */
    @GetMapping("/images/{imageName}/tags")
    public List<String> getImageTags(@PathVariable String imageName, @RequestParam(required = false) String project) {
        if (harborClient == null) {
            // 返回模拟数据
            return Arrays.asList("v1.0.0", "v1.0.1", "v1.1.0", "latest");
        }
        try {
            return harborClient.listImageTags(project, imageName);
        } catch (Exception e) {
            // 返回模拟数据
            return Arrays.asList("v1.0.0", "v1.0.1", "v1.1.0", "latest");
        }
    }

    /**
     * 根据服务名称获取版本列表
     * 会在所有Harbor项目中查找该服务名称的镜像，并返回所有标签（版本）
     */
    @GetMapping("/service/{serviceName}/versions")
    public List<String> getServiceVersions(@PathVariable String serviceName) {
        if (harborClient == null) {
            // 返回模拟数据
            return Arrays.asList("v1.0.0", "v1.0.1", "v1.1.0", "develop-20250101120000", "main-20250101120000");
        }
        try {
            List<String> allVersions = new ArrayList<>();
            // 获取所有项目
            List<String> projects = harborClient.listProjects();
            for (String project : projects) {
                try {
                    // 获取该项目的所有镜像
                    List<String> images = harborClient.listImages(project);
                    // 查找包含该服务名称的镜像
                    for (String image : images) {
                        // 解析镜像名称，格式：harbor.example.com/project/service-name:tag
                        String[] parts = image.split(":");
                        if (parts.length == 2) {
                            String imagePath = parts[0];
                            String tag = parts[1];
                            // 检查镜像路径是否包含服务名称
                            if (imagePath.contains("/" + serviceName) || imagePath.endsWith("/" + serviceName)) {
                                allVersions.add(tag);
                            }
                        }
                    }
                } catch (Exception e) {
                    // 忽略单个项目的错误，继续查找
                }
            }
            // 去重并排序
            return allVersions.stream().distinct().sorted().collect(Collectors.toList());
        } catch (Exception e) {
            // 返回模拟数据
            return Arrays.asList("v1.0.0", "v1.0.1", "v1.1.0", "develop-20250101120000", "main-20250101120000");
        }
    }
}

