package com.example.javaparser.service.recovery;

import com.example.javaparser.entity.ClassInfo;
import com.example.javaparser.entity.ClassRelation;
import com.example.javaparser.entity.RecoveredComponent;
import com.example.javaparser.repository.ClassInfoRepository;
import com.example.javaparser.repository.ClassRelationRepository;
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
 * 从 RecoveredComponent 数据库实体生成 UML2 Component XMI 文件
 */
@Slf4j
@Service
public class RecoveredComponentToXmiService {

    @Autowired
    private ClassInfoRepository classInfoRepository;

    @Autowired
    private ClassRelationRepository classRelationRepository;

    private ResourceSet resourceSet;
    private Model model;

    /**
     * 从恢复的组件生成 component.xmi
     *
     * @param projectId 项目ID
     * @param components 恢复的组件列表
     * @param outputDir 输出目录
     * @return component.xmi 的绝对路径
     */
    public String generateComponentXmi(Long projectId, List<RecoveredComponent> components, String outputDir) throws IOException {
        log.info("开始从 RecoveredComponent 生成 component.xmi: projectId={}, components={}", projectId, components.size());

        if (components.isEmpty()) {
            log.warn("没有组件可生成，跳过 component.xmi 生成");
            return null;
        }

        // 初始化 UML2 资源集
        initialize();

        // 加载项目的所有类信息和关系
        List<ClassInfo> allClasses = classInfoRepository.findByProjectId(projectId);
        List<ClassRelation> allRelations = classRelationRepository.findByProjectId(projectId);

        // 构建类名到 ClassInfo 的映射
        Map<String, ClassInfo> classInfoMap = new HashMap<>();
        for (ClassInfo ci : allClasses) {
            classInfoMap.put(ci.getFullyQualifiedName(), ci);
        }

        // 构建类到组件的映射
        Map<String, String> classToComponentName = new HashMap<>();
        for (RecoveredComponent comp : components) {
            if (comp.getClassNames() != null && !comp.getClassNames().isEmpty()) {
                for (String fqn : comp.getClassNames().split(",")) {
                    classToComponentName.put(fqn.trim(), comp.getName());
                }
            }
        }

        // 创建 UML 组件
        Map<String, Component> componentMap = createUMLComponents(components, classInfoMap);

        // 创建组件间依赖关系
        createComponentDependencies(components, allRelations, classToComponentName, componentMap);

        // 保存 XMI 文件
        File outputDirFile = new File(outputDir);
        if (!outputDirFile.exists()) {
            outputDirFile.mkdirs();
        }

        String xmiPath = saveComponentDiagram(outputDirFile, "component.xmi");
        log.info("component.xmi 生成完成: {}", xmiPath);

        return xmiPath;
    }

    /**
     * 初始化 UML2 资源集
     */
    private void initialize() {
        log.debug("初始化 UML2 资源集...");

        resourceSet = new ResourceSetImpl();

        // 注册 UML 资源工厂
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put(UMLResource.FILE_EXTENSION, UMLResource.Factory.INSTANCE);
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("xmi", UMLResource.Factory.INSTANCE);

        resourceSet.getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put(UMLResource.FILE_EXTENSION, UMLResource.Factory.INSTANCE);
        resourceSet.getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put("xmi", UMLResource.Factory.INSTANCE);

        // 注册 UML 包
        resourceSet.getPackageRegistry()
                .put(UMLPackage.eNS_URI, UMLPackage.eINSTANCE);

        // 创建 UML 模型
        model = UMLFactory.eINSTANCE.createModel();
        model.setName("ComponentModel");

        log.debug("UML2 资源集初始化完成");
    }

