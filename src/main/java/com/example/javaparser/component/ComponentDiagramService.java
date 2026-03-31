package com.example.javaparser.component;

import com.example.javaparser.llm.LLMService;
import com.example.javaparser.model.CodeEntity;
import com.example.javaparser.model.FunctionalModule;
import com.example.javaparser.model.KnowledgeBase;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.Class;
import org.eclipse.uml2.uml.Package;
import org.eclipse.uml2.uml.resource.UMLResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * 组件图生成服务 - 生成UML 2.0组件图并导出XMI
 */
@Slf4j
@Service
public class ComponentDiagramService {

    @Autowired
    private LLMService llmService;

    private ResourceSet resourceSet;
    private Model model;
    private Map<String, Component> componentMap;
    private Set<String> assignedClasses; // 跟踪已分配的类，避免重复

    /**
     * 初始化UML2资源集
     */
    private void initialize() {
        log.info("初始化组件图资源集...");

        resourceSet = new ResourceSetImpl();

        // 注册UML资源工厂到全局注册表
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put(UMLResource.FILE_EXTENSION, UMLResource.Factory.INSTANCE);
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("xmi", UMLResource.Factory.INSTANCE);

        // 注册UML资源工厂到ResourceSet
        resourceSet.getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put(UMLResource.FILE_EXTENSION, UMLResource.Factory.INSTANCE);
        resourceSet.getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put("xmi", UMLResource.Factory.INSTANCE);

        // 注册UML包
        resourceSet.getPackageRegistry()
                .put(UMLPackage.eNS_URI, UMLPackage.eINSTANCE);

        componentMap = new HashMap<>();
        assignedClasses = new HashSet<>(); // 初始化已分配类集合

        // 创建UML模型
        model = UMLFactory.eINSTANCE.createModel();
        model.setName("ComponentModel");

        log.info("组件图资源集初始化完成");
    }

    /**
     * 生成组件图并导出XMI
     */
    public ComponentGenerationResult generateComponentDiagram(KnowledgeBase knowledgeBase,
                                                              String outputDirectory) throws IOException {
        log.info("开始生成组件图...");

        if (resourceSet == null) {
            initialize();
        }

        File outputDir = new File(outputDirectory);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // 1. 优化模块命名
        optimizeModuleNames(knowledgeBase);

        // 2. 创建UML组件
        createUMLComponents(knowledgeBase);

        // 3. 创建组件间的依赖关系
        createComponentDependencies(knowledgeBase);

        // 4. 创建接口和端口
        createInterfacesAndPorts(knowledgeBase);

        // 5. 保存组件图XMI
        String componentXMI = saveComponentDiagram(outputDir, "component.xmi");

        ComponentGenerationResult result = new ComponentGenerationResult();
        result.setComponentXMIPath(componentXMI);
        result.setTotalComponents(componentMap.size());
        result.setModules(new ArrayList<>(knowledgeBase.getModules().values()));

        // 生成组件质量报告
        generateComponentQualityReport(knowledgeBase);

        log.info("组件图生成完成，共 {} 个组件", componentMap.size());

        return result;
    }

