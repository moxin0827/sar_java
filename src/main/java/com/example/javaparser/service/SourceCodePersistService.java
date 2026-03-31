package com.example.javaparser.service;

import com.example.javaparser.entity.ClassInfo;
import com.example.javaparser.entity.ClassRelation;
import com.example.javaparser.repository.ClassInfoRepository;
import com.example.javaparser.repository.ClassRelationRepository;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.Class;
import org.eclipse.uml2.uml.Enumeration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SourceCodePersistService {

    @Autowired
    private JavaToUML2Service javaToUML2Service;

    @Autowired
    private ClassInfoRepository classInfoRepository;

    @Autowired
    private ClassRelationRepository classRelationRepository;

    private final Gson gson = new Gson();

    /**
     * 解析并持久化源码，支持指定输出目录
     * @param projectId 项目ID
     * @param sourcePath 源码路径
     * @param outputDir 输出目录（可选，为null时使用系统临时目录）
     */
    public void parseAndPersist(Long projectId, String sourcePath, String outputDir) throws IOException {
        log.info("开始解析并持久化源码: projectId={}, path={}, outputDir={}", projectId, sourcePath, outputDir);

        javaToUML2Service.initialize();

        // 使用指定的outputDir，如果为null则回退到系统临时目录
        String tmpOut = (outputDir != null && !outputDir.isEmpty())
            ? outputDir
            : System.getProperty("java.io.tmpdir") + "/sar-parse-" + projectId;

        new java.io.File(tmpOut).mkdirs();
        javaToUML2Service.parseAndGenerateXMI(sourcePath, tmpOut);

        Map<String, Classifier> classifierMap = javaToUML2Service.getClassifierMap();
        Map<String, JavaToUML2Service.ClassTextInfo> textInfoMap = javaToUML2Service.getClassTextInfoMap();

        // 优化：预加载已存在的FQN集合，避免N次数据库查询
        Set<String> existingFqns = classInfoRepository.findByProjectId(projectId).stream()
                .map(ClassInfo::getFullyQualifiedName)
                .collect(Collectors.toSet());

        // 优化：预构建反向索引 Classifier -> FQN，将O(N)查找降为O(1)
        Map<Classifier, String> reverseClassifierMap = new IdentityHashMap<>();
        for (Map.Entry<String, Classifier> entry : classifierMap.entrySet()) {
            reverseClassifierMap.put(entry.getValue(), entry.getKey());
        }

        // 优化：批量收集后一次性保存
        List<ClassInfo> classInfoBatch = new ArrayList<>();
        for (Map.Entry<String, Classifier> entry : classifierMap.entrySet()) {
            String fqn = entry.getKey();
            Classifier classifier = entry.getValue();

            if (existingFqns.contains(fqn)) {
                continue;
            }

            ClassInfo ci = new ClassInfo();
            ci.setProjectId(projectId);
            ci.setFullyQualifiedName(fqn);
            int lastDot = fqn.lastIndexOf('.');
            ci.setSimpleName(lastDot > 0 ? fqn.substring(lastDot + 1) : fqn);
            ci.setPackageName(lastDot > 0 ? fqn.substring(0, lastDot) : "default");
            ci.setClassType(classifier.eClass().getName());

            // 提取修饰符、父类、接口（使用反向索引）
            extractClassifierDetails(ci, classifier, reverseClassifierMap);

            // 提取字段和方法的结构化详情
            extractStructuredDetails(ci, classifier);

            // 提取文本信息（javadoc、注解、名称列表）
            JavaToUML2Service.ClassTextInfo textInfo = textInfoMap.get(fqn);
            if (textInfo != null) {
                ci.setJavadocComment(textInfo.javadoc);
                ci.setAnnotations(String.join(",", textInfo.annotations));
                ci.setMethodNames(String.join(",", textInfo.methodNames));
                ci.setFieldNames(String.join(",", textInfo.fieldNames));
            }

            classInfoBatch.add(ci);
        }
        if (!classInfoBatch.isEmpty()) {
            classInfoRepository.saveAll(classInfoBatch);
            log.info("批量保存 {} 个ClassInfo", classInfoBatch.size());
        }

        // 优化：批量保存ClassRelation
        List<ClassRelation> relationBatch = new ArrayList<>();
        for (JavaToUML2Service.PendingRelation pr : javaToUML2Service.getPendingRelations()) {
            ClassRelation cr = new ClassRelation();
            cr.setProjectId(projectId);
            cr.setSourceClassName(pr.source);
            cr.setTargetClassName(pr.target);
            cr.setRelationType(pr.type);
            cr.setSourceMultiplicity(pr.sourceMultiplicity);
            cr.setTargetMultiplicity(pr.targetMultiplicity);
            cr.setAssociationName(pr.fieldName);
            relationBatch.add(cr);
        }
        if (!relationBatch.isEmpty()) {
            classRelationRepository.saveAll(relationBatch);
        }

        log.info("源码持久化完成: {} 个类, {} 个关系",
                classifierMap.size(), javaToUML2Service.getPendingRelations().size());
    }

    /**
     * 解析并持久化源码（向后兼容方法）
     * @param projectId 项目ID
     * @param sourcePath 源码路径
     */
    public void parseAndPersist(Long projectId, String sourcePath) throws IOException {
        // 委托到新方法，使用 null outputDir（向后兼容）
        parseAndPersist(projectId, sourcePath, null);
    }

    /**
     * 从Classifier对象提取修饰符、父类、接口信息
     * 使用反向索引 reverseMap (Classifier -> FQN) 实现O(1)查找
     */
    private void extractClassifierDetails(ClassInfo ci, Classifier classifier,
                                           Map<Classifier, String> reverseMap) {
        List<String> modifiers = new ArrayList<>();

        if (classifier instanceof Class) {
            Class umlClass = (Class) classifier;
            if (umlClass.isAbstract()) modifiers.add("abstract");

            // 提取父类
            List<Generalization> generalizations = umlClass.getGeneralizations();
            if (!generalizations.isEmpty()) {
                Classifier general = generalizations.get(0).getGeneral();
                if (general != null) {
                    String superFqn = reverseMap.get(general);
                    ci.setSuperClass(superFqn != null ? superFqn : general.getName());
                }
            }

            // 提取实现的接口
            List<String> ifaceNames = new ArrayList<>();
            for (InterfaceRealization ir : umlClass.getInterfaceRealizations()) {
                Interface iface = ir.getContract();
                if (iface != null) {
                    String ifaceFqn = reverseMap.get(iface);
                    ifaceNames.add(ifaceFqn != null ? ifaceFqn : iface.getName());
                }
            }
            if (!ifaceNames.isEmpty()) {
                ci.setInterfaces(String.join(",", ifaceNames));
            }
        } else if (classifier instanceof Interface) {
            modifiers.add("interface");
            Interface umlInterface = (Interface) classifier;
            // 接口继承接口
            List<String> superIfaceNames = new ArrayList<>();
            for (Generalization gen : umlInterface.getGeneralizations()) {
                Classifier general = gen.getGeneral();
                if (general != null) {
                    String superFqn = reverseMap.get(general);
                    superIfaceNames.add(superFqn != null ? superFqn : general.getName());
                }
            }
            if (!superIfaceNames.isEmpty()) {
                ci.setInterfaces(String.join(",", superIfaceNames));
            }
        } else if (classifier instanceof Enumeration) {
            modifiers.add("enum");
        }

        if (!modifiers.isEmpty()) {
            ci.setModifiers(String.join(",", modifiers));
        }
    }

    /**
     * 提取字段和方法的结构化JSON详情
     */
    private void extractStructuredDetails(ClassInfo ci, Classifier classifier) {
        // 提取字段详情
        List<Map<String, Object>> fieldDetailsList = new ArrayList<>();
        if (classifier instanceof Class) {
            for (Property prop : ((Class) classifier).getOwnedAttributes()) {
                Map<String, Object> fd = new LinkedHashMap<>();
                fd.put("name", prop.getName());
                fd.put("type", prop.getType() != null ? prop.getType().getName() : "Object");
                fd.put("visibility", visibilityToString(prop.getVisibility()));
                fd.put("isStatic", prop.isStatic());
                fd.put("isFinal", prop.isReadOnly());
                fieldDetailsList.add(fd);
            }
        }
        if (!fieldDetailsList.isEmpty()) {
            ci.setFieldDetails(gson.toJson(fieldDetailsList));
        }

        // 提取方法详情
        List<Map<String, Object>> methodDetailsList = new ArrayList<>();
        List<Operation> operations = Collections.emptyList();
        if (classifier instanceof Class) {
            operations = ((Class) classifier).getOwnedOperations();
        } else if (classifier instanceof Interface) {
            operations = ((Interface) classifier).getOwnedOperations();
        }

        for (Operation op : operations) {
            Map<String, Object> md = new LinkedHashMap<>();
            md.put("name", op.getName());
            md.put("visibility", visibilityToString(op.getVisibility()));
            md.put("isStatic", op.isStatic());
            md.put("isAbstract", op.isAbstract());

            // 返回类型
            String returnType = "void";
            List<Map<String, String>> params = new ArrayList<>();
            for (Parameter param : op.getOwnedParameters()) {
                if (param.getDirection() == ParameterDirectionKind.RETURN_LITERAL) {
                    returnType = param.getType() != null ? param.getType().getName() : "Object";
                } else {
                    Map<String, String> p = new LinkedHashMap<>();
                    p.put("name", param.getName());
                    p.put("type", param.getType() != null ? param.getType().getName() : "Object");
                    params.add(p);
                }
            }
            md.put("returnType", returnType);
            md.put("params", params);
            methodDetailsList.add(md);
        }
        if (!methodDetailsList.isEmpty()) {
            ci.setMethodDetails(gson.toJson(methodDetailsList));
        }
    }

    private String visibilityToString(VisibilityKind visibility) {
        if (visibility == null) return "package";
        switch (visibility.getValue()) {
            case VisibilityKind.PUBLIC: return "public";
            case VisibilityKind.PRIVATE: return "private";
            case VisibilityKind.PROTECTED: return "protected";
            default: return "package";
        }
    }

    public boolean ensureParsed(Long projectId) {
        return !classInfoRepository.findByProjectId(projectId).isEmpty();
    }
}