    /**
     * 创建 UML 组件
     */
    private Map<String, Component> createUMLComponents(List<RecoveredComponent> components,
                                                       Map<String, ClassInfo> classInfoMap) {
        log.debug("创建 UML 组件...");

        Map<String, Component> componentMap = new HashMap<>();
        Set<String> assignedClasses = new HashSet<>();

        for (RecoveredComponent recoveredComp : components) {
            // 创建 UML Component
            Component umlComponent = UMLFactory.eINSTANCE.createComponent();
            umlComponent.setName(recoveredComp.getName());
            model.getPackagedElements().add(umlComponent);

            // 添加来源标签
            if (recoveredComp.getSource() != null) {
                Comment sourceComment = umlComponent.createOwnedComment();
                sourceComment.setBody("Source: " + recoveredComp.getSource());
            }

            // 添加层级信息
            if (recoveredComp.getLevel() != null) {
                Comment levelComment = umlComponent.createOwnedComment();
                levelComment.setBody("Level: " + recoveredComp.getLevel());
            }

            // 为组件中的每个类创建内部类
            if (recoveredComp.getClassNames() != null && !recoveredComp.getClassNames().isEmpty()) {
                String[] classNames = recoveredComp.getClassNames().split(",");
                int addedCount = 0;

                for (String fqn : classNames) {
                    fqn = fqn.trim();
                    if (fqn.isEmpty() || assignedClasses.contains(fqn)) {
                        continue;
                    }

                    ClassInfo classInfo = classInfoMap.get(fqn);
                    if (classInfo != null) {
                        // 创建内部类
                        Class internalClass = umlComponent.createOwnedClass(classInfo.getSimpleName(), false);
                        assignedClasses.add(fqn);
                        addedCount++;

                        // 添加 Javadoc 注释
                        if (classInfo.getJavadocComment() != null && !classInfo.getJavadocComment().isEmpty()) {
                            Comment javadocComment = internalClass.createOwnedComment();
                            javadocComment.setBody(classInfo.getJavadocComment());
                        }
                    }
                }

                log.debug("组件 {} 包含 {} 个类", recoveredComp.getName(), addedCount);
            }

            componentMap.put(recoveredComp.getName(), umlComponent);
        }

        log.debug("创建了 {} 个 UML 组件", componentMap.size());
        return componentMap;
    }

    /**
     * 创建组件间依赖关系
     */
    private void createComponentDependencies(List<RecoveredComponent> components,
                                            List<ClassRelation> allRelations,
                                            Map<String, String> classToComponentName,
                                            Map<String, Component> componentMap) {
        log.debug("创建组件依赖关系...");

        // 统计组件间的依赖关系
        Map<String, Set<String>> componentDeps = new HashMap<>();

        for (ClassRelation relation : allRelations) {
            String srcComponent = classToComponentName.get(relation.getSourceClassName());
            String tgtComponent = classToComponentName.get(relation.getTargetClassName());

            // 只记录跨组件的依赖
            if (srcComponent != null && tgtComponent != null && !srcComponent.equals(tgtComponent)) {
                componentDeps.computeIfAbsent(srcComponent, k -> new HashSet<>()).add(tgtComponent);
            }
        }

        // 创建 UML Usage 依赖关系
        int depCount = 0;
        for (Map.Entry<String, Set<String>> entry : componentDeps.entrySet()) {
            Component srcComp = componentMap.get(entry.getKey());
            if (srcComp == null) continue;

            for (String tgtName : entry.getValue()) {
                Component tgtComp = componentMap.get(tgtName);
                if (tgtComp != null) {
                    Usage usage = srcComp.createUsage(tgtComp);
                    usage.setName(srcComp.getName() + "_uses_" + tgtComp.getName());
                    depCount++;
                    log.debug("创建依赖: {} -> {}", srcComp.getName(), tgtComp.getName());
                }
            }
        }

        log.debug("创建了 {} 个组件依赖关系", depCount);
    }

    /**
     * 保存组件图 XMI
     */
    private String saveComponentDiagram(File outputDir, String filename) throws IOException {
        String filePath = new File(outputDir, filename).getAbsolutePath();
        URI fileURI = URI.createFileURI(filePath);

        // 检查资源是否已存在
        Resource existingResource = resourceSet.getResource(fileURI, false);
        if (existingResource != null) {
            resourceSet.getResources().remove(existingResource);
        }

        // 创建新资源
        Resource resource = resourceSet.createResource(fileURI);
        if (resource == null) {
            throw new IOException("无法创建 UML 资源，资源工厂可能未正确注册");
        }

        resource.getContents().add(model);

        // 保存选项
        Map<String, Object> saveOptions = new HashMap<>();
        saveOptions.put(UMLResource.OPTION_SAVE_ONLY_IF_CHANGED,
                UMLResource.OPTION_SAVE_ONLY_IF_CHANGED_MEMORY_BUFFER);
        saveOptions.put(org.eclipse.emf.ecore.xmi.XMLResource.OPTION_ENCODING, "UTF-8");

        resource.save(saveOptions);

        log.info("组件图已保存到: {}", filePath);
        return filePath;
    }
}