    /**
     * 生成组件质量报告
     */
    private void generateComponentQualityReport(KnowledgeBase knowledgeBase) {
        log.info("=== 组件质量报告 ===");

        int totalComponents = knowledgeBase.getModules().size();
        int totalClasses = assignedClasses.size();
        int unassignedClasses = knowledgeBase.getEntities().size() - assignedClasses.size();

        log.info("总组件数: {}", totalComponents);
        log.info("已分配类数: {}", totalClasses);
        if (unassignedClasses > 0) {
            log.warn("未分配类数: {}", unassignedClasses);
        }

        // 统计组件大小分布
        Map<String, Integer> sizeDistribution = new HashMap<>();
        sizeDistribution.put("小型(3-10类)", 0);
        sizeDistribution.put("中型(11-20类)", 0);
        sizeDistribution.put("大型(21-30类)", 0);
        sizeDistribution.put("超大(>30类)", 0);

        for (FunctionalModule module : knowledgeBase.getModules().values()) {
            int size = module.getEntities().size();
            if (size <= 10) {
                sizeDistribution.put("小型(3-10类)", sizeDistribution.get("小型(3-10类)") + 1);
            } else if (size <= 20) {
                sizeDistribution.put("中型(11-20类)", sizeDistribution.get("中型(11-20类)") + 1);
            } else if (size <= 30) {
                sizeDistribution.put("大型(21-30类)", sizeDistribution.get("大型(21-30类)") + 1);
            } else {
                sizeDistribution.put("超大(>30类)", sizeDistribution.get("超大(>30类)") + 1);
            }
        }

        log.info("组件大小分布:");
        sizeDistribution.forEach((range, count) -> {
            if (count > 0) {
                log.info("  {}: {} 个组件", range, count);
            }
        });

        // 统计依赖关系
        int totalDependencies = knowledgeBase.getModules().values().stream()
                .mapToInt(m -> m.getDependencies().size())
                .sum();
        double avgDependencies = totalComponents > 0 ? (double) totalDependencies / totalComponents : 0;

        log.info("总依赖关系数: {}", totalDependencies);
        log.info("平均每组件依赖数: {:.2f}", avgDependencies);

        log.info("===================");
    }

    /**
     * 优化模块命名
     */
    private void optimizeModuleNames(KnowledgeBase knowledgeBase) {
        log.info("优化模块命名...");

        for (FunctionalModule module : knowledgeBase.getModules().values()) {
            try {
                String originalName = module.getName();
                String description = module.getDescription();

                // 使用LLM优化命名
                String optimizedName = llmService.optimizeModuleName(
                        originalName,
                        module.getEntities(),
                        description
                );

                if (optimizedName != null && !optimizedName.isEmpty() &&
                        !optimizedName.equals(originalName)) {
                    log.debug("模块命名优化: {} -> {}", originalName, optimizedName);
                    module.setName(optimizedName);
                }

                // 生成功能标签
                if (module.getFunctionalTags().isEmpty()) {
                    List<String> tags = generateFunctionalTags(module, knowledgeBase);
                    module.setFunctionalTags(tags);
                }

            } catch (Exception e) {
                log.error("优化模块命名失败: {}", module.getName(), e);
            }
        }

        log.info("模块命名优化完成");
    }

    /**
     * 生成功能标签
     */
    private List<String> generateFunctionalTags(FunctionalModule module, KnowledgeBase knowledgeBase) {
        Set<String> tags = new HashSet<>();

        // 从实体的语义信息中提取标签
        for (String entityName : module.getEntities()) {
            CodeEntity entity = knowledgeBase.getEntities().get(entityName);
            if (entity != null && entity.getSemanticInfo() != null) {
                tags.addAll(entity.getSemanticInfo().getFunctionalTags());
            }
        }

        return new ArrayList<>(tags);
    }

    /**
     * 创建UML组件
     */
    private void createUMLComponents(KnowledgeBase knowledgeBase) {
        log.info("创建UML组件...");
        assignedClasses.clear(); // 清空已分配类集合

        // 验证模块分配情况
        validateModuleDistribution(knowledgeBase);

        for (FunctionalModule module : knowledgeBase.getModules().values()) {
            // UML2 5.0.1: 使用UMLFactory创建Component，然后添加到model
            Component component = UMLFactory.eINSTANCE.createComponent();

            // 确保组件名称有效（不包含LLM推理文本）
            String componentName = sanitizeComponentName(module.getName());
            component.setName(componentName);
            model.getPackagedElements().add(component);

            // 设置组件描述
            if (module.getDescription() != null) {
                Comment comment = component.createOwnedComment();
                comment.setBody(module.getDescription());
            }

            // 添加关键词作为标签（使用注释方式，因为 keywords 可能不可修改）
            if (module.getFunctionalTags() != null && !module.getFunctionalTags().isEmpty()) {
                Comment tagsComment = component.createOwnedComment();
                tagsComment.setBody("Tags: " + String.join(", ", module.getFunctionalTags()));
            }

            // 统计实际添加的类数量
            int addedClassCount = 0;

            // 为组件中的每个类创建内部类（只为未分配的类创建）
            for (String entityName : module.getEntities()) {
                // 检查类是否已经在其他组件中定义
                if (!assignedClasses.contains(entityName)) {
                    CodeEntity entity = knowledgeBase.getEntities().get(entityName);
                    if (entity != null) {
                        // 创建内部类表示
                        Class internalClass = component.createOwnedClass(entity.getName(), false);
                        assignedClasses.add(entityName); // 标记为已分配
                        addedClassCount++;

                        // 添加注释
                        if (entity.getComment() != null) {
                            Comment classComment = internalClass.createOwnedComment();
                            classComment.setBody(entity.getComment());
                        }
                    }
                }
            }

            componentMap.put(module.getId(), component);
            log.debug("创建组件: {} (包含 {} 个类)", componentName, addedClassCount);
        }

        log.info("UML组件创建完成");

        // 处理未分配的类（改进版：就近分配或创建Utility组件）
        handleUnassignedClasses(knowledgeBase);
    }

    /**
     * 处理未分配的类：尝试就近分配到依赖最强的模块，否则创建Utility组件
     */
    private void handleUnassignedClasses(KnowledgeBase knowledgeBase) {
        Set<String> allClasses = knowledgeBase.getEntities().keySet();
        Set<String> unassigned = new HashSet<>(allClasses);
        unassigned.removeAll(assignedClasses);

        if (unassigned.isEmpty()) {
            return;
        }

        log.info("处理 {} 个未分配的类", unassigned.size());

        // 构建模块ID到实体集合的反向映射
        Map<String, String> entityToModuleId = new HashMap<>();
        for (FunctionalModule module : knowledgeBase.getModules().values()) {
            for (String entityName : module.getEntities()) {
                entityToModuleId.put(entityName, module.getId());
            }
        }

        // 尝试就近分配：计算每个未分配类与已有模块的依赖强度
        List<String> stillUnassigned = new ArrayList<>();
        int nearAssignedCount = 0;

        for (String entityName : unassigned) {
            CodeEntity entity = knowledgeBase.getEntities().get(entityName);
            if (entity == null) continue;

            // 统计与每个模块的依赖数量
            Map<String, Integer> moduleDepCount = new HashMap<>();
            for (String dep : entity.getDependencies()) {
                String moduleId = entityToModuleId.get(dep);
                if (moduleId != null) {
                    moduleDepCount.merge(moduleId, 1, Integer::sum);
                }
            }

            // 也统计被依赖关系（其他模块中的类依赖此类）
            for (Map.Entry<String, CodeEntity> otherEntry : knowledgeBase.getEntities().entrySet()) {
                if (otherEntry.getValue().getDependencies().contains(entityName)) {
                    String moduleId = entityToModuleId.get(otherEntry.getKey());
                    if (moduleId != null) {
                        moduleDepCount.merge(moduleId, 1, Integer::sum);
                    }
                }
            }

            // 找到依赖最强的模块
            String bestModuleId = moduleDepCount.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .filter(e -> e.getValue() >= 2) // 至少2个依赖才分配
                    .map(Map.Entry::getKey)
                    .orElse(null);

            if (bestModuleId != null) {
                // 就近分配到依赖最强的模块
                Component component = componentMap.get(bestModuleId);
                if (component != null && !assignedClasses.contains(entityName)) {
                    Class internalClass = component.createOwnedClass(entity.getName(), false);
                    assignedClasses.add(entityName);
                    entityToModuleId.put(entityName, bestModuleId);
                    nearAssignedCount++;

                    // 添加<<reassigned>> stereotype注释
                    Comment comment = internalClass.createOwnedComment();
                    comment.setBody("<<reassigned>> 基于依赖分析就近分配");
                }
            } else {
                stillUnassigned.add(entityName);
            }
        }

        if (nearAssignedCount > 0) {
            log.info("就近分配了 {} 个类到已有组件", nearAssignedCount);
        }

        // 对仍未分配的类创建Utility组件
        if (!stillUnassigned.isEmpty()) {
            Component utilityComponent = UMLFactory.eINSTANCE.createComponent();
            utilityComponent.setName("Utility");
            model.getPackagedElements().add(utilityComponent);

            Comment desc = utilityComponent.createOwnedComment();
            desc.setBody("<<unassigned>> 包含未能自动分配到功能模块的类");

            int utilityCount = 0;
            for (String entityName : stillUnassigned) {
                CodeEntity entity = knowledgeBase.getEntities().get(entityName);
                if (entity != null && !assignedClasses.contains(entityName)) {
                    Class internalClass = utilityComponent.createOwnedClass(entity.getName(), false);
                    Comment classComment = internalClass.createOwnedComment();
                    classComment.setBody("<<unassigned>>");
                    assignedClasses.add(entityName);
                    utilityCount++;
                }
            }

            // 使用固定ID注册到componentMap
            String utilityId = "utility-component";
            componentMap.put(utilityId, utilityComponent);
            log.info("创建Utility组件，包含 {} 个未分配的类", utilityCount);
        }
    }

    /**
     * 验证模块分配情况，检测"上帝组件"问题
     */
    private void validateModuleDistribution(KnowledgeBase knowledgeBase) {
        int totalClasses = knowledgeBase.getEntities().size();
        int totalModules = knowledgeBase.getModules().size();

        if (totalModules == 0) {
            log.error("❌ 没有识别出任何模块！");
            return;
        }

        log.info("模块分配验证: {} 个类分配到 {} 个模块", totalClasses, totalModules);

        // 检查每个模块的大小
        for (FunctionalModule module : knowledgeBase.getModules().values()) {
            int moduleSize = module.getEntities().size();
            double percentage = (double) moduleSize / totalClasses * 100;

            if (percentage > 70) {
                log.error("❌ 检测到上帝组件: {} 包含 {} 个类 ({:.1f}% 的所有类)！",
                         module.getName(), moduleSize, percentage);
                log.error("   建议: 检查 ArchitectureRecoveryService 的模块识别逻辑");
            } else if (percentage > 50) {
                log.warn("⚠️ 组件 {} 过大: {} 个类 ({:.1f}%)",
                        module.getName(), moduleSize, percentage);
            } else {
                log.debug("✓ 组件 {} 大小合理: {} 个类 ({:.1f}%)",
                         module.getName(), moduleSize, percentage);
            }
        }

        // 检查是否有空组件
        long emptyModules = knowledgeBase.getModules().values().stream()
                .filter(m -> m.getEntities().isEmpty())
                .count();
        if (emptyModules > 0) {
            log.warn("⚠️ 有 {} 个空组件（不包含任何类）", emptyModules);
        }
    }

    /**
     * 清理组件名称，移除可能的LLM推理文本
     */
    private String sanitizeComponentName(String name) {
        if (name == null || name.isEmpty()) {
            return "UnknownComponent";
        }

        // 如果名称过长（超过50个字符），可能包含LLM推理文本
        if (name.length() > 50) {
            log.warn("⚠️ 组件名称过长 ({} 字符)，可能包含LLM推理文本: ...",
                    name.length(), name.substring(0, Math.min(50, name.length())));
            return "UnknownComponent";
        }

        // 如果名称包含换行符或特殊字符，可能是LLM推理文本
        if (name.contains("\n") || name.contains("```") || name.contains("json")) {
            log.warn("⚠️ 组件名称包含特殊字符，可能是LLM推理文本");
            return "UnknownComponent";
        }

        // 移除前后空白
        return name.trim();
    }

    /**
     * 创建组件间的依赖关系
     */
    private void createComponentDependencies(KnowledgeBase knowledgeBase) {
        log.info("创建组件依赖关系...");

        for (FunctionalModule module : knowledgeBase.getModules().values()) {
            Component component = componentMap.get(module.getId());
            if (component == null) {
                continue;
            }

            // 创建依赖关系
            for (String depModuleId : module.getDependencies()) {
                Component depComponent = componentMap.get(depModuleId);
                if (depComponent != null) {
                    // 创建Usage依赖
                    Usage usage = component.createUsage(depComponent);
                    usage.setName(component.getName() + "_uses_" + depComponent.getName());

                    log.debug("创建依赖: {} -> {}", component.getName(), depComponent.getName());
                }
            }
        }

        log.info("组件依赖关系创建完成");
    }

    /**
     * 创建接口和端口（改进版：基于实际公共API方法和跨组件调用分析）
     */
    private void createInterfacesAndPorts(KnowledgeBase knowledgeBase) {
        log.info("创建接口和端口...");

        // 构建实体到模块ID的映射
        Map<String, String> entityToModuleId = new HashMap<>();
        for (FunctionalModule module : knowledgeBase.getModules().values()) {
            for (String entityName : module.getEntities()) {
                entityToModuleId.put(entityName, module.getId());
            }
        }

        for (FunctionalModule module : knowledgeBase.getModules().values()) {
            Component component = componentMap.get(module.getId());
            if (component == null) {
                continue;
            }

            // === Provided Interface ===
            // 1. 组件内的Java接口类型
            Set<String> providedInterfaceNames = new HashSet<>();
            for (String entityName : module.getEntities()) {
                CodeEntity entity = knowledgeBase.getEntities().get(entityName);
                if (entity == null) continue;

                if (entity.getType() == CodeEntity.EntityType.INTERFACE) {
                    providedInterfaceNames.add(entity.getName());
                }

                // 2. @RestController/@Controller 的端点方法天然是Provided Interface
                if (entity.getAnnotations().contains("RestController") ||
                    entity.getAnnotations().contains("Controller")) {
                    providedInterfaceNames.add(entity.getName() + "API");
                }

                // 3. 被其他组件调用的公共方法所在的类
                if (entity.getAnnotations().contains("Service") ||
                    entity.getAnnotations().contains("Component")) {
                    // 检查是否有其他组件的类依赖此实体
                    boolean calledByOtherModule = false;
                    for (Map.Entry<String, CodeEntity> otherEntry : knowledgeBase.getEntities().entrySet()) {
                        String otherModuleId = entityToModuleId.get(otherEntry.getKey());
                        if (otherModuleId != null && !otherModuleId.equals(module.getId())) {
                            if (otherEntry.getValue().getDependencies().contains(entityName)) {
                                calledByOtherModule = true;
                                break;
                            }
                        }
                    }
                    if (calledByOtherModule) {
                        // 使用类名作为接口名（去掉Impl后缀）
                        String ifaceName = entity.getName();
                        if (ifaceName.endsWith("Impl")) {
                            ifaceName = ifaceName.substring(0, ifaceName.length() - 4);
                        }
                        providedInterfaceNames.add("I" + ifaceName);
                    }
                }
            }

            // 创建提供接口
            for (String interfaceName : providedInterfaceNames) {
                Interface umlInterface = model.createOwnedInterface(
                        component.getName() + "_" + interfaceName
                );

                Port port = component.createOwnedPort(interfaceName + "Port", umlInterface);
                port.setIsService(true);

                InterfaceRealization realization = component.createInterfaceRealization(
                        interfaceName + "_realization",
                        umlInterface
                );

                log.debug("创建提供接口: {} for {}", interfaceName, component.getName());
            }

            // === Required Interface ===
            // 基于组件实际调用的其他组件的实体来生成
            Map<String, Set<String>> requiredByModule = new HashMap<>();
            for (String entityName : module.getEntities()) {
                CodeEntity entity = knowledgeBase.getEntities().get(entityName);
                if (entity == null) continue;

                for (String dep : entity.getDependencies()) {
                    String depModuleId = entityToModuleId.get(dep);
                    if (depModuleId != null && !depModuleId.equals(module.getId())) {
                        requiredByModule.computeIfAbsent(depModuleId, k -> new HashSet<>()).add(dep);
                    }
                }
            }

            for (Map.Entry<String, Set<String>> reqEntry : requiredByModule.entrySet()) {
                FunctionalModule depModule = knowledgeBase.getModules().get(reqEntry.getKey());
                if (depModule == null) continue;

                // 接口命名：基于被调用的类名生成更有意义的名称
                String requiredInterfaceName;
                Set<String> calledEntities = reqEntry.getValue();
                if (calledEntities.size() == 1) {
                    String calledName = calledEntities.iterator().next();
                    calledName = calledName.substring(calledName.lastIndexOf('.') + 1);
                    requiredInterfaceName = "I" + calledName;
                } else {
                    requiredInterfaceName = depModule.getName() + "Interface";
                }

                Interface requiredInterface = model.createOwnedInterface(
                        component.getName() + "_requires_" + requiredInterfaceName
                );

                Port requiredPort = component.createOwnedPort(
                        requiredInterfaceName + "Port",
                        requiredInterface
                );
                requiredPort.setIsService(false);

                log.debug("创建需求接口: {} for {}", requiredInterfaceName, component.getName());
            }
        }

        log.info("接口和端口创建完成");
    }

    /**
     * 保存组件图XMI
     */
    private String saveComponentDiagram(File outputDir, String filename) throws IOException {
        String filePath = new File(outputDir, filename).getAbsolutePath();

        // 使用 .uml 扩展名以确保正确的资源工厂被使用
        URI fileURI = URI.createFileURI(filePath);

        // 检查资源是否已存在，如果存在则先移除
        Resource existingResource = resourceSet.getResource(fileURI, false);
        if (existingResource != null) {
            resourceSet.getResources().remove(existingResource);
        }

        // 创建新资源
        Resource resource = resourceSet.createResource(fileURI);

        if (resource == null) {
            // 如果仍然为null，尝试使用全局注册表
            log.warn("使用ResourceSet创建资源失败，尝试使用全局注册表");
            Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                    .put(UMLResource.FILE_EXTENSION, UMLResource.Factory.INSTANCE);
            resource = resourceSet.createResource(fileURI);
        }

        if (resource == null) {
            throw new IOException("无法创建UML资源，资源工厂可能未正确注册");
        }

        resource.getContents().add(model);

        Map<String, Object> saveOptions = new HashMap<>();
        saveOptions.put(UMLResource.OPTION_SAVE_ONLY_IF_CHANGED,
                UMLResource.OPTION_SAVE_ONLY_IF_CHANGED_MEMORY_BUFFER);
        saveOptions.put(org.eclipse.emf.ecore.xmi.XMLResource.OPTION_ENCODING, "UTF-8");

        resource.save(saveOptions);

        log.info("组件图已保存到: {}", filePath);
        return filePath;
    }

    /**
     * 组件图生成结果
     */
    public static class ComponentGenerationResult {
        private String componentXMIPath;
        private int totalComponents;
        private List<FunctionalModule> modules;

        public String getComponentXMIPath() {
            return componentXMIPath;
        }

        public void setComponentXMIPath(String componentXMIPath) {
            this.componentXMIPath = componentXMIPath;
        }

        public int getTotalComponents() {
            return totalComponents;
        }

        public void setTotalComponents(int totalComponents) {
            this.totalComponents = totalComponents;
        }

        public List<FunctionalModule> getModules() {
            return modules;
        }

        public void setModules(List<FunctionalModule> modules) {
            this.modules = modules;
        }
    }
}
